package com.novel.reader.util

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * 解析小說的 book.yaml / book.yml 取得書名，並提供檔案名備援處理。
 */
object BookMetadataParser {

    private val PREFERRED_SECTIONS = setOf("project", "book", "metadata", "novel")

    /**
     * 從 book.yaml 內容中解析書名。
     * 優先順序：
     * 1. project/book/metadata/novel 區塊底下的 title 或 name
     * 2. 頂層的 title 或 name
     * 注意：不會誤抓 scale.volumes 底下的分卷 name。
     */
    fun parseTitle(yamlContent: String): String? {
        if (yamlContent.isBlank()) return null

        var currentTopSection: String? = null
        var sectionTitle: String? = null
        var sectionName: String? = null
        var topLevelTitle: String? = null
        var topLevelName: String? = null

        for (rawLine in yamlContent.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val indent = rawLine.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)

            if (indent == 0) {
                // 頂層 key
                val colonIdx = trimmed.indexOf(':')
                if (colonIdx <= 0) {
                    currentTopSection = null
                    continue
                }
                val key = trimmed.substring(0, colonIdx).trim().lowercase()
                val valuePart = cleanYamlScalar(trimmed.substring(colonIdx + 1))

                if (valuePart.isEmpty()) {
                    currentTopSection = key
                } else {
                    currentTopSection = null
                    when (key) {
                        "title" -> if (topLevelTitle == null) topLevelTitle = valuePart
                        "name" -> if (topLevelName == null) topLevelName = valuePart
                    }
                }
            } else if (currentTopSection in PREFERRED_SECTIONS) {
                // 只讀取第二層直屬欄位（排除 list item 或更深層巢狀）
                if (trimmed.startsWith("-")) continue
                val colonIdx = trimmed.indexOf(':')
                if (colonIdx <= 0) continue
                val key = trimmed.substring(0, colonIdx).trim().lowercase()
                val valuePart = cleanYamlScalar(trimmed.substring(colonIdx + 1))
                if (valuePart.isNotEmpty()) {
                    when (key) {
                        "title" -> if (sectionTitle == null) sectionTitle = valuePart
                        "name" -> if (sectionName == null) sectionName = valuePart
                    }
                }
            }
        }

        return sectionTitle ?: sectionName ?: topLevelTitle ?: topLevelName
    }

    /**
     * 在本機目錄中尋找 book.yaml / book.yml（最深搜尋 2 層，以相容 zip 外層多包一層資料夾的情況）
     */
    fun findTitleInDirectory(dir: File): String? {
        val yamlFile = findBookYamlFile(dir, maxDepth = 2) ?: return null
        return try {
            parseTitle(yamlFile.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    fun findBookYamlFile(dir: File, maxDepth: Int = 2): File? {
        if (!dir.exists() || !dir.isDirectory) return null

        // 使用 BFS 確保優先找到最淺層的 book.yaml
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
     * 在 SAF DocumentFile 目錄中尋找 book.yaml / book.yml
     */
    fun findTitleInDocumentDir(rootDoc: DocumentFile, context: Context): String? {
        val yamlDoc = findYamlInDoc(rootDoc, depth = 0, maxDepth = 1) ?: return null
        return try {
            context.contentResolver.openInputStream(yamlDoc.uri)?.use { stream ->
                val content = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
                parseTitle(content)
            }
        } catch (_: Exception) {
            null
        }
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

        // 處理雙引號字串
        if (trimmed.startsWith("\"")) {
            val endQuote = trimmed.indexOf('"', startIndex = 1)
            return if (endQuote > 0) trimmed.substring(1, endQuote).trim() else trimmed.trim('"').trim()
        }
        // 處理單引號字串
        if (trimmed.startsWith("'")) {
            val endQuote = trimmed.indexOf('\'', startIndex = 1)
            return if (endQuote > 0) trimmed.substring(1, endQuote).trim() else trimmed.trim('\'').trim()
        }
        // 未加引號時，移除行內註解 (# 前需有空白)
        val commentIdx = trimmed.indexOf(" #")
        val withoutComment = if (commentIdx != -1) trimmed.substring(0, commentIdx) else trimmed
        return withoutComment.trim()
    }
}
