package com.novel.reader.util

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.novel.reader.model.BookMetadata
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * 解析小說的 book.yaml / book.yml 取得完整書籍資訊（書名、副標題、作者、系列、時代、簡介、標籤），
 * 並提供檔案名備援處理。
 */
object BookMetadataParser {

    private val PREFERRED_SECTIONS = setOf("project", "book", "metadata", "novel")

    /**
     * 解析 book.yaml 完整書籍資訊。
     * 不會誤抓 scale.volumes 底下的分卷 name。
     */
    fun parseMetadata(yamlContent: String): BookMetadata {
        if (yamlContent.isBlank()) return BookMetadata()

        var currentTopSection: String? = null
        var inTagsList = false
        var tagsKeyIndent = -1

        var sectionTitle: String? = null
        var sectionName: String? = null
        var topLevelTitle: String? = null
        var topLevelName: String? = null

        var subtitle = ""
        var author = ""
        var series = ""
        var era = ""
        var description = ""
        val tags = mutableListOf<String>()

        for (rawLine in yamlContent.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val indent = rawLine.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)

            // 處理 tags YAML list 項目（例如：  tags:\n    - 元末）
            if (inTagsList) {
                if (indent > tagsKeyIndent && trimmed.startsWith("-")) {
                    val tagVal = cleanYamlScalar(trimmed.removePrefix("-"))
                    if (tagVal.isNotEmpty()) tags.add(tagVal)
                    continue
                } else if (indent <= tagsKeyIndent || !trimmed.startsWith("-")) {
                    inTagsList = false
                }
            }

            if (indent == 0) {
                val colonIdx = trimmed.indexOf(':')
                if (colonIdx <= 0) {
                    currentTopSection = null
                    continue
                }
                val key = trimmed.substring(0, colonIdx).trim().lowercase()
                val rawVal = trimmed.substring(colonIdx + 1)
                val valuePart = cleanYamlScalar(rawVal)

                if (valuePart.isEmpty()) {
                    if (key == "tags") {
                        inTagsList = true
                        tagsKeyIndent = indent
                    } else {
                        currentTopSection = key
                    }
                } else {
                    currentTopSection = null
                    when (key) {
                        "title" -> if (topLevelTitle == null) topLevelTitle = valuePart
                        "name" -> if (topLevelName == null) topLevelName = valuePart
                        "subtitle" -> if (subtitle.isEmpty()) subtitle = valuePart
                        "author" -> if (author.isEmpty()) author = valuePart
                        "series" -> if (series.isEmpty()) series = valuePart
                        "era", "status" -> if (era.isEmpty()) era = valuePart
                        "description", "summary", "intro" -> if (description.isEmpty()) description = valuePart
                        "tags" -> parseInlineTags(valuePart, tags)
                    }
                }
            } else if (currentTopSection in PREFERRED_SECTIONS) {
                if (trimmed.startsWith("-")) continue
                val colonIdx = trimmed.indexOf(':')
                if (colonIdx <= 0) continue
                val key = trimmed.substring(0, colonIdx).trim().lowercase()
                val rawVal = trimmed.substring(colonIdx + 1)
                val valuePart = cleanYamlScalar(rawVal)

                if (key == "tags") {
                    if (valuePart.isEmpty()) {
                        inTagsList = true
                        tagsKeyIndent = indent
                    } else {
                        parseInlineTags(valuePart, tags)
                    }
                    continue
                }

                if (valuePart.isNotEmpty()) {
                    when (key) {
                        "title" -> if (sectionTitle == null) sectionTitle = valuePart
                        "name" -> if (sectionName == null) sectionName = valuePart
                        "subtitle" -> if (subtitle.isEmpty()) subtitle = valuePart
                        "author" -> if (author.isEmpty()) author = valuePart
                        "series" -> if (series.isEmpty()) series = valuePart
                        "era" -> era = valuePart
                        "status" -> if (era.isEmpty()) era = valuePart
                        "description", "summary", "intro" -> if (description.isEmpty()) description = valuePart
                    }
                }
            }
        }

        val resolvedTitle = sectionTitle ?: sectionName ?: topLevelTitle ?: topLevelName ?: ""
        return BookMetadata(
            title = resolvedTitle,
            subtitle = subtitle,
            author = author,
            series = series,
            era = era,
            description = description,
            tags = tags.distinct()
        )
    }

    private fun parseInlineTags(value: String, target: MutableList<String>) {
        val inner = value.removePrefix("[").removeSuffix("]")
        inner.split(',', '、').forEach { part ->
            val cleaned = cleanYamlScalar(part)
            if (cleaned.isNotEmpty()) target.add(cleaned)
        }
    }

    /**
     * 從 book.yaml 內容中解析書名。
     */
    fun parseTitle(yamlContent: String): String? {
        return parseMetadata(yamlContent).title.takeIf { it.isNotBlank() }
    }

    /**
     * 在本機目錄中尋找並解析 book.yaml / book.yml 的完整書籍資訊
     */
    fun findMetadataInDirectory(dir: File): BookMetadata? {
        val yamlFile = findBookYamlFile(dir, maxDepth = 2) ?: return null
        return try {
            val meta = parseMetadata(yamlFile.readText(Charsets.UTF_8))
            if (meta.title.isNotBlank() || meta.subtitle.isNotBlank() || meta.tags.isNotEmpty()) meta else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 在本機目錄中尋找 book.yaml / book.yml（最深搜尋 2 層，以相容 zip 外層多包一層資料夾的情況）
     */
    fun findTitleInDirectory(dir: File): String? {
        return findMetadataInDirectory(dir)?.title?.takeIf { it.isNotBlank() }
    }

    fun findBookYamlFile(dir: File, maxDepth: Int = 2): File? {
        if (!dir.exists() || !dir.isDirectory) return null

        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(dir to 0)

        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()
            val files = current.listFiles() ?: continue

            val directYaml = files.firstOrNull {
                it.isFile && (it.name.equals("book.yaml", ignoreCase = true) ||
                        it.name.equals("book.yml", ignoreCase = true))
            }
            if (directYaml != null) return directYaml

            if (depth < maxDepth) {
                files.filter { it.isDirectory && !it.name.startsWith(".") }
                    .sortedBy { it.name }
                    .forEach { sub -> queue.add(sub to (depth + 1)) }
            }
        }
        return null
    }

    /**
     * 在 SAF DocumentFile 目錄中尋找並解析 book.yaml / book.yml
     */
    fun findMetadataInDocumentDir(rootDoc: DocumentFile, context: Context): BookMetadata? {
        val yamlDoc = findYamlInDoc(rootDoc, depth = 0, maxDepth = 1) ?: return null
        return try {
            context.contentResolver.openInputStream(yamlDoc.uri)?.use { stream ->
                val content = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
                parseMetadata(content)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun findTitleInDocumentDir(rootDoc: DocumentFile, context: Context): String? {
        return findMetadataInDocumentDir(rootDoc, context)?.title?.takeIf { it.isNotBlank() }
    }

    private fun findYamlInDoc(dir: DocumentFile, depth: Int, maxDepth: Int): DocumentFile? {
        val children = dir.listFiles()
        val direct = children.firstOrNull {
            it.isFile && (it.name.equals("book.yaml", ignoreCase = true) ||
                    it.name.equals("book.yml", ignoreCase = true))
        }
        if (direct != null) return direct
        if (depth >= maxDepth) return null
        for (child in children) {
            if (child.isDirectory) {
                val found = findYamlInDoc(child, depth + 1, maxDepth)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * 去除壓縮檔或文字檔副檔名作為備援書名
     */
    fun stripFileExtension(fileName: String): String {
        val base = fileName.substringAfterLast("/").substringAfterLast("\\").trim()
        val lower = base.lowercase()
        val stripped = when {
            lower.endsWith(".tar.gz") -> base.dropLast(7)
            lower.endsWith(".tgz") -> base.dropLast(4)
            lower.endsWith(".zip") -> base.dropLast(4)
            lower.endsWith(".tar") -> base.dropLast(4)
            lower.endsWith(".md") -> base.dropLast(3)
            lower.endsWith(".txt") -> base.dropLast(4)
            base.contains(".") -> base.substringBeforeLast(".")
            else -> base
        }.trim()
        return stripped.ifEmpty { base.ifEmpty { "未命名小說" } }
    }

    fun isBookYamlFileName(pathOrName: String): Boolean {
        val name = pathOrName.substringAfterLast("/").substringAfterLast("\\")
        return name.equals("book.yaml", ignoreCase = true) || name.equals("book.yml", ignoreCase = true)
    }

    private fun cleanYamlScalar(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        if (trimmed.startsWith("\"")) {
            val endQuote = trimmed.indexOf('"', startIndex = 1)
            return if (endQuote > 0) trimmed.substring(1, endQuote).trim() else trimmed.trim('"').trim()
        }
        if (trimmed.startsWith("'")) {
            val endQuote = trimmed.indexOf('\'', startIndex = 1)
            return if (endQuote > 0) trimmed.substring(1, endQuote).trim() else trimmed.trim('\'').trim()
        }
        val commentIdx = trimmed.indexOf(" #")
        val withoutComment = if (commentIdx != -1) trimmed.substring(0, commentIdx) else trimmed
        return withoutComment.trim()
    }
}
