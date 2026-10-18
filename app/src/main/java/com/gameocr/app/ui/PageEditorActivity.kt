package com.gameocr.app.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gameocr.app.data.AppLocalePrefs
import com.gameocr.app.gallery.GalleryImageDecoder
import com.gameocr.app.gallery.GalleryRect
import com.gameocr.app.gallery.GalleryTranslatedImageRenderer
import com.gameocr.app.gallery.GalleryTranslationItemEntity
import com.gameocr.app.gallery.GalleryTranslationRepository
import com.gameocr.app.gallery.GalleryTranslationSegment
import com.gameocr.app.data.Settings
import com.gameocr.app.ui.theme.GameOcrTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import timber.log.Timber
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Editor de páginas traduzidas: texto, caixa, tamanho da fonte e exportação. */
@AndroidEntryPoint
class PageEditorActivity : ComponentActivity() {
    private val viewModel: PageEditorViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocalePrefs.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            GameOcrTheme(themeMode = com.gameocr.app.ui.theme.ThemeMode.LIGHT) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PageEditorScreen(viewModel = viewModel, onClose = { closeAfterSave() })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        viewModel.saveNow()
    }

    private fun closeAfterSave() {
        viewModel.saveNow()
        finish()
    }

    private fun handleIntent(intent: Intent?) {
        val itemId = intent?.getStringExtra(EXTRA_ITEM_ID)
        if (itemId != null) {
            viewModel.open(itemId)
        } else {
            viewModel.openLastReadingPage()
        }
    }

    companion object {
        const val EXTRA_ITEM_ID = "app.tradutormanga.extra.ITEM_ID"

        fun intent(context: Context, itemId: String? = null): Intent =
            Intent(context, PageEditorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { if (itemId != null) putExtra(EXTRA_ITEM_ID, itemId) }
    }
}

private enum class DragMode { NONE, PAN, MOVE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR }

@HiltViewModel
class PageEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: GalleryTranslationRepository,
    private val imageDecoder: GalleryImageDecoder,
    private val renderer: GalleryTranslatedImageRenderer,
    private val json: Json,
) : ViewModel() {
    var item by mutableStateOf<GalleryTranslationItemEntity?>(null)
        private set
    var segments by mutableStateOf<List<GalleryTranslationSegment>>(emptyList())
        private set
    var selected by mutableIntStateOf(-1)
        private set
    var rendered by mutableStateOf<Bitmap?>(null)
        private set
    var showOriginal by mutableStateOf(false)
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var pageIndex by mutableIntStateOf(0)
        private set
    var pageCount by mutableIntStateOf(0)
        private set
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)

    private var base: Bitmap? = null
    private var settings: Settings? = null
    private var pages: List<GalleryTranslationItemEntity> = emptyList()
    private var renderJob: Job? = null
    private var saveJob: Job? = null
    private var dirty = false
    private var openingId: String? = null

    /** Dimensões do espaço de coordenadas dos segmentos. */
    val coordWidth: Int get() = item?.processedWidth?.takeIf { it > 0 } ?: base?.width ?: 1
    val coordHeight: Int get() = item?.processedHeight?.takeIf { it > 0 } ?: base?.height ?: 1
    val originalBitmap: Bitmap? get() = base

    fun open(itemId: String) {
        if (openingId == itemId && item != null) return
        openingId = itemId
        viewModelScope.launch {
            saveNowInternal()
            val entity = repository.getItem(itemId) ?: return@launch
            load(entity)
        }
    }

    fun openLastReadingPage() {
        if (item != null) return
        viewModelScope.launch {
            val entity = repository.lastReadingPage()
            if (entity == null) {
                message = "Nenhuma página salva ainda. Traduza uma página na leitura primeiro."
            } else {
                openingId = entity.id
                load(entity)
            }
        }
    }

    private suspend fun load(entity: GalleryTranslationItemEntity) {
        busy = true
        try {
            val task = repository.getTask(entity.taskId)
            settings = task?.let { repository.settingsForTask(it) }
            pages = repository.getItems(entity.taskId).filter { it.localPath.isNotBlank() || it.sourceUri.isNotBlank() }
                .filter { it.status == com.gameocr.app.gallery.GalleryItemStatus.SUCCEEDED }
            pageCount = pages.size
            pageIndex = pages.indexOfFirst { it.id == entity.id }.coerceAtLeast(0)
            val decoded = withContext(Dispatchers.IO) { imageDecoder.decode(entity) }
            base = decoded.bitmap
            item = entity
            segments = runCatching {
                json.decodeFromString<List<GalleryTranslationSegment>>(entity.segmentsJson)
            }.getOrDefault(emptyList())
            selected = -1
            zoom = 1f
            pan = Offset.Zero
            dirty = false
            renderNow()
        } catch (error: Throwable) {
            Timber.w(error, "Editor could not load page")
            message = "Não foi possível abrir a página."
        } finally {
            busy = false
        }
    }

    fun goToPage(delta: Int) {
        val target = pages.getOrNull(pageIndex + delta) ?: return
        viewModelScope.launch {
            saveNowInternal()
            load(target)
        }
    }

    fun select(index: Int) {
        selected = index
    }

    fun effectiveBox(segment: GalleryTranslationSegment): GalleryRect =
        segment.renderBox ?: segment.boundingBox

    fun updateSelected(transform: (GalleryTranslationSegment) -> GalleryTranslationSegment) {
        val index = selected
        if (index !in segments.indices) return
        segments = segments.toMutableList().also { it[index] = transform(it[index]) }
        markChanged()
    }

    fun setBox(box: GalleryRect) {
        updateSelected { it.copy(renderBox = box) }
    }

    fun deleteSelected() {
        val index = selected
        if (index !in segments.indices) return
        segments = segments.toMutableList().also { it.removeAt(index) }
        selected = -1
        markChanged()
    }

    fun addBubble() {
        val w = coordWidth
        val h = coordHeight
        val box = GalleryRect(w / 4, h / 3, w * 3 / 4, h / 3 + h / 8)
        segments = segments + GalleryTranslationSegment(
            sourceText = "",
            translatedText = "Novo texto",
            boundingBox = box,
            sourceBoxes = listOf(box),
            confidence = 1f,
            renderBox = box,
        )
        selected = segments.lastIndex
        markChanged()
    }

    private fun markChanged() {
        dirty = true
        scheduleRender()
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            saveNowInternal()
        }
    }

    fun saveNow() {
        viewModelScope.launch { saveNowInternal() }
    }

    private suspend fun saveNowInternal() {
        val current = item ?: return
        if (!dirty) return
        dirty = false
        saveJob?.cancel()
        runCatching { repository.updateItemSegments(current.id, segments) }
            .onFailure { Timber.w(it, "Editor save failed") }
    }

    private fun scheduleRender() {
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            delay(RENDER_DEBOUNCE_MS)
            renderNow()
        }
    }

    private suspend fun renderNow() {
        val source = base ?: return
        val current = item ?: return
        val renderSettings = settings
        if (renderSettings == null) {
            rendered = source
            return
        }
        val snapshot = segments
        val output = withContext(Dispatchers.Default) {
            runCatching {
                renderer.render(
                    source = source,
                    processedWidth = current.processedWidth,
                    processedHeight = current.processedHeight,
                    segments = snapshot.filter { it.translatedText.isNotBlank() },
                    settings = renderSettings,
                )
            }.onFailure { Timber.w(it, "Editor render failed") }.getOrNull()
        }
        if (output != null) {
            val previous = rendered
            rendered = output
            if (previous != null && previous !== source && previous !== output && !previous.isRecycled) {
                // Evita reciclar enquanto o Compose ainda pode estar desenhando; deixa o GC cuidar.
            }
        }
    }

    fun exportCurrentPage() {
        val current = item ?: return
        viewModelScope.launch {
            saveNowInternal()
            busy = true
            val folder = projectFolderName(current)
            val ok = runCatching { exportItem(current, folder) }
                .onFailure { Timber.w(it, "Export page failed") }
                .isSuccess
            busy = false
            message = if (ok) "Página exportada em Imagens/TradutorManga/$folder" else "Falha ao exportar a página."
        }
    }

    fun exportAllPages() {
        val current = item ?: return
        viewModelScope.launch {
            saveNowInternal()
            busy = true
            val folder = projectFolderName(current)
            var done = 0
            var failed = 0
            pages.forEach { page ->
                runCatching {
                    val fresh = repository.getItem(page.id) ?: page
                    exportItem(fresh, folder)
                }.onSuccess { done++ }.onFailure {
                    failed++
                    Timber.w(it, "Export all failed for %s", page.id)
                }
            }
            busy = false
            message = if (failed == 0) {
                "$done páginas exportadas em Imagens/TradutorManga/$folder"
            } else {
                "$done exportadas, $failed com falha."
            }
        }
    }

    fun deleteCurrentPage(onEmpty: () -> Unit) {
        val current = item ?: return
        viewModelScope.launch {
            dirty = false
            repository.deleteItem(current.id)
            val remaining = pages.filterNot { it.id == current.id }
            if (remaining.isEmpty()) {
                item = null
                onEmpty()
            } else {
                val next = remaining.getOrNull(pageIndex.coerceAtMost(remaining.lastIndex))!!
                pages = remaining
                load(next)
            }
        }
    }

    private suspend fun projectFolderName(entity: GalleryTranslationItemEntity): String {
        val createdAt = repository.getTask(entity.taskId)?.createdAtMs ?: entity.updatedAtMs
        val created = runCatching { SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date(createdAt)) }
            .getOrDefault(entity.taskId.take(8))
        return "Leitura_$created"
    }

    private suspend fun exportItem(entity: GalleryTranslationItemEntity, folder: String) {
        val renderSettings = requireNotNull(settings) { "settings" }
        withContext(Dispatchers.IO) {
            val decoded = imageDecoder.decode(entity, maxDimension = EXPORT_MAX_DIMENSION)
            var output: Bitmap? = null
            try {
                val list = json.decodeFromString<List<GalleryTranslationSegment>>(entity.segmentsJson)
                    .filter { it.translatedText.isNotBlank() }
                output = renderer.render(
                    source = decoded.bitmap,
                    processedWidth = entity.processedWidth,
                    processedHeight = entity.processedHeight,
                    segments = list,
                    settings = renderSettings,
                )
                val name = String.format(Locale.ROOT, "pagina_%03d.png", entity.position + 1)
                savePng(output, folder, name)
            } finally {
                output?.takeIf { it !== decoded.bitmap && !it.isRecycled }?.recycle()
                decoded.bitmap.takeIf { !it.isRecycled }?.recycle()
            }
        }
    }

    private fun savePng(bitmap: Bitmap, folder: String, fileName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/TradutorManga/$folder",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = requireNotNull(
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ) { "MediaStore insert failed" }
            try {
                resolver.openOutputStream(uri)!!.use { out ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "png encode failed" }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
        } else {
            val dir = java.io.File(
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "TradutorManga/$folder",
            ).apply { mkdirs() }
            java.io.File(dir, fileName).outputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "png encode failed" }
            }
        }
    }

    private companion object {
        const val RENDER_DEBOUNCE_MS = 350L
        const val AUTOSAVE_DELAY_MS = 700L
        const val EXPORT_MAX_DIMENSION = 4096
    }
}

@Composable
private fun PageEditorScreen(viewModel: PageEditorViewModel, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val vm = viewModel
    var confirmDelete by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onClose) { Text("‹ Voltar") }
            Text(
                text = if (vm.pageCount > 0) "Página ${vm.pageIndex + 1}/${vm.pageCount}" else "—",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { vm.goToPage(-1) }, enabled = vm.pageIndex > 0) { Text("‹") }
            OutlinedButton(
                onClick = { vm.goToPage(1) },
                enabled = vm.pageIndex < vm.pageCount - 1,
            ) { Text("›") }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(onClick = { vm.zoom = max(1f, vm.zoom - 0.5f); if (vm.zoom == 1f) vm.pan = Offset.Zero }) { Text("Zoom −") }
            OutlinedButton(onClick = { vm.zoom = min(5f, vm.zoom + 0.5f) }) { Text("Zoom +") }
            OutlinedButton(onClick = { vm.addBubble() }) { Text("+ Balão") }
            FilterChip(
                selected = vm.showOriginal,
                onClick = { vm.showOriginal = !vm.showOriginal },
                label = { Text("Ver original") },
            )
            Button(onClick = { vm.exportCurrentPage() }, enabled = !vm.busy) { Text("Exportar página") }
            Button(onClick = { vm.exportAllPages() }, enabled = !vm.busy) { Text("Exportar todas") }
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Excluir página") }
        }
        vm.message?.let { text ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.message = null }) { Text("OK") }
            }
        }
        if (vm.busy) Text("Processando…", style = MaterialTheme.typography.bodySmall)
        EditorCanvas(vm = vm, modifier = Modifier.weight(1f).fillMaxWidth())
        HorizontalDivider()
        SelectionPanel(vm = vm)
    }
    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Excluir esta página?") },
            text = { Text("A página e a tradução salva serão removidas do projeto.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteCurrentPage(onEmpty = onClose)
                }) { Text("Excluir") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun EditorCanvas(vm: PageEditorViewModel, modifier: Modifier) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var dragMode by remember { mutableStateOf(DragMode.NONE) }
    val handleRadiusPx = 44f

    // Transformação coordenadas do segmento -> tela.
    fun fit(): Float {
        val w = vm.coordWidth.toFloat()
        val h = vm.coordHeight.toFloat()
        if (viewSize.width == 0 || viewSize.height == 0) return 1f
        return min(viewSize.width / w, viewSize.height / h)
    }

    fun scale(): Float = fit() * vm.zoom

    fun origin(): Offset {
        val s = scale()
        return Offset(
            (viewSize.width - vm.coordWidth * s) / 2f + vm.pan.x,
            (viewSize.height - vm.coordHeight * s) / 2f + vm.pan.y,
        )
    }

    fun toView(p: Offset): Offset = origin() + p * scale()

    fun toCoord(p: Offset): Offset = (p - origin()) / scale()

    fun boxOnView(box: GalleryRect): androidx.compose.ui.geometry.Rect {
        val a = toView(Offset(box.left.toFloat(), box.top.toFloat()))
        val b = toView(Offset(box.right.toFloat(), box.bottom.toFloat()))
        return androidx.compose.ui.geometry.Rect(a, b)
    }

    Canvas(
        modifier = modifier
            .background(Color(0xFFEFEFEF))
            .onSizeChanged { viewSize = it }
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    val point = toCoord(tap)
                    val hit = vm.segments.withIndex()
                        .filter { (_, seg) ->
                            val b = vm.effectiveBox(seg)
                            point.x >= b.left && point.x <= b.right && point.y >= b.top && point.y <= b.bottom
                        }
                        .minByOrNull { (_, seg) ->
                            val b = vm.effectiveBox(seg)
                            (b.right - b.left).toLong() * (b.bottom - b.top)
                        }
                    vm.select(hit?.index ?: -1)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start ->
                        val seg = vm.segments.getOrNull(vm.selected)
                        dragMode = DragMode.PAN
                        if (seg != null) {
                            val r = boxOnView(vm.effectiveBox(seg))
                            fun near(c: Offset) = (c - start).getDistance() <= handleRadiusPx
                            dragMode = when {
                                near(r.topLeft) -> DragMode.RESIZE_TL
                                near(r.topRight) -> DragMode.RESIZE_TR
                                near(r.bottomLeft) -> DragMode.RESIZE_BL
                                near(r.bottomRight) -> DragMode.RESIZE_BR
                                start.x in r.left..r.right && start.y in r.top..r.bottom -> DragMode.MOVE
                                else -> DragMode.PAN
                            }
                        }
                    },
                    onDragEnd = { dragMode = DragMode.NONE },
                    onDragCancel = { dragMode = DragMode.NONE },
                    onDrag = { change, amount ->
                        change.consume()
                        val s = scale()
                        if (dragMode == DragMode.PAN) {
                            if (vm.zoom > 1f) vm.pan += amount
                            return@detectDragGestures
                        }
                        val seg = vm.segments.getOrNull(vm.selected) ?: return@detectDragGestures
                        val box = vm.effectiveBox(seg)
                        val dx = (amount.x / s).roundToInt()
                        val dy = (amount.y / s).roundToInt()
                        var l = box.left
                        var t = box.top
                        var r = box.right
                        var b = box.bottom
                        when (dragMode) {
                            DragMode.MOVE -> { l += dx; r += dx; t += dy; b += dy }
                            DragMode.RESIZE_TL -> { l += dx; t += dy }
                            DragMode.RESIZE_TR -> { r += dx; t += dy }
                            DragMode.RESIZE_BL -> { l += dx; b += dy }
                            DragMode.RESIZE_BR -> { r += dx; b += dy }
                            else -> Unit
                        }
                        val minSize = 16
                        if (dragMode == DragMode.MOVE) {
                            val w = r - l
                            val h = b - t
                            l = l.coerceIn(0, max(0, vm.coordWidth - w))
                            t = t.coerceIn(0, max(0, vm.coordHeight - h))
                            r = l + w
                            b = t + h
                        } else {
                            l = l.coerceIn(0, vm.coordWidth - minSize)
                            t = t.coerceIn(0, vm.coordHeight - minSize)
                            r = r.coerceIn(l + minSize, vm.coordWidth)
                            b = b.coerceIn(t + minSize, vm.coordHeight)
                        }
                        vm.setBox(GalleryRect(l, t, r, b))
                    },
                )
            },
    ) {
        val bitmap = if (vm.showOriginal) vm.originalBitmap else (vm.rendered ?: vm.originalBitmap)
        if (bitmap != null) {
            val s = scale()
            val o = origin()
            drawImage(
                image = bitmap.asImageBitmap(),
                dstOffset = IntOffset(o.x.roundToInt(), o.y.roundToInt()),
                dstSize = IntSize(
                    (vm.coordWidth * s).roundToInt().coerceAtLeast(1),
                    (vm.coordHeight * s).roundToInt().coerceAtLeast(1),
                ),
            )
        }
        vm.segments.forEachIndexed { index, seg ->
            val r = boxOnView(vm.effectiveBox(seg))
            val isSelected = index == vm.selected
            drawRect(
                color = if (isSelected) Color(0xFFD50000) else Color(0x99000000),
                topLeft = r.topLeft,
                size = Size(r.width, r.height),
                style = Stroke(
                    width = if (isSelected) 4f else 1.5f,
                    pathEffect = if (isSelected) null else PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                ),
            )
            if (isSelected) {
                listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight).forEach { c ->
                    drawCircle(color = Color(0xFFD50000), radius = 14f, center = c)
                    drawCircle(color = Color.White, radius = 7f, center = c)
                }
            }
        }
    }
}

@Composable
private fun SelectionPanel(vm: PageEditorViewModel) {
    val index = vm.selected
    val seg = vm.segments.getOrNull(index)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (seg == null) {
            Text(
                "Toque num balão (contorno tracejado) para editar. Arraste os cantos para mudar a caixa.",
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }
        if (seg.sourceText.isNotBlank()) {
            Text(
                "Original: ${seg.sourceText}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )
        }
        OutlinedTextField(
            value = seg.translatedText,
            onValueChange = { text -> vm.updateSelected { it.copy(translatedText = text) } },
            label = { Text("Tradução") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Fonte ×${"%.1f".format(Locale.ROOT, seg.fontScale)}", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = seg.fontScale,
                onValueChange = { v -> vm.updateSelected { it.copy(fontScale = (v * 10).roundToInt() / 10f) } },
                valueRange = 0.4f..2.0f,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = seg.keepOriginal,
                onClick = { vm.updateSelected { it.copy(keepOriginal = !it.keepOriginal) } },
                label = { Text("Manter original") },
            )
            OutlinedButton(
                onClick = { vm.updateSelected { it.copy(renderBox = null) } },
                enabled = seg.renderBox != null,
            ) { Text("Caixa automática") }
            OutlinedButton(onClick = { vm.deleteSelected() }) { Text("Excluir balão") }
        }
    }
}
