package com.novel.reader.util

import com.novel.reader.model.BookMetadata
import com.novel.reader.model.Chapter
import com.novel.reader.model.ChapterContent
import java.io.File

data class StoredWebBook(
    val title: String,
    val sourceUrl: String,
    val preferWebView: Boolean,
    val chapters: List<Chapter>,
    val metadata: BookMetadata = BookMetadata(title = title)
)

/**
 * 網路小說的本機目錄與章節快取儲存工具（含完整書籍資訊與逐章快取）
 */
object WebBookStore {

    private const val META_FILE = "web_meta.txt"
    private const val TOC_FILE = "web_toc.txt"
    private const val CACHE_DIR_NAME = "cache"
    private const val SEP = "\u001F"

    fun generateBookId(sourceUrl: String): String {
        val normalized = sourceUrl.trim().lowercase().trimEnd('/')
        val host = WebAdBlocker.extractHost(normalized)?.replace(Regex("""[^a-z0-9]"""), "_") ?: "web"
        val hash = normalized.hashCode().toUInt().toString(16)
        return "web_${host}_$hash"
    }

    fun getCachedChapterFile(bookDir: File, chapterNumber: Int): File {
        val cacheDir = File(bookDir, CACHE_DIR_NAME)
        return File(cacheDir, "ch_%05d.txt".format(chapterNumber))
    }

    fun saveBook(
        bookDir: File,
        title: String,
        sourceUrl: String,
        preferWebView: Boolean,
        chapters: List<Chapter>,
        metadata: BookMetadata = BookMetadata(title = title)
    ) {
        bookDir.mkdirs()
        File(bookDir, CACHE_DIR_NAME).mkdirs()

        val metaContent = buildString {
            appendLine("title=${sanitizeLine(title)}")
            appendLine("sourceUrl=${sanitizeLine(sourceUrl)}")
            appendLine("preferWebView=$preferWebView")
            appendLine("subtitle=${sanitizeLine(metadata.subtitle)}")
            appendLine("author=${sanitizeLine(metadata.author)}")
            appendLine("series=${sanitizeLine(metadata.series)}")
            appendLine("era=${sanitizeLine(metadata.era)}")
            appendLine("description=${sanitizeLine(metadata.description)}")
            appendLine("tags=${metadata.tags.joinToString(",") { sanitizeLine(it).replace(",", " ") }}")
        }
        File(bookDir, META_FILE).writeText(metaContent, Charsets.UTF_8)

        val tocLines = chapters.joinToString("\n") { ch ->
            listOf(
                ch.chapterNumber.toString(),
                ch.volumeIndex.toString(),
                sanitizeField(ch.volumeName),
                sanitizeField(ch.title),
                sanitizeField(ch.remoteUrl ?: "")
            ).joinToString(SEP)
        }
        File(bookDir, TOC_FILE).writeText(tocLines, Charsets.UTF_8)
    }

    fun loadBook(bookDir: File): StoredWebBook? {
        val metaFile = File(bookDir, META_FILE)
        val tocFile = File(bookDir, TOC_FILE)
        if (!metaFile.exists() || !tocFile.exists()) return null

        var title = ""
        var sourceUrl = ""
        var preferWebView = false
        var subtitle = ""
        var author = ""
        var series = ""
        var era = ""
        var description = ""
        var tags: List<String> = emptyList()

        metaFile.readLines(Charsets.UTF_8).forEach { line ->
            when {
                line.startsWith("title=") -> title = line.removePrefix("title=").trim()
                line.startsWith("sourceUrl=") -> sourceUrl = line.removePrefix("sourceUrl=").trim()
                line.startsWith("preferWebView=") -> preferWebView = line.removePrefix("preferWebView=").trim().toBoolean()
                line.startsWith("subtitle=") -> subtitle = line.removePrefix("subtitle=").trim()
                line.startsWith("author=") -> author = line.removePrefix("author=").trim()
                line.startsWith("series=") -> series = line.removePrefix("series=").trim()
                line.startsWith("era=") -> era = line.removePrefix("era=").trim()
                line.startsWith("description=") -> description = line.removePrefix("description=").trim()
                line.startsWith("tags=") -> {
                    val raw = line.removePrefix("tags=").trim()
                    if (raw.isNotEmpty()) {
                        tags = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    }
                }
            }
        }

        val chapters = mutableListOf<Chapter>()
        tocFile.readLines(Charsets.UTF_8).forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split(SEP)
            if (parts.size < 5) return@forEach
            val chapNum = parts[0].toIntOrNull() ?: return@forEach
            val volIdx = parts[1].toIntOrNull() ?: 1
            val volName = parts[2].ifBlank { "正文" }
            val chapTitle = parts[3].ifBlank { "第 $chapNum 章" }
            val remoteUrl = parts[4].ifBlank { null }
            val cacheFile = getCachedChapterFile(bookDir, chapNum)
            chapters.add(
                Chapter(
                    id = remoteUrl ?: cacheFile.absolutePath,
                    volumeIndex = volIdx,
                    volumeName = volName,
                    chapterNumber = chapNum,
                    title = chapTitle,
                    fileUriString = if (cacheFile.exists()) "file://${cacheFile.absolutePath}" else null,
                    remoteUrl = remoteUrl
                )
            )
        }

        if (chapters.isEmpty()) return null
        val resolvedTitle = title.ifBlank { "網路小說" }
        return StoredWebBook(
            title = resolvedTitle,
            sourceUrl = sourceUrl,
            preferWebView = preferWebView,
            chapters = chapters,
            metadata = BookMetadata(
                title = resolvedTitle,
                subtitle = subtitle,
                author = author,
                series = series,
                era = era,
                description = description,
                tags = tags
            )
        )
    }

    fun saveCachedChapter(cacheFile: File, content: ChapterContent) {
        cacheFile.parentFile?.mkdirs()
        val text = buildString {
            appendLine("# ${sanitizeLine(content.title)}")
            appendLine()
            content.bodyParagraphs.forEach { para ->
                appendLine(para)
                appendLine()
            }
        }
        cacheFile.writeText(text, Charsets.UTF_8)
    }

    fun readCachedChapter(cacheFile: File): ChapterContent? {
        if (!cacheFile.exists() || cacheFile.length() == 0L) return null
        return try {
            val raw = cacheFile.readText(Charsets.UTF_8)
            val lines = raw.lines()
            var title = ""
            val body = mutableListOf<String>()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                if (title.isEmpty() && trimmed.startsWith("# ")) {
                    title = trimmed.removePrefix("# ").trim()
                } else {
                    body.add(trimmed)
                }
            }
            if (body.isEmpty()) null else ChapterContent(
                title = title.ifBlank { "章節" },
                bodyParagraphs = body,
                workRecords = emptyList(),
                rawMarkdown = raw
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizeLine(value: String): String = value.replace("\n", " ").replace("\r", " ").trim()
    private fun sanitizeField(value: String): String = sanitizeLine(value).replace(SEP, " ")
}
