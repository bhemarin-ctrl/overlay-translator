package com.gameocr.app.gallery

import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.zip.ZipFile

/** Extrai as imagens de um EPUB de mangá na ordem de leitura (spine do OPF). */
internal object EpubImageExtractor {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    fun isEpub(displayName: String, mime: String?): Boolean =
        displayName.endsWith(".epub", ignoreCase = true) ||
            mime.equals("application/epub+zip", ignoreCase = true)

    /** Devolve os arquivos extraídos em [outDir], já numerados na ordem de leitura. */
    fun extract(epub: File, outDir: File): List<File> {
        outDir.mkdirs()
        ZipFile(epub).use { zip ->
            val ordered = orderedImagePaths(zip)
            val result = ArrayList<File>()
            ordered.forEachIndexed { index, path ->
                val entry = zip.getEntry(path) ?: return@forEachIndexed
                val ext = path.substringAfterLast('.', "jpg").lowercase()
                val target = File(outDir, "%04d.%s".format(index + 1, ext))
                zip.getInputStream(entry).use { input -> FileOutputStream(target).use(input::copyTo) }
                result.add(target)
            }
            return result
        }
    }

    private fun orderedImagePaths(zip: ZipFile): List<String> {
        val allImages = zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in imageExtensions }
            .map { it.name }
            .toList()
        val fromSpine = runCatching { imagesFromSpine(zip) }.getOrDefault(emptyList())
            .filter { path -> allImages.contains(path) }
            .distinct()
        // Imagens que o spine não referencia (ex.: páginas só em CSS) entram no fim, em ordem de nome.
        val rest = allImages.filterNot { it in fromSpine }.sortedWith(naturalOrder())
        return if (fromSpine.isEmpty()) rest else fromSpine + rest.filter { isLikelyPage(it) }
    }

    private fun isLikelyPage(path: String): Boolean {
        val lower = path.lowercase()
        return !lower.contains("cover") && !lower.contains("thumb") && !lower.contains("icon")
    }

    private fun naturalOrder(): Comparator<String> = Comparator { a, b ->
        val ra = Regex("\\d+|\\D+").findAll(a).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]
            val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                x.toBigInteger().compareTo(y.toBigInteger())
            } else {
                x.compareTo(y)
            }
            if (c != 0) return@Comparator c
        }
        ra.size - rb.size
    }

    private fun readText(zip: ZipFile, path: String): String? =
        zip.getEntry(path)?.let { entry -> zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) } }

    private fun imagesFromSpine(zip: ZipFile): List<String> {
        val container = readText(zip, "META-INF/container.xml") ?: return emptyList()
        val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1) ?: return emptyList()
        val opf = readText(zip, opfPath) ?: return emptyList()
        val opfDir = opfPath.substringBeforeLast('/', "")

        val manifest = HashMap<String, String>()
        Regex("<item\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf).forEach { m ->
            val tag = m.value
            val id = attr(tag, "id") ?: return@forEach
            val href = attr(tag, "href") ?: return@forEach
            manifest[id] = href
        }
        val spineIds = Regex("<itemref\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf)
            .mapNotNull { attr(it.value, "idref") }
            .toList()

        val out = ArrayList<String>()
        spineIds.forEach { id ->
            val href = manifest[id] ?: return@forEach
            val pagePath = resolve(opfDir, href)
            val ext = pagePath.substringAfterLast('.', "").lowercase()
            if (ext in imageExtensions) {
                out.add(pagePath)
                return@forEach
            }
            val html = readText(zip, pagePath) ?: return@forEach
            val pageDir = pagePath.substringBeforeLast('/', "")
            Regex("<(?:img|image)\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
                val src = attr(m.value, "src") ?: attr(m.value, "xlink:href") ?: attr(m.value, "href")
                if (src != null && !src.startsWith("data:")) out.add(resolve(pageDir, src))
            }
        }
        return out
    }

    private fun attr(tag: String, name: String): String? =
        Regex("\\b${Regex.escape(name)}\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
            .find(tag)?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }

    private fun resolve(baseDir: String, href: String): String {
        val clean = URLDecoder.decode(href.substringBefore('#').substringBefore('?'), "UTF-8")
        val parts = ArrayList<String>()
        val joined = if (clean.startsWith("/")) clean.removePrefix("/") else if (baseDir.isEmpty()) clean else "$baseDir/$clean"
        joined.split('/').forEach { seg ->
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }
}
