package com.novel.reader.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.novel.reader.model.BookEntry
import com.novel.reader.model.BookMetadata
import com.novel.reader.model.BookSourceType
import com.novel.reader.model.Chapter
import com.novel.reader.model.ChapterContent
import com.novel.reader.parser.NovelChapterParser
import com.novel.reader.util.ArchiveHelper
import com.novel.reader.util.BookMetadataParser
import com.novel.reader.util.ChapterFetchOutcome
import com.novel.reader.util.StoredWebBook
import com.novel.reader.util.WebBookStore
import com.novel.reader.util.WebNovelFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader

class NovelRepository(private val context: Context) {

    private val cachedChapters = mutableListOf<Chapter>()
    var currentNovelId: String = ""
        private set
    var currentNovelTitle: String = ""
        private set
    var currentNovelMetadata: BookMetadata = BookMetadata()
        private set
    var currentNovelLocalPath: String? = null
        private set

    /**
     * 掃描 assets/novels/ 下所有的內建小說壓縮檔（yuan.zip, song.zip, sex.zip, ming.zip 等），
     * 直接讀取每個 zip 內的 book.yaml 書籍資訊與章節數（不需預先解壓縮），供書單初始化使用。
     */
    suspend fun discoverBuiltinAssets(): List<BookEntry> = withContext(Dispatchers.IO) {
        val assetFiles = try {
            context.assets.list("novels")
                ?.filter { it.lowercase().endsWith(".zip") }
                ?.sortedWith(compareBy<String> { if (it.equals("yuan.zip", true)) 0 else 1 }.thenBy { it })
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        val now = System.currentTimeMillis()
        val entries = mutableListOf<BookEntry>()

        assetFiles.forEachIndexed { idx, fileName ->
            val assetPath = "novels/$fileName"
            val stem = BookMetadataParser.stripFileExtension(fileName)
            val bookId = "builtin_$stem"
            val summary = try {
                context.assets.open(assetPath).use { input ->
                    ArchiveHelper.inspectZipBookSummary(input, fallbackTitle = stem)
                }
            } catch (_: Exception) {
                null
            }

            if (summary != null && summary.chapterCount > 0) {
                val meta = summary.metadata
                val title = meta.title.ifBlank { stem }
                val targetDir = File(context.filesDir, "builtin_$stem")
                entries.add(
                    BookEntry(
                        id = bookId,
                        title = title,
                        sourceType = BookSourceType.BUILTIN_ASSET,
                        localPath = targetDir.absolutePath,
                        sourceUriOrUrl = assetPath,
                        chapterCount = summary.chapterCount,
                        addedAt = now - idx * 1000L,
                        lastOpenedAt = now - idx * 1000L,
                        subtitle = meta.subtitle,
                        author = meta.author,
                        series = meta.series,
                        era = meta.era,
                        description = meta.description,
                        tags = meta.tags
                    )
                )
            }
        }
        entries
    }

    /**
     * 載入內建資產壓縮檔（預設 novels/yuan.zip）。
     * 優先從解壓後的 book.yaml 讀取完整書籍資訊；缺失時以檔名（去除副檔名）作為書名。
     */
    suspend fun loadBuiltinNovelChapters(
        assetPath: String = "novels/yuan.zip",
        bookId: String = "builtin_${BookMetadataParser.stripFileExtension(assetPath)}"
    ): List<Chapter> = withContext(Dispatchers.IO) {
        val stem = BookMetadataParser.stripFileExtension(assetPath)
        val targetDir = File(context.filesDir, "builtin_$stem")
        val markerFile = File(targetDir, ".extracted_v3_ok")

        if (!markerFile.exists()) {
            targetDir.deleteRecursively()
            targetDir.mkdirs()
            try {
                context.assets.open(assetPath).use { input ->
                    ArchiveHelper.extractZip(input, targetDir)
                }
                markerFile.createNewFile()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val chapters = scanDirectoryForChapters(targetDir)
        val meta = BookMetadataParser.findMetadataInDirectory(targetDir) ?: BookMetadata()
        val resolvedTitle = meta.title.ifBlank { stem }

        cachedChapters.clear()
        cachedChapters.addAll(chapters)
        currentNovelId = bookId
        currentNovelTitle = resolvedTitle
        currentNovelMetadata = meta.copy(title = resolvedTitle)
        currentNovelLocalPath = targetDir.absolutePath
        chapters
    }

    /**
     * 從已儲存的本機實體目錄重新載入章節（用於書單切換已下載/已匯入的書籍）
     */
    suspend fun loadLocalDirectoryChapters(
        dir: File,
        bookId: String,
        fallbackTitle: String
    ): List<Chapter> = withContext(Dispatchers.IO) {
        if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()

        val chapters = scanDirectoryForChapters(dir)
        if (chapters.isNotEmpty()) {
            val meta = BookMetadataParser.findMetadataInDirectory(dir) ?: BookMetadata()
            val resolvedTitle = meta.title.ifBlank { fallbackTitle }
            cachedChapters.clear()
            cachedChapters.addAll(chapters)
            currentNovelId = bookId
            currentNovelTitle = resolvedTitle
            currentNovelMetadata = meta.copy(title = resolvedTitle)
            currentNovelLocalPath = dir.absolutePath
        }
        chapters
    }

    /**
     * 從外部選取的壓縮檔 (.zip, .tar, .tar.gz, .tgz) 匯入並載入章節。
     * 優先從 book.yaml 取得書籍資訊，缺 book.yaml 時使用壓縮檔名（去除副檔名）。
     */
    suspend fun loadArchiveChapters(archiveUri: Uri, fileName: String): List<Chapter> = withContext(Dispatchers.IO) {
        val safeName = fileName.replace(Regex("""[^a-zA-Z0-9_\u4e00-\u9fa5]"""), "_")
        val extractDir = File(context.filesDir, "imported_$safeName")
        extractDir.deleteRecursively()
        extractDir.mkdirs()

        val success = context.contentResolver.openInputStream(archiveUri)?.use { input ->
            ArchiveHelper.extractArchive(input, fileName, extractDir)
        } ?: false

        if (!success) {
            extractDir.deleteRecursively()
            return@withContext emptyList()
        }

        val chapters = scanDirectoryForChapters(extractDir)
        if (chapters.isNotEmpty()) {
            val meta = BookMetadataParser.findMetadataInDirectory(extractDir) ?: BookMetadata()
            val resolvedTitle = meta.title.ifBlank { BookMetadataParser.stripFileExtension(fileName) }
            cachedChapters.clear()
            cachedChapters.addAll(chapters)
            currentNovelId = "archive_$safeName"
            currentNovelTitle = resolvedTitle
            currentNovelMetadata = meta.copy(title = resolvedTitle)
            currentNovelLocalPath = extractDir.absolutePath
        } else {
            extractDir.deleteRecursively()
        }
        chapters
    }

    /**
     * 從外部選取的資料夾 (SAF Uri) 載入小說章節。
     * 優先從 book.yaml 取得書籍資訊，缺 book.yaml 時使用資料夾名稱。
     */
    suspend fun loadCustomDirectoryChapters(treeUri: Uri): List<Chapter> = withContext(Dispatchers.IO) {
        val chapters = mutableListOf<Chapter>()
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val chaptersFolder = rootDoc.findFile("chapters")
        val scanTarget = if (chaptersFolder != null && chaptersFolder.isDirectory) chaptersFolder else rootDoc

        fun scanFolder(docFile: DocumentFile, volIndex: Int, volName: String) {
            for (file in docFile.listFiles()) {
                if (file.isDirectory) {
                    val name = file.name ?: ""
                    val vIdx = if (name.contains("volume", ignoreCase = true) || name.startsWith("vol", ignoreCase = true) || name.matches(Regex("""^0*\d+$"""))) {
                        name.replace(Regex("""[^0-9]"""), "").toIntOrNull() ?: volIndex
                    } else volIndex
                    scanFolder(file, vIdx, "第 ${vIdx} 卷")
                } else if (file.isFile && (file.name?.endsWith(".md") == true || file.name?.endsWith(".txt") == true)) {
                    val name = file.name ?: ""
                    val lower = name.lowercase()
                    if (lower.contains("rules") || lower.contains("outline") || lower.contains("readme") ||
                        lower.contains("character") || lower.contains("timeline") || lower.contains("geography") ||
                        lower.contains("audit") || lower.contains("ledger") || lower.contains("bible") ||
                        lower.contains("social") || lower.contains("workflow") || lower.contains("progress") ||
                        lower.contains("generator") || lower.contains("book.yaml") ||
                        name.matches(Regex("""^volume\d+\.md$""", RegexOption.IGNORE_CASE))) {
                        continue
                    }
                    val num = Regex("""\d+""").find(name)?.value?.toIntOrNull() ?: 0
                    if (num <= 0) continue
                    val title = readTitleFromUri(file.uri) ?: BookMetadataParser.stripFileExtension(name)
                    chapters.add(
                        Chapter(
                            id = file.uri.toString(),
                            volumeIndex = volIndex,
                            volumeName = volName,
                            chapterNumber = num,
                            title = title,
                            fileUriString = file.uri.toString()
                        )
                    )
                }
            }
        }

        scanFolder(scanTarget, 1, "目錄")
        // 先依卷序再依章號排序（支援「各卷獨立計數」的章節編號）
        chapters.sortWith(compareBy<Chapter>({ it.volumeIndex }, { it.chapterNumber }))
        if (chapters.isNotEmpty()) {
            val meta = BookMetadataParser.findMetadataInDocumentDir(rootDoc, context) ?: BookMetadata()
            val resolvedTitle = meta.title.ifBlank { rootDoc.name ?: "外部小說目錄" }
            cachedChapters.clear()
            cachedChapters.addAll(chapters)
            currentNovelId = "folder_${treeUri.toString().hashCode()}"
            currentNovelTitle = resolvedTitle
            currentNovelMetadata = meta.copy(title = resolvedTitle)
            currentNovelLocalPath = null
        }
        chapters
    }

    /**
     * 掃描本機實體目錄下的所有章節 markdown / txt 檔案
     */
    private fun scanDirectoryForChapters(baseDir: File): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        val searchDir = if (File(baseDir, "chapters").isDirectory) File(baseDir, "chapters") else baseDir

        fun scan(file: File, volIndex: Int, volName: String) {
            if (file.isDirectory) {
                val name = file.name
                val vIdx = if (name.contains("volume", ignoreCase = true) || name.startsWith("vol", ignoreCase = true) || name.matches(Regex("""^0*\d+$"""))) {
                    name.replace(Regex("""[^0-9]"""), "").toIntOrNull() ?: volIndex
                } else volIndex
                val vName = when (vIdx) {
                    1 -> "第一卷"
                    2 -> "第二卷"
                    3 -> "第三卷"
                    4 -> "第四卷"
                    5 -> "第五卷"
                    6 -> "第六卷"
                    else -> "第 $vIdx 卷"
                }
                file.listFiles()?.forEach { scan(it, vIdx, vName) }
            } else if (file.isFile && (file.name.endsWith(".md") || file.name.endsWith(".txt"))) {
                val lower = file.name.lowercase()
                if (lower.contains("rules") || lower.contains("outline") || lower.contains("readme") ||
                    lower.contains("character") || lower.contains("timeline") || lower.contains("geography") ||
                    lower.contains("audit") || lower.contains("ledger") || lower.contains("bible") ||
                    lower.contains("social") || lower.contains("workflow") || lower.contains("progress") ||
                    lower.contains("generator") || lower.contains("book.yaml") ||
                    file.name.matches(Regex("""^volume\d+\.md$""", RegexOption.IGNORE_CASE))) {
                    return
                }
                val num = Regex("""\d+""").find(file.name)?.value?.toIntOrNull()
                    ?: if (baseDir.listFiles()?.count { it.isFile && (it.name.endsWith(".md") || it.name.endsWith(".txt")) } == 1) 1 else 0
                if (num <= 0) return
                val title = readTitleFromFile(file) ?: BookMetadataParser.stripFileExtension(file.name)
                chapters.add(
                    Chapter(
                        id = file.absolutePath,
                        volumeIndex = volIndex,
                        volumeName = volName,
                        chapterNumber = num,
                        title = title,
                        fileUriString = Uri.fromFile(file).toString()
                    )
                )
            }
        }

        scan(searchDir, 1, "第一卷")
        // 先依卷序再依章號排序（支援「各卷獨立計數」的章節編號）
        chapters.sortWith(compareBy<Chapter>({ it.volumeIndex }, { it.chapterNumber }))
        return chapters
    }

    /**
     * 從本機已儲存的網路小說目錄載入書目與章節清單（秒開，實際章節正文於閱讀時按需抓取並快取）
     */
    suspend fun loadWebBookChapters(bookDir: File, bookId: String): StoredWebBook? = withContext(Dispatchers.IO) {
        val stored = WebBookStore.loadBook(bookDir) ?: return@withContext null
        cachedChapters.clear()
        cachedChapters.addAll(stored.chapters)
        currentNovelId = bookId
        currentNovelTitle = stored.title
        currentNovelMetadata = stored.metadata
        currentNovelLocalPath = bookDir.absolutePath
        stored
    }

    /**
     * 載入特定章節的完整內容（支援本機檔案、assets 與網路小說按需抓取+快取）
     */
    suspend fun loadChapterContentOutcome(
        chapter: Chapter,
        webBookDir: File? = null,
        preferWebView: Boolean = false
    ): ChapterFetchOutcome {
        if (chapter.remoteUrl != null) {
            return WebNovelFetcher.fetchChapterWithCache(
                context = context,
                chapter = chapter,
                bookDir = webBookDir,
                preferWebView = preferWebView
            )
        }

        val content = loadChapterContent(chapter)
        return ChapterFetchOutcome.Success(content, null)
    }

    /**
     * 背景預載下一章（僅針對可直連的靜態網路小說，讓自動連播零延遲）
     */
    suspend fun prefetchWebChapterIfDirect(
        chapter: Chapter,
        webBookDir: File?,
        preferWebView: Boolean
    ) {
        if (chapter.remoteUrl == null || preferWebView || webBookDir == null) return
        val cacheFile = WebBookStore.getCachedChapterFile(webBookDir, chapter.chapterNumber)
        if (cacheFile.exists() && cacheFile.length() > 0L) return
        try {
            WebNovelFetcher.fetchChapterWithCache(
                context = context,
                chapter = chapter,
                bookDir = webBookDir,
                preferWebView = false
            )
        } catch (_: Exception) {
        }
    }

    /**
     * 載入特定章節的完整內容
     */
    suspend fun loadChapterContent(chapter: Chapter): ChapterContent = withContext(Dispatchers.IO) {
        val markdownText = when {
            chapter.fileUriString != null -> {
                val uri = Uri.parse(chapter.fileUriString)
                if (uri.scheme == "file") {
                    File(uri.path!!).readText(Charsets.UTF_8)
                } else {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
                    } ?: ""
                }
            }
            chapter.assetPath != null -> {
                context.assets.open(chapter.assetPath).use { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
                }
            }
            else -> ""
        }

        NovelChapterParser.parse(markdownText)
    }

    private fun readTitleFromFile(file: File): String? {
        return try {
            BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line!!.trim()
                    if (trimmed.startsWith("# ")) {
                        return@use NovelChapterParser.cleanMarkdownSymbols(trimmed.removePrefix("#").trim())
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readTitleFromUri(uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val trimmed = line!!.trim()
                        if (trimmed.startsWith("# ")) {
                            return@use NovelChapterParser.cleanMarkdownSymbols(trimmed.removePrefix("#").trim())
                        }
                    }
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
