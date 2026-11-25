package com.novel.reader.util

import android.content.Context
import com.novel.reader.model.Chapter
import com.novel.reader.parser.NovelChapterParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class GitHubUrlInfo(
    val owner: String,
    val repo: String,
    val branch: String,
    val path: String,
    val isSingleFile: Boolean,
    val originalUrl: String = ""
) {
    val rawFileUrl: String
        get() = "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"

    val archiveZipUrl: String
        get() = "https://github.com/$owner/$repo/archive/refs/heads/$branch.zip"

    val inferredNovelRootPath: String
        get() {
            val parentPath = if (path.contains("/")) path.substringBeforeLast("/") else ""
            return when {
                parentPath.contains("/chapters") -> parentPath.substringBefore("/chapters").trimEnd('/')
                parentPath == "chapters" || parentPath.startsWith("chapters/") -> ""
                else -> parentPath
            }
        }

    val inferredBookYamlRawUrls: List<String>
        get() {
            val candidates = linkedSetOf<String>()
            val root = inferredNovelRootPath
            if (root.isNotEmpty()) {
                candidates.add("https://raw.githubusercontent.com/$owner/$repo/$branch/$root/book.yaml")
                candidates.add("https://raw.githubusercontent.com/$owner/$repo/$branch/$root/book.yml")
            } else {
                candidates.add("https://raw.githubusercontent.com/$owner/$repo/$branch/book.yaml")
                candidates.add("https://raw.githubusercontent.com/$owner/$repo/$branch/book.yml")
            }
            val directParent = if (path.contains("/")) path.substringBeforeLast("/") else ""
            if (directParent.isNotEmpty() && directParent != root) {
                candidates.add("https://raw.githubusercontent.com/$owner/$repo/$branch/$directParent/book.yaml")
            }
            return candidates.toList()
        }

    val inferredParentFolderUrl: String
        get() = "https://github.com/$owner/$repo/tree/$branch/$inferredNovelRootPath"
}

sealed class GitHubLoadResult {
    data class Success(
        val chapters: List<Chapter>,
        val title: String,
        val isSingleChapter: Boolean,
        val bookId: String,
        val localDirPath: String,
        val sourceUrl: String
    ) : GitHubLoadResult()

    data class Error(val message: String) : GitHubLoadResult()
}

object GitHubHelper {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val metaClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val BLOB_REGEX = Regex("""^https?://github\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+)$""")
    private val TREE_REGEX = Regex("""^https?://github\.com/([^/]+)/([^/]+)/tree/([^/]+)/?(.*)$""")
    private val RAW_REGEX = Regex("""^https?://raw\.githubusercontent\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$""")
    private val REPO_REGEX = Regex("""^https?://github\.com/([^/]+)/([^/]+)/?$""")

    /**
     * 解析使用者輸入的 GitHub 網址
     */
    fun parseUrl(url: String): GitHubUrlInfo? {
        val trimmed = url.trim()

        // 1. 單一檔案 blob
        BLOB_REGEX.find(trimmed)?.let { match ->
            return GitHubUrlInfo(
                owner = match.groupValues[1],
                repo = match.groupValues[2],
                branch = match.groupValues[3],
                path = match.groupValues[4],
                isSingleFile = true,
                originalUrl = trimmed
            )
        }

        // 2. raw 網址
        RAW_REGEX.find(trimmed)?.let { match ->
            return GitHubUrlInfo(
                owner = match.groupValues[1],
                repo = match.groupValues[2],
                branch = match.groupValues[3],
                path = match.groupValues[4],
                isSingleFile = true,
                originalUrl = trimmed
            )
        }

        // 3. tree 目錄
        TREE_REGEX.find(trimmed)?.let { match ->
            return GitHubUrlInfo(
                owner = match.groupValues[1],
                repo = match.groupValues[2],
                branch = match.groupValues[3],
                path = match.groupValues[4].trimEnd('/'),
                isSingleFile = false,
                originalUrl = trimmed
            )
        }

        // 4. 整體 repo
        REPO_REGEX.find(trimmed)?.let { match ->
            return GitHubUrlInfo(
                owner = match.groupValues[1],
                repo = match.groupValues[2].removeSuffix(".git"),
                branch = "main",
                path = "",
                isSingleFile = false,
                originalUrl = trimmed
            )
        }

        return null
    }

    /**
     * 下載單一 Markdown / TXT 章節：
     * 優先從同小說目錄抓取 book.yaml 決定書名；若無 book.yaml 則以檔案名稱（去除副檔名）作為書名。
     */
    suspend fun downloadSingleChapter(
        context: Context,
        info: GitHubUrlInfo
    ): GitHubLoadResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(info.rawFileUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext GitHubLoadResult.Error("下載失敗 (HTTP ${response.code}): ${response.message}")
            }

            val body = response.body?.string() ?: ""
            if (body.isBlank()) {
                return@withContext GitHubLoadResult.Error("取得的檔案內容為空")
            }

            val fileName = info.path.substringAfterLast("/")
            val safeKey = "${info.owner}_${info.repo}_${info.path}"
                .replace(Regex("""[^a-zA-Z0-9_\u4e00-\u9fa5]"""), "_")
            val bookId = "gh_single_$safeKey"
            val targetDir = File(context.filesDir, bookId)
            targetDir.deleteRecursively()
            targetDir.mkdirs()

            val file = File(targetDir, fileName)
            file.writeText(body, Charsets.UTF_8)

            // 嘗試抓取所屬目錄的 book.yaml 以取得正式書名
            var yamlTitle: String? = null
            for (yamlUrl in info.inferredBookYamlRawUrls) {
                val yamlContent = fetchOptionalText(yamlUrl)
                if (!yamlContent.isNullOrBlank()) {
                    val parsedYamlTitle = BookMetadataParser.parseTitle(yamlContent)
                    if (!parsedYamlTitle.isNullOrBlank()) {
                        yamlTitle = parsedYamlTitle
                        File(targetDir, "book.yaml").writeText(yamlContent, Charsets.UTF_8)
                        break
                    }
                }
            }

            val parsed = NovelChapterParser.parse(body)
            val chapNumber = Regex("""\d+""").find(fileName)?.value?.toIntOrNull() ?: 1
            val chapter = Chapter(
                id = file.absolutePath,
                volumeIndex = 1,
                volumeName = "第一卷",
                chapterNumber = chapNumber,
                title = parsed.title,
                fileUriString = android.net.Uri.fromFile(file).toString()
            )

            // 優先使用 book.yaml 書名；缺 book.yaml 時使用檔案名（去除副檔名）
            val novelTitle = yamlTitle ?: BookMetadataParser.stripFileExtension(fileName)

            GitHubLoadResult.Success(
                chapters = listOf(chapter),
                title = novelTitle,
                isSingleChapter = true,
                bookId = bookId,
                localDirPath = targetDir.absolutePath,
                sourceUrl = info.originalUrl.ifEmpty { info.rawFileUrl }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            GitHubLoadResult.Error("下載發生錯誤: ${e.localizedMessage}")
        }
    }

    private fun fetchOptionalText(url: String): String? {
        return try {
            val req = Request.Builder().url(url).build()
            metaClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 下載整本書或整個目錄 (透過 GitHub Branch Zip 封裝串流解壓)
     * 同步解出 book.yaml / book.yml，優先讀取其中的書名，缺失時退回目錄名 / 專案名。
     */
    suspend fun downloadTreeFolder(
        context: Context,
        info: GitHubUrlInfo,
        onProgress: (message: String) -> Unit = {}
    ): GitHubLoadResult = withContext(Dispatchers.IO) {
        try {
            onProgress("正在連線至 GitHub 下載專案資料...")
            val request = Request.Builder().url(info.archiveZipUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext GitHubLoadResult.Error("連線至 GitHub 失敗 (HTTP ${response.code})")
            }

            val body = response.body ?: return@withContext GitHubLoadResult.Error("回應為空")
            val safePath = info.path.replace(Regex("""[^a-zA-Z0-9_\u4e00-\u9fa5]"""), "_").ifEmpty { "all" }
            val bookId = "gh_${info.owner}_${info.repo}_$safePath"
            val destDir = File(context.filesDir, bookId)
            destDir.deleteRecursively()
            destDir.mkdirs()
            val destCanonical = destDir.canonicalPath

            onProgress("正在解壓縮並索引小說章節...")
            val expectedPrefix = if (info.path.isNotEmpty()) "/${info.path}/" else "/"
            val parentPath = if (info.path.contains("/")) info.path.substringBeforeLast("/") else ""
            val parentYamlSuffix = if (parentPath.isNotEmpty()) "/$parentPath/book.yaml" else null
            val parentYmlSuffix = if (parentPath.isNotEmpty()) "/$parentPath/book.yml" else null

            ZipInputStream(body.byteStream()).use { zis ->
                var entry = zis.nextEntry
                val buffer = ByteArray(8192)
                while (entry != null) {
                    val name = entry.name
                    val isBookYaml = BookMetadataParser.isBookYamlFileName(name)
                    val matchesPath = if (info.path.isEmpty()) true else {
                        name.contains(expectedPrefix) || name.startsWith("${info.repo}-${info.branch}/${info.path}/")
                    }
                    val matchesParentBookYaml = !matchesPath && isBookYaml && (
                        (parentYamlSuffix != null && name.endsWith(parentYamlSuffix, ignoreCase = true)) ||
                        (parentYmlSuffix != null && name.endsWith(parentYmlSuffix, ignoreCase = true))
                    )

                    if (!entry.isDirectory && (
                            (matchesPath && (name.endsWith(".md") || name.endsWith(".txt") || isBookYaml)) ||
                            matchesParentBookYaml
                        )
                    ) {
                        val lowerName = name.lowercase()
                        if (isBookYaml || (!lowerName.contains("rules") && !lowerName.contains("readme"))) {
                            val relativeName = when {
                                matchesParentBookYaml -> name.substringAfterLast("/")
                                info.path.isNotEmpty() -> name.substringAfter(expectedPrefix, name.substringAfterLast("/"))
                                else -> name
                            }

                            val outFile = File(destDir, relativeName)
                            if (outFile.canonicalPath.startsWith(destCanonical + File.separator)) {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { fos ->
                                    var len: Int
                                    while (zis.read(buffer).also { len = it } > 0) {
                                        fos.write(buffer, 0, len)
                                    }
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // 掃描目錄
            val chapters = mutableListOf<Chapter>()
            val searchDir = if (File(destDir, "chapters").isDirectory) File(destDir, "chapters") else destDir

            fun scan(file: File, volIndex: Int, volName: String) {
                if (file.isDirectory) {
                    val name = file.name
                    val vIdx = if (name.contains("volume", ignoreCase = true) || name.startsWith("vol", ignoreCase = true) || name.matches(Regex("""^0*\d+$"""))) {
                        name.replace(Regex("""[^0-9]"""), "").toIntOrNull() ?: volIndex
                    } else volIndex
                    val vName = "第 ${vIdx} 卷"
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
                    val num = Regex("""\d+""").find(file.name)?.value?.toIntOrNull() ?: 0
                    if (num <= 0) return
                    val title = try {
                        file.bufferedReader(Charsets.UTF_8).use { r ->
                            var l: String?
                            var t: String? = null
                            while (r.readLine().also { l = it } != null) {
                                val tr = l!!.trim()
                                if (tr.startsWith("# ")) {
                                    t = NovelChapterParser.cleanMarkdownSymbols(tr.removePrefix("#").trim())
                                    break
                                }
                            }
                            t
                        }
                    } catch (e: Exception) {
                        null
                    } ?: "第 ${num} 章"

                    chapters.add(
                        Chapter(
                            id = file.absolutePath,
                            volumeIndex = volIndex,
                            volumeName = volName,
                            chapterNumber = num,
                            title = title,
                            fileUriString = android.net.Uri.fromFile(file).toString()
                        )
                    )
                }
            }

            scan(searchDir, 1, "第一卷")
            chapters.sortBy { it.chapterNumber }

            if (chapters.isEmpty()) {
                destDir.deleteRecursively()
                return@withContext GitHubLoadResult.Error("在指定的 GitHub 路徑中未找到小說章節檔案 (.md)")
            }

            val fallbackTitle = if (info.path.isNotEmpty()) {
                info.path.substringAfterLast("/")
            } else info.repo
            val novelTitle = BookMetadataParser.findTitleInDirectory(destDir) ?: fallbackTitle

            GitHubLoadResult.Success(
                chapters = chapters,
                title = novelTitle,
                isSingleChapter = false,
                bookId = bookId,
                localDirPath = destDir.absolutePath,
                sourceUrl = info.originalUrl.ifEmpty { info.archiveZipUrl }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            GitHubLoadResult.Error("下載專案發生異常: ${e.localizedMessage}")
        }
    }
}
