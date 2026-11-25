package com.novel.reader.util

import java.net.URI

/**
 * WebView 廣告與惡意跳轉過濾器：
 * 1. 封鎖常見廣告聯盟、彈窗腳本、追蹤統計網域
 * 2. 限制主頁面導航僅允許目標小說網域與 Cloudflare 驗證網域，攔截外部跳轉與 App 拉起
 */
object WebAdBlocker {

    private val AD_HOST_KEYWORDS = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "googletagservices.com",
        "googletagmanager.com",
        "google-analytics.com",
        "adservice.google",
        "pagead2.",
        "pos.baidu.com",
        "cpro.baidu.com",
        "hm.baidu.com",
        "dup.baidustatic.com",
        "tanx.com",
        "alimama.com",
        "mmstat.com",
        "cnzz.com",
        "51.la",
        "umeng.com",
        "taboola.com",
        "outbrain.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "exoclick.com",
        "adsterra.com",
        "highrevenuegate.com",
        "profitablegate",
        "juicyads.com",
        "mgid.com",
        "adnxs.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "criteo.com",
        "criteo.net",
        "amazon-adsystem.com",
        "adsrvr.org",
        "adsafeprotected.com",
        "moatads.com",
        "scorecardresearch.com",
        "quantserve.com",
        "histats.com",
        "statcounter.com",
        "trafficjunky.",
        "tsyndicate.com",
        "realsrv.com",
        "onclickads",
        "onclkds",
        "adskeeper",
        "adblade",
        "bidvertiser",
        "revcontent",
        "yandex.ru/ads",
        "acint.net",
        "clarity.ms",
        "hotjar.com",
        "facebook.net"
    )

    private val AD_PATH_PATTERNS = listOf(
        Regex("""/scripts/read/style\d+\.js""", RegexOption.IGNORE_CASE),
        Regex("""/scripts/readindex/tongji\.js""", RegexOption.IGNORE_CASE),
        Regex("""/ads?(/|\.|_)""", RegexOption.IGNORE_CASE),
        Regex("""/adv(/|\.|_)""", RegexOption.IGNORE_CASE),
        Regex("""/adserver""", RegexOption.IGNORE_CASE),
        Regex("""/popunder""", RegexOption.IGNORE_CASE),
        Regex("""/popup[_-]?ad""", RegexOption.IGNORE_CASE),
        Regex("""/banner[_-]?ad""", RegexOption.IGNORE_CASE),
        Regex("""/tongji\.js""", RegexOption.IGNORE_CASE),
        Regex("""/adsbygoogle""", RegexOption.IGNORE_CASE)
    )

    private val CHALLENGE_ALLOWED_SUFFIXES = listOf(
        "cloudflare.com",
        "challenges.cloudflare.com",
        "hcaptcha.com",
        "recaptcha.net",
        "gstatic.com"
    )

    /**
     * 判斷資源請求 URL 是否為廣告或追蹤腳本
     */
    fun isAdUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        if (lower.isEmpty()) return false
        if (lower.startsWith("data:") || lower.startsWith("about:")) return false

        val host = extractHost(lower) ?: return false

        // 絕不攔截 Cloudflare 驗證相關資源，否則驗證無法載入
        if (isChallengeHost(host)) return false

        if (AD_HOST_KEYWORDS.any { host == it || host.endsWith(".$it") || host.contains(it) }) {
            return true
        }

        val path = try {
            URI(lower).rawPath ?: ""
        } catch (_: Exception) {
            lower
        }

        if (AD_PATH_PATTERNS.any { it.containsMatchIn(path) }) {
            return true
        }

        return false
    }

    /**
     * 判斷主頁面導航是否允許（封鎖外部跳轉廣告、intent:// 拉起其他 App、APK 下載）
     */
    fun isAllowedNavigation(targetUrl: String, allowedBookHost: String): Boolean {
        val trimmed = targetUrl.trim()
        val lower = trimmed.lowercase()

        // 僅允許 http / https，直接擋掉 intent://, market://, tel:, sms:, javascript: 等
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false
        }

        // 封鎖直接下載 APK 檔案
        if (lower.substringBefore("?").endsWith(".apk")) {
            return false
        }

        // 封鎖已知廣告網域
        if (isAdUrl(trimmed)) {
            return false
        }

        val targetHost = extractHost(lower) ?: return false
        val bookHost = extractHost(allowedBookHost.lowercase()) ?: allowedBookHost.lowercase()

        if (isChallengeHost(targetHost)) return true
        if (bookHost.isBlank()) return true

        val targetBase = extractRegistrableDomain(targetHost)
        val bookBase = extractRegistrableDomain(bookHost)
        return targetHost == bookHost || targetBase == bookBase
    }

    fun isChallengeHost(host: String): Boolean {
        val lower = host.lowercase()
        return CHALLENGE_ALLOWED_SUFFIXES.any { lower == it || lower.endsWith(".$it") }
    }

    fun extractHost(urlOrHost: String): String? {
        val trimmed = urlOrHost.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val uri = if (trimmed.contains("://")) URI(trimmed) else URI("https://$trimmed")
            uri.host?.lowercase()
        } catch (_: Exception) {
            null
        }
    }

    private fun extractRegistrableDomain(host: String): String {
        val parts = host.split('.').filter { it.isNotBlank() }
        if (parts.size <= 2) return host
        val secondLast = parts[parts.size - 2]
        val multiPartTlds = setOf("com", "net", "org", "co", "gov", "edu", "idv")
        return if (secondLast in multiPartTlds && parts.size >= 3) {
            parts.takeLast(3).joinToString(".")
        } else {
            parts.takeLast(2).joinToString(".")
        }
    }
}
