package com.novel.reader.util

import org.junit.Assert.*
import org.junit.Test

class WebAdBlockerTest {

    @Test
    fun testBlocksKnownAdDomainsAndScripts() {
        assertTrue(WebAdBlocker.isAdUrl("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
        assertTrue(WebAdBlocker.isAdUrl("https://googleads.g.doubleclick.net/pagead/ads"))
        assertTrue(WebAdBlocker.isAdUrl("https://www.piaotia.com/scripts/read/style12.js"))
        assertTrue(WebAdBlocker.isAdUrl("https://www.piaotia.com/scripts/readindex/tongji.js"))
        assertTrue(WebAdBlocker.isAdUrl("https://cdn.popads.net/pop.js"))
    }

    @Test
    fun testAllowsNovelPagesAndCloudflareChallenge() {
        assertFalse(WebAdBlocker.isAdUrl("https://www.piaotia.com/html/3/3759/1881421.html"))
        assertFalse(WebAdBlocker.isAdUrl("https://czbooks.net/n/ucpije"))
        assertFalse(WebAdBlocker.isAdUrl("https://uukanshu.cc/book/27525/"))
        assertFalse(WebAdBlocker.isAdUrl("https://challenges.cloudflare.com/turnstile/v0/api.js"))
    }

    @Test
    fun testNavigationWhitelistBlocksRedirectsAppsAndApks() {
        assertTrue(WebAdBlocker.isAllowedNavigation("https://czbooks.net/n/ucpije/abc", "czbooks.net"))
        assertTrue(WebAdBlocker.isAllowedNavigation("https://m.czbooks.net/n/ucpije", "czbooks.net"))
        assertTrue(WebAdBlocker.isAllowedNavigation("https://challenges.cloudflare.com/cdn-cgi/l", "czbooks.net"))

        assertFalse(WebAdBlocker.isAllowedNavigation("https://evil-casino-ads.example.com/offer", "czbooks.net"))
        assertFalse(WebAdBlocker.isAllowedNavigation("intent://open#Intent;scheme=app;end", "czbooks.net"))
        assertFalse(WebAdBlocker.isAllowedNavigation("market://details?id=com.spam", "czbooks.net"))
        assertFalse(WebAdBlocker.isAllowedNavigation("https://czbooks.net/download/fake.apk", "czbooks.net"))
    }
}
