package com.gameocr.app.inpaint

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * Apagamento de texto com LaMa (ONNX, entrada fixa 512x512), opcional e baixado sob demanda.
 * Para cada caixa, recorta uma janela quadrada com contexto, roda o modelo e cola de volta só a
 * região da máscara. Os resultados ficam em cache para o editor não reprocessar a cada ajuste.
 */
@Singleton
class LamaInpainter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
) {
    private val modelFile: File get() = File(File(context.filesDir, "models/lama").apply { mkdirs() }, MODEL_NAME)
    private val lock = Any()
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private val patchCache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 40
    }

    fun isInstalled(): Boolean = modelFile.isFile && modelFile.length() > MIN_MODEL_BYTES

    fun sizeMb(): Long = if (modelFile.isFile) modelFile.length() / (1024 * 1024) else 0L

    fun delete() {
        synchronized(lock) {
            closeSession()
            modelFile.delete()
        }
    }

    suspend fun download(onProgress: (downloaded: Long, total: Long) -> Unit) = withContext(Dispatchers.IO) {
        val target = modelFile
        val part = File(target.parentFile, "$MODEL_NAME.part")
        val request = Request.Builder().url(MODEL_URL).build()
        client.newBuilder().readTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build()
            .newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val body = checkNotNull(response.body) { "Resposta vazia" }
                val total = body.contentLength()
                var done = 0L
                body.byteStream().use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            onProgress(done, total)
                        }
                    }
                }
            }
        check(part.length() > MIN_MODEL_BYTES) { "Arquivo do modelo incompleto" }
        synchronized(lock) {
            closeSession()
            if (target.exists()) target.delete()
            check(part.renameTo(target)) { "Não foi possível salvar o modelo" }
        }
    }

    /**
     * Apaga o conteúdo de [rects] (coordenadas de [target], que deve ser mutável) usando [source]
     * como imagem de referência. Devolve false se o modelo não está instalado ou falhou.
     */
    fun inpaint(target: Bitmap, source: Bitmap, rects: List<Rect>): Boolean {
            if (rects.isEmpty()) return true
            if (!isInstalled()) return false
            return runCatching {
                val canvas = Canvas(target)
                rects.forEach { rect ->
                    val bounds = Rect(rect).apply {
                        inset(-MASK_DILATE_PX, -MASK_DILATE_PX)
                        intersect(0, 0, target.width, target.height)
                    }
                    if (bounds.isEmpty) return@forEach
                    val key = "${System.identityHashCode(source)}:${bounds.flattenToString()}"
                    val patch = synchronized(patchCache) { patchCache[key] }
                        ?: runModel(source, bounds).also { synchronized(patchCache) { patchCache[key] = it } }
                    canvas.drawBitmap(patch, bounds.left.toFloat(), bounds.top.toFloat(), null)
                }
                true
            }.onFailure { Timber.w(it, "LaMa inpaint failed") }.getOrDefault(false)
    }

    /** Devolve o trecho limpo, com o tamanho de [bounds]. */
    private fun runModel(source: Bitmap, bounds: Rect): Bitmap {
        val side = min(
            max(bounds.width(), bounds.height()) * 2 + 32,
            min(source.width, source.height),
        ).coerceAtLeast(max(bounds.width(), bounds.height()))
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val left = (cx - side / 2).coerceIn(0, max(0, source.width - side))
        val top = (cy - side / 2).coerceIn(0, max(0, source.height - side))
        val crop = Rect(left, top, min(source.width, left + side), min(source.height, top + side))

        val cropBitmap = Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height())
        val scaled = Bitmap.createScaledBitmap(cropBitmap, SIZE, SIZE, true)
        val pixels = IntArray(SIZE * SIZE)
        scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)

        val plane = SIZE * SIZE
        val image = FloatArray(3 * plane)
        val mask = FloatArray(plane)
        val sx = SIZE.toFloat() / crop.width()
        val sy = SIZE.toFloat() / crop.height()
        val mL = ((bounds.left - crop.left) * sx).toInt().coerceIn(0, SIZE)
        val mT = ((bounds.top - crop.top) * sy).toInt().coerceIn(0, SIZE)
        val mR = ((bounds.right - crop.left) * sx).toInt().coerceIn(0, SIZE)
        val mB = ((bounds.bottom - crop.top) * sy).toInt().coerceIn(0, SIZE)
        for (i in 0 until plane) {
            val c = pixels[i]
            image[i] = ((c shr 16) and 0xFF) / 255f
            image[plane + i] = ((c shr 8) and 0xFF) / 255f
            image[2 * plane + i] = (c and 0xFF) / 255f
            val x = i % SIZE
            val y = i / SIZE
            if (x in mL until mR && y in mT until mB) mask[i] = 1f
        }

        val out = FloatArray(3 * plane)
        synchronized(lock) {
            val environment = env ?: OrtEnvironment.getEnvironment().also { env = it }
            val ort = session ?: environment.createSession(
                modelFile.absolutePath,
                OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                },
            ).also { session = it }
            val names = ort.inputNames.toList()
            val maskName = names.firstOrNull { it.contains("mask", ignoreCase = true) } ?: names.last()
            val imageName = names.firstOrNull { it != maskName } ?: names.first()
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(image), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { imageTensor ->
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(mask), longArrayOf(1, 1, SIZE.toLong(), SIZE.toLong())).use { maskTensor ->
                    ort.run(mapOf(imageName to imageTensor, maskName to maskTensor)).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val array = result[0].value as Array<Array<Array<FloatArray>>>
                        for (c in 0 until 3) for (y in 0 until SIZE) {
                            System.arraycopy(array[0][c][y], 0, out, c * plane + y * SIZE, SIZE)
                        }
                    }
                }
            }
        }
        val maxValue = out.maxOrNull() ?: 255f
        val factor = if (maxValue <= 1.5f) 255f else 1f
        val outPixels = IntArray(plane)
        for (i in 0 until plane) {
            val r = (out[i] * factor).toInt().coerceIn(0, 255)
            val g = (out[plane + i] * factor).toInt().coerceIn(0, 255)
            val b = (out[2 * plane + i] * factor).toInt().coerceIn(0, 255)
            outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val outBitmap = Bitmap.createBitmap(outPixels, SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val full = Bitmap.createScaledBitmap(outBitmap, crop.width(), crop.height(), true)
        return Bitmap.createBitmap(
            full,
            bounds.left - crop.left,
            bounds.top - crop.top,
            bounds.width(),
            bounds.height(),
        )
    }

    private fun closeSession() {
        runCatching { session?.close() }
        session = null
        synchronized(patchCache) { patchCache.clear() }
    }

    private companion object {
        const val SIZE = 512
        const val MASK_DILATE_PX = 4
        const val MODEL_NAME = "lama_fp32.onnx"
        const val MODEL_URL = "https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx"
        const val MIN_MODEL_BYTES = 50L * 1024 * 1024
    }
}
