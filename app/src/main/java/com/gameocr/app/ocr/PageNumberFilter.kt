package com.gameocr.app.ocr

import android.graphics.Rect

/** Número de página (só dígitos/traços na margem superior ou inferior) nunca deve ser traduzido. */
object PageNumberFilter {
    private val pattern = Regex("^[\\s\\-–—−・.·\\[\\](){}（）<>〈〉/|0-9０-９]{1,10}$")

    fun isPageNumber(text: String, box: Rect, pageHeight: Int): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.none { it.isDigit() }) return false
        if (!pattern.matches(trimmed)) return false
        if (pageHeight <= 0) return false
        val centerY = box.centerY().toFloat() / pageHeight
        return centerY < 0.10f || centerY > 0.90f
    }
}
