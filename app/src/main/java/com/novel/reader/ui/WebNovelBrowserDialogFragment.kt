package com.novel.reader.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.novel.reader.R
import com.novel.reader.databinding.DialogWebNovelBrowserBinding
import com.novel.reader.util.WebAdBlocker
import com.novel.reader.util.WebBookImportResult
import com.novel.reader.util.WebNovelFetcher
import com.novel.reader.util.WebNovelParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/**
 * 內建防廣告 WebView 瀏覽器：
 * - 封鎖彈窗、外部廣告跳轉、常見廣告聯盟網域
 * - 若網站要求 Cloudflare / 人機驗證，由使用者於可見畫面自然完成
 * - 載入完成後，自動偵測目錄或章節正文，並交回原生無廣告閱讀介面與 TTS 朗讀
 */
class WebNovelBrowserDialogFragment(
    private val initialUrl: String,
    private val mode: Mode = Mode.IMPORT_BOOK,
    private val onBookImported: ((WebBookImportResult.Success) -> Unit)? = null,
    private val onChapterHtmlReady: ((html: String, finalUrl: String) -> Unit)? = null
) : DialogFragment() {

    constructor() : this("")

    enum class Mode {
        IMPORT_BOOK,       // 匯入整本書目錄或單一章節至書單
        VERIFY_CHAPTER     // 閱讀中途網站要求驗證時，通過驗證後自動抽回該章內容
    }

    private var _binding: DialogWebNovelBrowserBinding? = null
    private val binding get() = _binding!!

    private val handler = Handler(Looper.getMainLooper())
    private var allowedHost: String = ""
    private var blockedAdsCount: Int = 0
    private var latestHtml: String = ""
    private var latestUrl: String = initialUrl
    private var deliveredChapter = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null || initialUrl.isBlank()) {
            dismissAllowingStateLoss()
            return
        }
        setStyle(STYLE_NORMAL, R.style.Theme_NovelReader_FullscreenDialog)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.setCanceledOnTouchOutside(false)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogWebNovelBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        allowedHost = WebAdBlocker.extractHost(initialUrl) ?: ""

        if (mode == Mode.VERIFY_CHAPTER) {
            binding.btnWebImportCurrent.text = "完成驗證並繼續朗讀"
        }

        binding.btnWebClose.setOnClickListener { dismiss() }
        binding.btnWebBack.setOnClickListener {
            if (binding.webViewNovel.canGoBack()) {
                binding.webViewNovel.goBack()
            }
        }
        binding.btnWebReload.setOnClickListener {
            binding.webViewNovel.reload()
        }
        binding.btnWebImportCurrent.setOnClickListener {
            extractAndComplete(userTriggered = true)
        }

        setupWebView()
        binding.webViewNovel.loadUrl(initialUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val wv = binding.webViewNovel
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                isAlgorithmicDarkeningAllowed = true
            }
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                val b = _binding ?: return
                if (newProgress in 1..99) {
                    b.progressWebLoad.visibility = View.VISIBLE
                    b.progressWebLoad.progress = newProgress
                } else {
                    b.progressWebLoad.visibility = View.GONE
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                val b = _binding ?: return
                if (!title.isNullOrBlank()) {
                    b.tvWebBrowserTitle.text = title
                }
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val reqUrl = request?.url?.toString() ?: return null
                if (WebAdBlocker.isAdUrl(reqUrl)) {
                    blockedAdsCount++
                    val countSnapshot = blockedAdsCount
                    handler.post {
                        _binding?.tvWebAdBlockStatus?.text = "已啟用防彈窗 · 攔截 $countSnapshot 個廣告請求"
                    }
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
                val targetUrl = request?.url?.toString() ?: return false
                if (!WebAdBlocker.isAllowedNavigation(targetUrl, allowedHost)) {
                    blockedAdsCount++
                    val countSnapshot = blockedAdsCount
                    handler.post {
                        _binding?.tvWebAdBlockStatus?.text = "已封鎖外部跳轉 · 共攔截 $countSnapshot 次"
                    }
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null) {
                    latestUrl = url
                }
                scheduleDomInspection()
            }
        }
    }

    private fun scheduleDomInspection() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            val b = _binding ?: return@postDelayed
            b.webViewNovel.evaluateJavascript(
                "(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })();"
            ) { raw ->
                val html = WebNovelFetcher.decodeJsString(raw)
                latestHtml = html
                inspectLoadedHtml(html, latestUrl)
            }
        }, 700L)
    }

    private fun inspectLoadedHtml(html: String, url: String) {
        val b = _binding ?: return
        if (html.isBlank() || WebNovelParser.isChallengePage(html)) {
            b.tvWebDetectStatus.text = "網站要求人機驗證，請在上方畫面完成驗證（驗證完會自動偵測）..."
            // 若處於驗證中，每 1.5 秒重新檢查是否已通過並載入內容
            handler.postDelayed({ scheduleDomInspection() }, 1500L)
            return
        }

        lifecycleScope.launch {
            if (mode == Mode.VERIFY_CHAPTER) {
                val chapter = withContext(Dispatchers.IO) {
                    WebNovelParser.parseChapter(html, url)
                }
                val bindingNow = _binding ?: return@launch
                if (chapter.bodyParagraphs.isNotEmpty() && !deliveredChapter) {
                    deliveredChapter = true
                    bindingNow.tvWebDetectStatus.text = "驗證完成，已取得《${chapter.title}》正文，正在回到閱讀介面..."
                    onChapterHtmlReady?.invoke(html, url)
                    dismiss()
                } else {
                    bindingNow.tvWebDetectStatus.text = "頁面已載入，確認顯示正文後請點擊下方按鈕繼續朗讀"
                }
            } else {
                val parsed = withContext(Dispatchers.IO) {
                    WebNovelParser.parseToc(html, url)
                }
                val bindingNow = _binding ?: return@launch
                if (parsed.chapters.size >= 2) {
                    bindingNow.tvWebDetectStatus.text =
                        "已偵測到《${parsed.title}》（共 ${parsed.chapters.size} 章），點擊下方加入書單並朗讀"
                    bindingNow.btnWebImportCurrent.text = "加入書單：《${parsed.title}》(${parsed.chapters.size}章)"
                } else {
                    val ch = withContext(Dispatchers.IO) {
                        WebNovelParser.parseChapter(html, url)
                    }
                    val bindingAfter = _binding ?: return@launch
                    if (ch.bodyParagraphs.isNotEmpty()) {
                        bindingAfter.tvWebDetectStatus.text =
                            "已偵測到單章正文《${ch.title}》（${ch.bodyParagraphs.size} 段），點擊下方開始朗讀"
                        bindingAfter.btnWebImportCurrent.text = "匯入此章並朗讀"
                    } else {
                        bindingAfter.tvWebDetectStatus.text =
                            "請先瀏覽至小說目錄頁或章節頁，再點擊下方按鈕匯入"
                    }
                }
            }
        }
    }

    private fun extractAndComplete(userTriggered: Boolean) {
        val b = _binding ?: return
        b.btnWebImportCurrent.isEnabled = false
        b.webViewNovel.evaluateJavascript(
            "(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })();"
        ) { raw ->
            val html = WebNovelFetcher.decodeJsString(raw).ifBlank { latestHtml }
            val currentUrl = _binding?.webViewNovel?.url ?: latestUrl

            if (html.isBlank() || WebNovelParser.isChallengePage(html)) {
                _binding?.btnWebImportCurrent?.isEnabled = true
                if (userTriggered) {
                    Toast.makeText(requireContext(), "請先在上方畫面完成網站驗證並等待內容載入", Toast.LENGTH_LONG).show()
                }
                return@evaluateJavascript
            }

            if (mode == Mode.VERIFY_CHAPTER) {
                deliveredChapter = true
                onChapterHtmlReady?.invoke(html, currentUrl)
                dismiss()
                return@evaluateJavascript
            }

            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    WebNovelFetcher.saveParsedHtmlAsBook(
                        context = requireContext(),
                        html = html,
                        pageUrl = currentUrl,
                        preferWebView = true
                    )
                }
                val bNow = _binding ?: return@launch
                bNow.btnWebImportCurrent.isEnabled = true

                when (result) {
                    is WebBookImportResult.Success -> {
                        Toast.makeText(
                            requireContext(),
                            "已加入書單「${result.title}」（共 ${result.chapters.size} 章）",
                            Toast.LENGTH_SHORT
                        ).show()
                        dismiss()
                        onBookImported?.invoke(result)
                    }
                    is WebBookImportResult.NeedsInteractiveWebView -> {
                        Toast.makeText(requireContext(), "目前頁面尚未找到章節目錄或正文，請確認已進入小說頁面", Toast.LENGTH_LONG).show()
                    }
                    is WebBookImportResult.Error -> {
                        Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        _binding?.webViewNovel?.apply {
            stopLoading()
            webViewClient = WebViewClient()
            webChromeClient = null
            destroy()
        }
        _binding = null
        super.onDestroyView()
    }
}
