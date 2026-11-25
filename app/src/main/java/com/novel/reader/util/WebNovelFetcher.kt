package com.novel.reader.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.novel.reader.model.BookMetadata
import com.novel.reader.model.Chapter
import com.novel.reader.model.ChapterContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

sealed class WebHtmlFetchResult {
    data class Success(val html: String, val finalUrl: String) : WebHtmlFetchResult()
    data class NeedsInteractiveWebView(val url: String, val reason: String) : WebHtmlFetchResult()
    data class Error(val message: String) : WebHtmlFetchResult()
}

sealed class WebBookImportResult {
    data class Success(
        val bookId: String,
        val title: String,
        val sourceUrl: String,
        val localDirPath: String,
        val chapters: List<Chapter>,
        val preferWebView: Boolean,
        val metadata: BookMetadata = BookMetadata(title = title)
    ) : WebBookImportResult()

    data class NeedsInteractiveWebView(val url: String, val reason: String) : WebBookImportResult()
    data class Error(val message: String) : WebBookImportResult()
}

/**
 * 網路小說抓取器：
 * 1. 優先使用 OkHttp 直連抓取（適用於飄天文學等純靜態站點，極快且可背景執行）
 * 2. 若遇 Cloudflare / JS 保護，可嘗試透過共用同一個已驗證 session 的防廣告 WebView 靜默載入；
 *    若網站要求互動驗證，則回傳 NeedsInteractiveWebView 交給可見 WebView 讓使用者完成驗證。
 */
object WebNovelFetcher {

    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * 直接透過 OkHttp 抓取網頁 HTML（含自動偵測 GB18030/UTF-8 編碼與 Cloudflare 偵測）
     */
    suspend fun fetchHtmlDirect(url: String): WebHtmlFetchResult = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-TW,zh;q=0.9,en;q=0.5")

            // 附帶本機 WebView 已有的正常瀏覽 Cookie（若有的話）
            try {
                val cookie = CookieManager.getInstance().getCookie(url)
                if (!cookie.isNullOrBlank()) {
                    builder.header("Cookie", cookie)
                }
            } catch (_: Exception) {
            }

            client.newCall(builder.build()).execute().use { response ->
                val finalUrl = response.request.url.toString()
                val contentType = response.header("Content-Type")
                val bytes = response.body?.bytes() ?: ByteArray(0)
                val html = WebNovelParser.decodeHtmlBytes(bytes, contentType)

                val cfMitigated = response.header("cf-mitigated")
                if (cfMitigated != null || WebNovelParser.isChallengePage(html, response.code)) {
                    return@withContext WebHtmlFetchResult.NeedsInteractiveWebView(
                        url = finalUrl,
                        reason = "此網站啟用了瀏覽器驗證保護，請透過內建防廣告瀏覽器開啟。"
                    )
                }

                if (!response.isSuccessful) {
                    return@withContext WebHtmlFetchResult.Error("網頁載入失敗 (HTTP ${response.code})")
                }

                if (html.isBlank()) {
                    return@withContext WebHtmlFetchResult.Error("取得的網頁內容為空")
                }

                WebHtmlFetchResult.Success(html = html, finalUrl = finalUrl)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            WebHtmlFetchResult.Error("連線失敗：${e.localizedMessage ?: "網路錯誤"}")
        }
    }

    /**
     * 嘗試直接匯入一個網路小說網址（目錄頁或單章頁）。
     * 若網站需要互動驗證或 JS 渲染，回傳 NeedsInteractiveWebView 讓 UI 開啟防廣告瀏覽器。
     */
    suspend fun importFromUrlDirect(context: Context, url: String): WebBookImportResult = withContext(Dispatchers.IO) {
        when (val fetch = fetchHtmlDirect(url)) {
            is WebHtmlFetchResult.NeedsInteractiveWebView -> {
                WebBookImportResult.NeedsInteractiveWebView(fetch.url, fetch.reason)
            }
            is WebHtmlFetchResult.Error -> {
                WebBookImportResult.Error(fetch.message)
            }
            is WebHtmlFetchResult.Success -> {
                saveParsedHtmlAsBook(
                    context = context,
                    html = fetch.html,
                    pageUrl = fetch.finalUrl,
                    preferWebView = false
                )
            }
        }
    }

    /**
     * 將已取得的目錄頁（或單章頁）HTML 解析並存入本機 WebBookStore，回傳可直接加入書單的結果
     */
    fun saveParsedHtmlAsBook(
        context: Context,
        html: String,
        pageUrl: String,
        preferWebView: Boolean
    ): WebBookImportResult {
        val parsedToc = WebNovelParser.parseToc(html, pageUrl)
        val bookId = WebBookStore.generateBookId(pageUrl)
        val bookDir = File(context.filesDir, bookId)

        if (parsedToc.chapters.size >= 2) {
            val chapters = WebNovelParser.toChapters(parsedToc, bookDir)
            WebBookStore.saveBook(
                bookDir = bookDir,
                title = parsedToc.title,
                sourceUrl = pageUrl,
                preferWebView = preferWebView,
                chapters = chapters,
                metadata = parsedToc.metadata
            )
            return WebBookImportResult.Success(
                bookId = bookId,
                title = parsedToc.title,
                sourceUrl = pageUrl,
                localDirPath = bookDir.absolutePath,
                chapters = chapters,
                preferWebView = preferWebView,
                metadata = parsedToc.metadata
            )
        }

        // 若不是目錄頁，嘗試當作單一章節匯入
        val parsedChapter = WebNovelParser.parseChapter(html, pageUrl)
        if (parsedChapter.bodyParagraphs.isNotEmpty()) {
            val cacheFile = WebBookStore.getCachedChapterFile(bookDir, 1)
            WebBookStore.saveCachedChapter(cacheFile, parsedChapter)
            val singleChapter = Chapter(
                id = pageUrl,
                volumeIndex = 1,
                volumeName = "正文",
                chapterNumber = 1,
                title = parsedChapter.title,
                fileUriString = "file://${cacheFile.absolutePath}",
                remoteUrl = pageUrl
            )
            val bookTitle = parsedToc.title.ifBlank { parsedChapter.title }
            val singleMeta = parsedToc.metadata.copy(title = bookTitle)
            WebBookStore.saveBook(
                bookDir = bookDir,
                title = bookTitle,
                sourceUrl = pageUrl,
                preferWebView = preferWebView,
                chapters = listOf(singleChapter),
                metadata = singleMeta
            )
            return WebBookImportResult.Success(
                bookId = bookId,
                title = bookTitle,
                sourceUrl = pageUrl,
                localDirPath = bookDir.absolutePath,
                chapters = listOf(singleChapter),
                preferWebView = preferWebView,
                metadata = singleMeta
            )
        }

        return WebBookImportResult.NeedsInteractiveWebView(
            url = pageUrl,
            reason = "未在靜態 HTML 中偵測到目錄或正文，改由內建防廣告瀏覽器開啟。"
        )
    }

    /**
     * 抓取單一章節內容（先讀本機快取 -> 直連 OkHttp -> 若受保護則用防廣告 WebView session 靜默載入）。
     * 若仍觸發互動驗證，回傳 null 並由呼叫端決定是否彈出可見驗證視窗。
     */
    suspend fun fetchChapterWithCache(
        context: Context,
        chapter: Chapter,
        bookDir: File?,
        preferWebView: Boolean = false
    ): ChapterFetchOutcome {
        val remoteUrl = chapter.remoteUrl
            ?: return ChapterFetchOutcome.Error("章節缺少遠端網址")

        // 1. 檢查本機快取
        val cacheFile = bookDir?.let { WebBookStore.getCachedChapterFile(it, chapter.chapterNumber) }
        if (cacheFile != null) {
            val cached = withContext(Dispatchers.IO) { WebBookStore.readCachedChapter(cacheFile) }
            if (cached != null) {
                return ChapterFetchOutcome.Success(cached, cacheFile)
            }
        }

        // 2. 非優先 WebView 的網站，先試直連 OkHttp（飄天等站點 100~300ms 即可完成）
        if (!preferWebView) {
            when (val direct = fetchHtmlDirect(remoteUrl)) {
                is WebHtmlFetchResult.Success -> {
                    val content = withContext(Dispatchers.IO) {
                        WebNovelParser.parseChapter(direct.html, direct.finalUrl, chapter.title)
                    }
                    if (content.bodyParagraphs.isNotEmpty()) {
                        if (cacheFile != null) {
                            withContext(Dispatchers.IO) { WebBookStore.saveCachedChapter(cacheFile, content) }
                        }
                        return ChapterFetchOutcome.Success(content, cacheFile)
                    }
                }
                is WebHtmlFetchResult.NeedsInteractiveWebView -> {
                    // 往下嘗試用已驗證的 WebView session 載入
                }
                is WebHtmlFetchResult.Error -> {
                    // 往下嘗試 WebView 備援或回報錯誤
                }
            }
        }

        // 3. 使用已驗證過的 App WebView session 靜默載入（全程啟用廣告攔截）
        val wvResult = fetchHtmlViaSessionWebView(context, remoteUrl, timeoutMs = 12_000L)
        return when (wvResult) {
            is WebHtmlFetchResult.Success -> {
                val content = withContext(Dispatchers.IO) {
                    WebNovelParser.parseChapter(wvResult.html, wvResult.finalUrl, chapter.title)
                }
                if (content.bodyParagraphs.isNotEmpty()) {
                    if (cacheFile != null) {
                        withContext(Dispatchers.IO) { WebBookStore.saveCachedChapter(cacheFile, content) }
                    }
                    ChapterFetchOutcome.Success(content, cacheFile)
                } else {
                    ChapterFetchOutcome.NeedsInteractiveVerification(remoteUrl, chapter)
                }
            }
            is WebHtmlFetchResult.NeedsInteractiveWebView -> {
                ChapterFetchOutcome.NeedsInteractiveVerification(remoteUrl, chapter)
            }
            is WebHtmlFetchResult.Error -> {
                ChapterFetchOutcome.Error(wvResult.message)
            }
        }
    }

    /**
     * 使用與可見瀏覽器共用 Cookie / Session 的輕量 WebView 載入頁面並抽取 DOM。
     * 若頁面要求人機驗證，不嘗試繞過，立即回傳 NeedsInteractiveWebView 交給使用者手動驗證。
     */
    suspend fun fetchHtmlViaSessionWebView(
        context: Context,
        url: String,
        timeoutMs: Long = 12_000L
    ): WebHtmlFetchResult {
        val result = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<WebHtmlFetchResult> { cont ->
                    val allowedHost = WebAdBlocker.extractHost(url) ?: ""
                    val handler = Handler(Looper.getMainLooper())
                    var finished = false
                    var webView: WebView? = null

                    fun finishWith(res: WebHtmlFetchResult) {
                        if (finished) return
                        finished = true
                        handler.removeCallbacksAndMessages(null)
                        try {
                            webView?.stopLoading()
                            webView?.destroy()
                        } catch (_: Exception) {
                        }
                        webView = null
                        if (cont.isActive) {
                            cont.resume(res)
                        }
                    }

                    try {
                        val wv = WebView(context.applicationContext)
                        webView = wv
                        wv.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            loadsImagesAutomatically = false
                            blockNetworkImage = true
                            javaScriptCanOpenWindowsAutomatically = false
                            setSupportMultipleWindows(false)
                            cacheMode = WebSettings.LOAD_DEFAULT
                        }
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

                        wv.webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val reqUrl = request?.url?.toString() ?: return null
                                if (WebAdBlocker.isAdUrl(reqUrl)) {
                                    return WebResourceResponse(
                                        "text/plain",
                                        "utf-8",
                                        ByteArrayInputStream(ByteArray(0))
                                    )
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val target = request?.url?.toString() ?: return false
                                return !WebAdBlocker.isAllowedNavigation(target, allowedHost)
                            }

                            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                                if (finished || view == null) return
                                val finalUrl = loadedUrl ?: url
                                handler.postDelayed({
                                    if (finished) return@postDelayed
                                    view.evaluateJavascript(
                                        "(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })();"
                                    ) { raw ->
                                        val html = decodeJsString(raw)
                                        if (html.isBlank() || WebNovelParser.isChallengePage(html)) {
                                            finishWith(
                                                WebHtmlFetchResult.NeedsInteractiveWebView(
                                                    finalUrl,
                                                    "網站要求瀏覽器驗證，請手動確認。"
                                                )
                                            )
                                        } else {
                                            finishWith(WebHtmlFetchResult.Success(html, finalUrl))
                                        }
                                    }
                                }, 500L)
                            }
                        }

                        cont.invokeOnCancellation {
                            handler.post {
                                if (!finished) {
                                    finished = true
                                    handler.removeCallbacksAndMessages(null)
                                    try {
                                        webView?.stopLoading()
                                        webView?.destroy()
                                    } catch (_: Exception) {
                                    }
                                    webView = null
                                }
                            }
                        }

                        wv.loadUrl(url)
                    } catch (e: Exception) {
                        finishWith(WebHtmlFetchResult.Error("WebView 初始化失敗：${e.localizedMessage}"))
                    }
                }
            }
        }
        return result ?: WebHtmlFetchResult.NeedsInteractiveWebView(url, "網頁載入逾時，請透過內建瀏覽器開啟。")
    }

    fun decodeJsString(rawJsResult: String?): String {
        if (rawJsResult.isNullOrBlank() || rawJsResult == "null") return ""
        return try {
            (JSONTokener(rawJsResult).nextValue() as? String) ?: ""
        } catch (_: Exception) {
            rawJsResult.trim('"')
        }
    }
}

sealed class ChapterFetchOutcome {
    data class Success(val content: ChapterContent, val cacheFile: File?) : ChapterFetchOutcome()
    data class NeedsInteractiveVerification(val url: String, val chapter: Chapter) : ChapterFetchOutcome()
    data class Error(val message: String) : ChapterFetchOutcome()
}
