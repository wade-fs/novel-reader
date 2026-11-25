package com.novel.reader.util

import com.novel.reader.model.Chapter
import com.novel.reader.model.ChapterContent
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URI
import java.nio.charset.Charset

data class ParsedWebChapterRef(
    val volumeIndex: Int,
    val volumeName: String,
    val chapterNumber: Int,
    val title: String,
    val url: String
)

data class ParsedWebBook(
    val title: String,
    val chapters: List<ParsedWebChapterRef>,
    val sourceUrl: String
)

/**
 * 網頁小說 HTML 解析器：
 * - 專屬支援：飄天文學 (piaotia.com)、小說狂人 (czbooks.net)、UU看書 (uukanshu.cc)
 * - 通用啟發式備援：適用多數靜態／動態中文小說網站
 * - 自動偵測編碼 (UTF-8 / GB18030 / GBK / Big5) 並過濾網站廣告字句
 */
object WebNovelParser {

    private val CHARSET_HEADER_REGEX = Regex("""charset\s*=\s*["']?([a-zA-Z0-9_\-]+)""", RegexOption.IGNORE_CASE)
    private val META_CHARSET_REGEX = Regex("""<meta[^>]+charset\s*=\s*["']?([a-zA-Z0-9_\-]+)""", RegexOption.IGNORE_CASE)

    private val NAV_LINK_BLACKLIST = setOf(
        "首頁", "首页", "返回首頁", "返回首页", "書架", "书架", "我的書架", "我的书架",
        "加入書架", "加入书架", "加入書籤", "加入书签", "推薦本書", "推荐本书", "收藏本書", "收藏本书",
        "上一章", "下一章", "上一頁", "上一页", "下一頁", "下一页", "返回目錄", "返回目录", "目錄", "目录",
        "返回書頁", "返回书页", "書籍介紹", "书籍介绍", "登入", "登录", "註冊", "注册", "排行榜", "排行",
        "全本", "分類", "分类", "搜尋", "搜索", "聯絡我們", "联系我们", "章節報錯", "章节报错",
        "返回頂部", "返回顶部", "繁體中文", "简体中文", "最新章節", "最新章节"
    )

    private val AD_TEXT_PATTERNS = listOf(
        Regex("""\{?\s*飘天文学[^\n]*?(?:感谢|动力|piaotia)[^\n]*\}?""", RegexOption.IGNORE_CASE),
        Regex("""\{?\s*飄天文學[^\n]*?(?:感謝|動力|piaotia)[^\n]*\}?""", RegexOption.IGNORE_CASE),
        Regex("""(?:www\.)?(?:piaotia|czbooks|uukanshu)\.[a-z]+""", RegexOption.IGNORE_CASE),
        Regex("""^.*(?:請收藏本站|请收藏本站|天才一秒記住|天才一秒记住|一秒記住|一秒记住).*$"""),
        Regex("""^.*(?:章節報錯|章节报错|點此舉報|点此举报|手機用戶請瀏覽|手机用户请浏览).*$"""),
        Regex("""^.*(?:本章未完|請點擊下一頁繼續閱讀|请点击下一页继续阅读|請點選下一頁).*$"""),
        Regex("""^.*(?:喜歡.*請大家收藏|喜欢.*请大家收藏|更新速度全網最快|更新速度全网最快).*$"""),
        Regex("""^.*(?:最新章節地址|最新章节地址|全文閱讀地址|全文阅读地址).*$"""),
        Regex("""^[\s;；,，·•\-_=*~]+$""")
    )

    private val TITLE_SUFFIX_PATTERNS = listOf(
        Regex("""最新章節.*$"""),
        Regex("""最新章节.*$"""),
        Regex("""全文閱讀.*$"""),
        Regex("""全文阅读.*$"""),
        Regex("""無彈窗.*$"""),
        Regex("""无弹窗.*$"""),
        Regex("""目錄.*$"""),
        Regex("""目录.*$"""),
        Regex("""\s*[-_|｜]\s*(?:飄天文學|飘天文学|PT文學|PT文学|小說狂人|小说狂人|UU看書|UU看书|czbooks|uukanshu).*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*[-_|｜]\s*[^-_｜|]*小說網.*$"""),
        Regex("""\s*[-_|｜]\s*[^-_｜|]*小说网.*$""")
    )

    /**
     * 判斷是否為支援的網頁小說網址（一般 http/https 網址且非 GitHub 專案/檔案連結）
     */
    fun isWebNovelUrl(url: String): Boolean {
        val trimmed = url.trim()
        val lower = trimmed.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        if (GitHubHelper.parseUrl(trimmed) != null) return false
        return true
    }

    /**
     * 偵測是否為 Cloudflare 或人機驗證/攔截頁面
     */
    fun isChallengePage(html: String, httpCode: Int = 200): Boolean {
        if (httpCode == 403 || httpCode == 503 || httpCode == 429) {
            if (html.isBlank()) return true
        }
        val head = html.take(4000).lowercase()
        val hasCfTitle = head.contains("<title>just a moment") ||
                head.contains("<title>attention required") ||
                head.contains("<title>access denied") ||
                head.contains("checking your browser") ||
                head.contains("verify you are human")
        val hasCfChallenge = head.contains("cf-browser-verification") ||
                head.contains("cf_chl_opt") ||
                head.contains("/cdn-cgi/challenge-platform/h/") ||
                head.contains("challenge-running") ||
                head.contains("turnstile")
        return hasCfTitle || (httpCode >= 400 && hasCfChallenge)
    }

    /**
     * 依 HTTP Content-Type 與 HTML meta 標籤自動偵測編碼（GBK/GB2312 統一以超集 GB18030 解碼避免缺字）
     */
    fun decodeHtmlBytes(bytes: ByteArray, contentTypeHeader: String? = null): String {
        val headerCharset = contentTypeHeader?.let { CHARSET_HEADER_REGEX.find(it)?.groupValues?.get(1) }
        val headAscii = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val metaCharset = META_CHARSET_REGEX.find(headAscii)?.groupValues?.get(1)

        val chosenName = normalizeCharsetName(metaCharset ?: headerCharset)
        val charset = try {
            Charset.forName(chosenName)
        } catch (_: Exception) {
            Charsets.UTF_8
        }
        return String(bytes, charset)
    }

    private fun normalizeCharsetName(raw: String?): String {
        val lower = raw?.trim()?.lowercase() ?: return "UTF-8"
        return when {
            lower == "gbk" || lower == "gb2312" || lower == "gb18030" || lower == "cp936" -> "GB18030"
            lower == "big5" || lower == "big5-hkscs" || lower == "cp950" -> "Big5"
            lower == "utf8" || lower == "utf-8" -> "UTF-8"
            else -> raw.trim()
        }
    }

    /**
     * 解析書籍目錄頁 HTML，抽出書名與所有章節連結
     */
    fun parseToc(html: String, baseUrl: String): ParsedWebBook {
        val doc = Jsoup.parse(html, baseUrl)
        val host = WebAdBlocker.extractHost(baseUrl) ?: ""

        val title = extractBookTitle(doc, baseUrl, host)
        val chapters = when {
            host.contains("piaotia") -> parsePiaotiaToc(doc)
            host.contains("czbooks") -> parseCzbooksToc(doc)
            host.contains("uukanshu") -> parseUukanshuToc(doc)
            else -> emptyList()
        }.ifEmpty {
            parseGenericToc(doc, baseUrl)
        }

        return ParsedWebBook(
            title = title,
            chapters = chapters,
            sourceUrl = baseUrl
        )
    }

    private fun extractBookTitle(doc: Document, baseUrl: String, host: String): String {
        val candidates = mutableListOf<String>()
        when {
            host.contains("czbooks") -> {
                doc.selectFirst("span.title, div.info .title, h1.title")?.text()?.let { candidates.add(it) }
            }
            host.contains("uukanshu") -> {
                doc.selectFirst("h1.booktitle, .bookinfo h1, h1")?.text()?.let { candidates.add(it) }
            }
            host.contains("piaotia") -> {
                doc.selectFirst("div.title h1, h1")?.text()?.let { candidates.add(it) }
            }
        }
        doc.selectFirst("meta[property=og:novel:book_name]")?.attr("content")?.let { candidates.add(it) }
        doc.selectFirst("meta[property=og:title]")?.attr("content")?.let { candidates.add(it) }
        doc.selectFirst("h1")?.text()?.let { candidates.add(it) }
        doc.title().takeIf { it.isNotBlank() }?.let { candidates.add(it) }

        for (raw in candidates) {
            val cleaned = cleanBookTitle(raw)
            if (cleaned.isNotBlank() && cleaned.length <= 60) {
                return cleaned
            }
        }
        return try {
            val uri = URI(baseUrl)
            val seg = uri.path.trimEnd('/').substringAfterLast('/')
            seg.ifBlank { uri.host ?: "網路小說" }
        } catch (_: Exception) {
            "網路小說"
        }
    }

    fun cleanBookTitle(raw: String): String {
        var text = raw.trim()
            .removePrefix("《")
            .removeSuffix("》")
            .trim()
        // 處理像 "奥术神座最新章节,奥术神座无弹窗全文阅读_飘天文学"
        if (text.contains(",")) {
            text = text.substringBefore(",").trim()
        }
        for (pattern in TITLE_SUFFIX_PATTERNS) {
            text = pattern.replace(text, "").trim()
        }
        return text.removePrefix("《").removeSuffix("》").trim()
    }

    private fun parsePiaotiaToc(doc: Document): List<ParsedWebChapterRef> {
        val result = mutableListOf<ParsedWebChapterRef>()
        val seen = HashSet<String>()
        var volIndex = 1
        var volName = "正文"
        var chapNum = 1

        // 飄天目錄結構：div.centent / div.centtbody 內的 div.list / div.centertitle (卷名) 與 ul > li > a (章節)
        val container = doc.selectFirst("div.centent, div.centtbody, div.mainbody") ?: doc.body()
        for (el in container.select("div.list, div.centertitle, li.title, li > a[href]")) {
            if (el.tagName().equals("a", ignoreCase = true)) {
                val absUrl = el.absUrl("href").trim()
                val text = el.text().trim()
                if (absUrl.isBlank() || text.isBlank()) continue
                if (!absUrl.lowercase().endsWith(".html") && !absUrl.lowercase().endsWith(".htm")) continue
                if (absUrl.lowercase().endsWith("index.html")) continue
                if (text in NAV_LINK_BLACKLIST) continue
                if (seen.add(absUrl)) {
                    result.add(
                        ParsedWebChapterRef(
                            volumeIndex = volIndex,
                            volumeName = volName,
                            chapterNumber = chapNum++,
                            title = text,
                            url = absUrl
                        )
                    )
                }
            } else {
                val vText = el.text().trim()
                if (vText.isNotBlank() && vText.length <= 40) {
                    if (result.isNotEmpty()) volIndex++
                    volName = vText
                }
            }
        }
        return result
    }

    private fun parseCzbooksToc(doc: Document): List<ParsedWebChapterRef> {
        val result = mutableListOf<ParsedWebChapterRef>()
        val seen = HashSet<String>()
        var volIndex = 1
        var volName = "正文"
        var chapNum = 1

        val listItems = doc.select("ul.chapter-list > li, #chapter-list > li, .chapter-list li")
        for (li in listItems) {
            if (li.hasClass("volume")) {
                val vText = li.text().trim()
                if (vText.isNotBlank()) {
                    if (result.isNotEmpty()) volIndex++
                    volName = vText
                }
                continue
            }
            val a = li.selectFirst("a[href]") ?: continue
            val absUrl = a.absUrl("href").trim()
            val text = a.text().trim()
            if (absUrl.isBlank() || text.isBlank() || text in NAV_LINK_BLACKLIST) continue
            if (seen.add(absUrl)) {
                result.add(
                    ParsedWebChapterRef(
                        volumeIndex = volIndex,
                        volumeName = volName,
                        chapterNumber = chapNum++,
                        title = text,
                        url = absUrl
                    )
                )
            }
        }
        return result
    }

    private fun parseUukanshuToc(doc: Document): List<ParsedWebChapterRef> {
        val result = mutableListOf<ParsedWebChapterRef>()
        val seen = HashSet<String>()
        var volIndex = 1
        var volName = "正文"
        var chapNum = 1

        val container = doc.selectFirst("#list-chapterAll, dl.chapterlist, div.booklist, #chapterList")
        if (container != null) {
            for (el in container.select("dt, dd > a[href], li > a[href]")) {
                if (el.tagName().equals("dt", ignoreCase = true)) {
                    val vText = el.text().trim()
                    if (vText.isNotBlank() && !vText.contains("最新")) {
                        if (result.isNotEmpty()) volIndex++
                        volName = vText
                    }
                    continue
                }
                val absUrl = el.absUrl("href").trim()
                val text = el.text().trim()
                if (absUrl.isBlank() || text.isBlank() || text in NAV_LINK_BLACKLIST) continue
                if (seen.add(absUrl)) {
                    result.add(
                        ParsedWebChapterRef(
                            volumeIndex = volIndex,
                            volumeName = volName,
                            chapterNumber = chapNum++,
                            title = text,
                            url = absUrl
                        )
                    )
                }
            }
        }
        return result
    }

    /**
     * 通用目錄啟發式解析：找出頁面中章節連結最密集的容器並按順序抽出
     */
    private fun parseGenericToc(doc: Document, baseUrl: String): List<ParsedWebChapterRef> {
        val baseHost = WebAdBlocker.extractHost(baseUrl) ?: ""
        val candidates = doc.select("ul, dl, ol, div.list, div.chapter-list, div#list, div.box_con")

        var bestLinks: List<Pair<String, String>> = emptyList()
        for (container in candidates) {
            val links = extractValidChapterLinks(container, baseHost)
            if (links.size > bestLinks.size) {
                bestLinks = links
            }
        }

        if (bestLinks.size < 3) {
            bestLinks = extractValidChapterLinks(doc.body(), baseHost)
        }

        return bestLinks.mapIndexed { idx, (title, url) ->
            ParsedWebChapterRef(
                volumeIndex = 1,
                volumeName = "正文",
                chapterNumber = idx + 1,
                title = title,
                url = url
            )
        }
    }

    private fun extractValidChapterLinks(container: Element, baseHost: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val seen = HashSet<String>()
        for (a in container.select("a[href]")) {
            val rawHref = a.attr("href").trim()
            if (rawHref.isEmpty() || rawHref.startsWith("#") || rawHref.startsWith("javascript:", true)) continue
            val absUrl = a.absUrl("href").trim()
            if (!absUrl.startsWith("http://") && !absUrl.startsWith("https://")) continue
            val linkHost = WebAdBlocker.extractHost(absUrl) ?: continue
            if (baseHost.isNotBlank() && linkHost != baseHost && !linkHost.endsWith(".$baseHost")) continue
            if (WebAdBlocker.isAdUrl(absUrl)) continue

            val text = a.text().trim()
            if (text.length < 2 || text.length > 80) continue
            if (text in NAV_LINK_BLACKLIST) continue
            val lowerUrl = absUrl.lowercase()
            if (lowerUrl.contains("bookcase") || lowerUrl.contains("uservote") ||
                lowerUrl.contains("login") || lowerUrl.contains("register") ||
                lowerUrl.contains("newmessage") || lowerUrl.contains("search")) {
                continue
            }

            if (seen.add(absUrl)) {
                result.add(text to absUrl)
            }
        }
        return result
    }

    /**
     * 解析單一章節網頁 HTML 為乾淨的 ChapterContent（只含標題與正文段落，自動去除廣告與導航列）
     */
    fun parseChapter(html: String, pageUrl: String, fallbackTitle: String = ""): ChapterContent {
        val doc = Jsoup.parse(html, pageUrl)
        val host = WebAdBlocker.extractHost(pageUrl) ?: ""

        val title = extractChapterTitle(doc, host, fallbackTitle)
        val rawParagraphs = when {
            host.contains("piaotia") -> parsePiaotiaChapterBody(doc)
            host.contains("czbooks") -> parseCzbooksChapterBody(doc)
            host.contains("uukanshu") -> parseUukanshuChapterBody(doc)
            else -> emptyList()
        }.ifEmpty {
            parseGenericChapterBody(doc)
        }

        val cleanedParagraphs = rawParagraphs
            .map { cleanParagraphText(it) }
            .filter { it.isNotBlank() && !isAdParagraph(it) }

        return ChapterContent(
            title = title.ifBlank { fallbackTitle.ifBlank { "章節" } },
            bodyParagraphs = cleanedParagraphs,
            workRecords = emptyList(),
            rawMarkdown = ""
        )
    }

    private fun extractChapterTitle(doc: Document, host: String, fallbackTitle: String): String {
        val raw = when {
            host.contains("czbooks") -> doc.selectFirst("div.name, .chapter-detail .name, h1")?.text()
            host.contains("uukanshu") -> doc.selectFirst("h1.readTitle, .bookname h1, h1")?.text()
            host.contains("piaotia") -> {
                val h1 = doc.selectFirst("h1")?.clone()
                h1?.select("a")?.remove() // 飄天 H1 內會包著書名連結 <a href="...">書名</a>
                h1?.text()?.trim()?.ifBlank { doc.selectFirst("h1")?.text() }
            }
            else -> doc.selectFirst("h1, .chapter-title, .bookname h1")?.text()
        }?.trim()

        if (!raw.isNullOrBlank()) return raw
        if (fallbackTitle.isNotBlank()) return fallbackTitle
        return doc.title().substringBefore("_").substringBefore("-").trim().ifBlank { "章節" }
    }

    private fun parsePiaotiaChapterBody(doc: Document): List<String> {
        val explicit = doc.selectFirst("div#content, div.content")
        if (explicit != null) {
            return extractParagraphsFromElement(explicit)
        }

        // 飄天常見版型：正文直接寫在 H1 同層的 div.mainbody 或 body 內，夾在 .toplink / 廣告 table 與 .bottomlink 之間
        val h1 = doc.selectFirst("h1")
        val parent = h1?.parent()?.clone() ?: doc.body().clone()
        parent.select(
            "script, style, noscript, iframe, h1, table, center, " +
                    "#guild, #shop, .toplink, .bottomlink, #feit2, #Commenddiv, " +
                    "div[align=center], a, form"
        ).remove()
        return extractParagraphsFromElement(parent)
    }

    private fun parseCzbooksChapterBody(doc: Document): List<String> {
        val el = doc.selectFirst("div.content, .chapter-detail .content, #content") ?: return emptyList()
        val copy = el.clone()
        copy.select("script, style, noscript, iframe, .ad, .ads, ins").remove()
        return extractParagraphsFromElement(copy)
    }

    private fun parseUukanshuChapterBody(doc: Document): List<String> {
        val el = doc.selectFirst("div.readcotent, div.readcontent, div#content, div.bbb, div#chaptercontent")
            ?: return emptyList()
        val copy = el.clone()
        copy.select("script, style, noscript, iframe, .ad, .ads, ins, .readinline, a").remove()
        return extractParagraphsFromElement(copy)
    }

    private fun parseGenericChapterBody(doc: Document): List<String> {
        val preferredSelectors = listOf(
            "div#content", "div.content", "div.readcotent", "div.readcontent",
            "div#chaptercontent", "div.chapter-content", "div#booktxt", "div.txtnav",
            "div#txt", "div.txt", "div.showtxt", "div.yd_text2", "article"
        )
        for (sel in preferredSelectors) {
            val el = doc.selectFirst(sel)
            if (el != null) {
                val copy = el.clone()
                copy.select("script, style, noscript, iframe, nav, header, footer, form, .ad, .ads, ins").remove()
                val paras = extractParagraphsFromElement(copy)
                if (paras.sumOf { it.length } >= 80) {
                    return paras
                }
            }
        }

        // 啟發式：從整個文件找出文字密度最高的 div / article
        val bodyCopy = doc.body().clone()
        bodyCopy.select("script, style, noscript, iframe, nav, header, footer, form, aside, .toplink, .bottomlink, .ad, .ads").remove()

        var bestEl: Element? = null
        var bestScore = 0
        for (div in bodyCopy.select("div, article, section, main")) {
            val ownAndPText = div.ownText().length + div.children().filter { it.tagName() == "p" }.sumOf { it.text().length }
            val linkText = div.select("a").sumOf { it.text().length }
            val score = ownAndPText - linkText * 2
            if (score > bestScore) {
                bestScore = score
                bestEl = div
            }
        }
        return bestEl?.let { extractParagraphsFromElement(it) } ?: extractParagraphsFromElement(bodyCopy)
    }

    /**
     * 將 HTML 元素內的 <p>、<br>、文字節點轉成乾淨的段落清單
     */
    private fun extractParagraphsFromElement(element: Element): List<String> {
        val paragraphs = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            val raw = current.toString()
            current.clear()
            raw.lines().forEach { line ->
                val trimmed = line.replace('\u00A0', ' ').trim()
                if (trimmed.isNotEmpty()) {
                    paragraphs.add(trimmed)
                }
            }
        }

        fun walk(node: Node) {
            when (node) {
                is TextNode -> {
                    current.append(node.text())
                }
                is Element -> {
                    val tag = node.tagName().lowercase()
                    if (tag in setOf("script", "style", "noscript", "iframe", "ins", "button", "select", "input")) {
                        return
                    }
                    if (tag == "br") {
                        flush()
                        return
                    }
                    val isBlock = tag in setOf("p", "div", "li", "h2", "h3", "h4", "blockquote", "section", "article")
                    if (isBlock) flush()
                    for (child in node.childNodes()) {
                        walk(child)
                    }
                    if (isBlock) flush()
                }
            }
        }

        walk(element)
        flush()
        return paragraphs
    }

    private fun cleanParagraphText(raw: String): String {
        return raw
            .replace('\u00A0', ' ')
            .replace('\u3000', ' ')
            .trim()
    }

    private fun isAdParagraph(text: String): Boolean {
        if (text.isBlank()) return true
        return AD_TEXT_PATTERNS.any { it.containsMatchIn(text) }
    }

    /**
     * 將解析出的 WebBook 轉換為 Chapter 列表（配合本地快取目錄）
     */
    fun toChapters(parsedBook: ParsedWebBook, cacheDir: java.io.File): List<Chapter> {
        return parsedBook.chapters.map { ref ->
            val cacheFile = WebBookStore.getCachedChapterFile(cacheDir, ref.chapterNumber)
            Chapter(
                id = ref.url,
                volumeIndex = ref.volumeIndex,
                volumeName = ref.volumeName,
                chapterNumber = ref.chapterNumber,
                title = ref.title,
                fileUriString = if (cacheFile.exists()) "file://${cacheFile.absolutePath}" else null,
                remoteUrl = ref.url
            )
        }
    }
}
