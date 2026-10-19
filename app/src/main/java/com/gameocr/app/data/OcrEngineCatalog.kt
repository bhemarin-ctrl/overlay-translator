package com.gameocr.app.data

import androidx.annotation.StringRes
import com.gameocr.app.R

enum class OcrEngineGroup { ON_DEVICE, LOCAL, CLOUD }

data class OcrEngineOption(
    val engine: OcrEngineKind,
    @param:StringRes val labelRes: Int,
    val group: OcrEngineGroup,
)

/** Shared names and display order for manual OCR and automatic language routing. */
object OcrEngineCatalog {
    // Tradutor Manga: único motor de OCR é o Manga OCR (japonês).
    val options = listOf(
        OcrEngineOption(OcrEngineKind.MANGA_OCR_JA, R.string.settings_ocr_chip_manga_ocr_ja, OcrEngineGroup.ON_DEVICE),
    )

    fun option(engine: OcrEngineKind): OcrEngineOption = options.firstOrNull { it.engine == engine } ?: options.first()

    fun optionsIn(group: OcrEngineGroup): List<OcrEngineOption> = options.filter { it.group == group }

    // Automatic routing cannot select itself. Every other outer option stays visible,
    // including missing models; language compatibility only affects selection/runtime.
    fun automaticRoutes(): List<AutoOcrRoute> = options
        .filterNot { it.engine == OcrEngineKind.ML_KIT_AUTO }
        .flatMap { option ->
            if (option.engine == OcrEngineKind.PADDLE_ONNX) {
                PaddleModelVersion.entries.map { AutoOcrRoute(option.engine, it) }
            } else listOf(AutoOcrRoute(option.engine))
        }
}
