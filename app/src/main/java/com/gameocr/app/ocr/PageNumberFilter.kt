package com.gameocr.app.ocr

import android.graphics.Rect

/** Número de página (só dígitos e símbolos, perto das bordas) nunca deve ser traduzido. */
object PageNumberFilter {
    fun isPageNumber(text: String, box: Rect, pageHeight: Int): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.none { it.isDigit() }) return false
        if (trimmed.count { it.isDigit() } > 5) return false
        // Só dígitos, espaços, pontuação, traços (inclui "ー" e "－") ou símbolos.
        val onlyNumberLike = trimmed.all { ch ->
            ch.isDigit() || ch.isWhitespace() || ch == 'ー' || ch == '－' || ch == '―' ||
                Character.getType(ch).let { type ->
                    type == Character.DASH_PUNCTUATION.toInt() ||
                        type == Character.OTHER_PUNCTUATION.toInt() ||
                        type == Character.START_PUNCTUATION.toInt() ||
                        type == Character.END_PUNCTUATION.toInt() ||
                        type == Character.MATH_SYMBOL.toInt() ||
                        type == Character.OTHER_SYMBOL.toInt() ||
                        type == Character.CONNECTOR_PUNCTUATION.toInt()
                }
        }
        if (!onlyNumberLike) return false
        if (pageHeight <= 0) return false
        val centerY = box.centerY().toFloat() / pageHeight
        // Margem superior/inferior; no leitor eletrônico a página pode ter faixas extras.
        return centerY < 0.16f || centerY > 0.84f
    }
}
