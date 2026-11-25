package com.novel.reader.util

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.Charset

class WebNovelParserTest {

    @Test
    fun testIsWebNovelUrlDistinguishesFromGitHub() {
        assertTrue(WebNovelParser.isWebNovelUrl("https://www.piaotia.com/html/3/3759/"))
        assertTrue(WebNovelParser.isWebNovelUrl("https://czbooks.net/n/ucpije"))
        assertTrue(WebNovelParser.isWebNovelUrl("https://uukanshu.cc/book/27525/"))
        assertFalse(WebNovelParser.isWebNovelUrl("https://github.com/wade-fs/novel/tree/main/yuan"))
        assertFalse(WebNovelParser.isWebNovelUrl("not a url"))
    }

    @Test
    fun testDetectCloudflareChallengePage() {
        val cfChallenge = "<!DOCTYPE html><html><head><title>Just a moment...</title></head><body></body></html>"
        val cfBlock = "<!DOCTYPE html><html><head><title>Attention Required! | Cloudflare</title></head><body></body></html>"
        val normal = "<!DOCTYPE html><html><head><title>奧術神座</title></head><body><h1>奧術神座</h1></body></html>"

        assertTrue(WebNovelParser.isChallengePage(cfChallenge, 403))
        assertTrue(WebNovelParser.isChallengePage(cfBlock, 403))
        assertFalse(WebNovelParser.isChallengePage(normal, 200))
    }

    @Test
    fun testDecodeGbkHtmlBytes() {
        val gbkHtml = """
            <html><head><meta http-equiv="Content-Type" content="text/html; charset=gbk" />
            <title>奥术神座</title></head><body>正文测试</body></html>
        """.trimIndent()
        val gbkBytes = gbkHtml.toByteArray(Charset.forName("GB18030"))
        val decoded = WebNovelParser.decodeHtmlBytes(gbkBytes, "text/html")
        assertTrue(decoded.contains("奥术神座"))
        assertTrue(decoded.contains("正文测试"))
    }

    @Test
    fun testParsePiaotiaTocAndChapter() {
        val tocHtml = """
            <html><head>
              <title>奥术神座最新章节,奥术神座无弹窗全文阅读_飘天文学</title>
              <meta name="author" content="爱潜水的乌贼" />
              <meta name="description" content="知识就是力量。" />
            </head>
            <body>
              <h1>奥术神座最新章节</h1>
              <div class="centent">
                <div class="list">第一部 圣咏之城卷</div>
                <ul>
                  <li><a href="1881421.html">第一章 燃烧的火刑架</a></li>
                  <li><a href="1881422.html">第二章 随自己而来的</a></li>
                </ul>
              </div>
            </body></html>
        """.trimIndent()

        val book = WebNovelParser.parseToc(tocHtml, "https://www.piaotia.com/html/3/3759/")
        assertEquals("奥术神座", book.title)
        assertEquals("爱潜水的乌贼", book.metadata.author)
        assertEquals("知识就是力量。", book.metadata.description)
        assertEquals(2, book.chapters.size)
        assertEquals("第一章 燃烧的火刑架", book.chapters[0].title)
        assertEquals("https://www.piaotia.com/html/3/3759/1881421.html", book.chapters[0].url)
        assertEquals("第一部 圣咏之城卷", book.chapters[0].volumeName)

        val chapterHtml = """
            <html><body>
              <div class="mainbody">
                <h1><a href="https://www.piaotia.com/bookinfo/3/3759.html">奥术神座</a> 第一章 燃烧的火刑架</h1>
                <div class="toplink"><a href="index.html">上一章</a><a href="1881422.html">下一章</a></div>
                <table><tr><td><script src="/scripts/read/style12.js"></script></td></tr></table>
                <br>
                &nbsp;&nbsp;&nbsp;&nbsp;浓烟滚滚，夏风的意识很快就模糊起来。<br /><br />
                &nbsp;&nbsp;&nbsp;&nbsp;“清醒，必须清醒！”<br /><br />
                &nbsp;&nbsp;&nbsp;&nbsp;;<br /><br />
                &nbsp;&nbsp;&nbsp;&nbsp;{飘天文学<a href="https://www.piaotia.com">www.piaotia.com</a>感谢各位书友的支持，您的支持就是我们最大的动力}
                <div class="bottomlink"><a href="1881422.html">下一章</a></div>
              </div>
            </body></html>
        """.trimIndent()

        val chapter = WebNovelParser.parseChapter(chapterHtml, "https://www.piaotia.com/html/3/3759/1881421.html")
        assertEquals("第一章 燃烧的火刑架", chapter.title)
        assertEquals(2, chapter.bodyParagraphs.size)
        assertEquals("浓烟滚滚，夏风的意识很快就模糊起来。", chapter.bodyParagraphs[0])
        assertEquals("“清醒，必须清醒！”", chapter.bodyParagraphs[1])
    }

    @Test
    fun testParseCzbooksTocAndChapter() {
        val tocHtml = """
            <html><body>
              <div class="info"><span class="title">《測試仙俠傳》</span></div>
              <ul class="chapter-list">
                <li class="volume">第一卷 初入江湖</li>
                <li><a href="//czbooks.net/n/ucpije/c1">第一章 少年出山</a></li>
                <li><a href="//czbooks.net/n/ucpije/c2">第二章 劍氣如霜</a></li>
              </ul>
            </body></html>
        """.trimIndent()

        val book = WebNovelParser.parseToc(tocHtml, "https://czbooks.net/n/ucpije")
        assertEquals("測試仙俠傳", book.title)
        assertEquals(2, book.chapters.size)
        assertEquals("https://czbooks.net/n/ucpije/c1", book.chapters[0].url)
        assertEquals("第一卷 初入江湖", book.chapters[0].volumeName)

        val chapterHtml = """
            <html><body>
              <div class="chapter-detail">
                <div class="name">第一章 少年出山</div>
                <div class="content">
                  晨霧漫過山谷，青石階微涼。
                  <br><br>
                  少年背起長劍，頭也不回地走下山道。
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val chapter = WebNovelParser.parseChapter(chapterHtml, "https://czbooks.net/n/ucpije/c1")
        assertEquals("第一章 少年出山", chapter.title)
        assertEquals(2, chapter.bodyParagraphs.size)
        assertTrue(chapter.bodyParagraphs[0].contains("晨霧漫過山谷"))
        assertTrue(chapter.bodyParagraphs[1].contains("少年背起長劍"))
    }

    @Test
    fun testParseUukanshuTocAndChapter() {
        val tocHtml = """
            <html><body>
              <h1 class="booktitle">星海紀元</h1>
              <dl id="list-chapterAll" class="chapterlist">
                <dt>正文卷</dt>
                <dd><a href="/book/27525/101.html">第1章 啟航</a></dd>
                <dd><a href="/book/27525/102.html">第2章 星門</a></dd>
              </dl>
            </body></html>
        """.trimIndent()

        val book = WebNovelParser.parseToc(tocHtml, "https://uukanshu.cc/book/27525/")
        assertEquals("星海紀元", book.title)
        assertEquals(2, book.chapters.size)
        assertEquals("https://uukanshu.cc/book/27525/101.html", book.chapters[0].url)

        val chapterHtml = """
            <html><body>
              <h1 class="readTitle">第1章 啟航</h1>
              <div class="readcotent">
                <p>星艦引擎發出低沉轟鳴。</p>
                <p>艦橋上的星圖緩緩展開。</p>
                <p>請收藏本站 www.uukanshu.cc 更新速度全網最快</p>
              </div>
            </body></html>
        """.trimIndent()

        val chapter = WebNovelParser.parseChapter(chapterHtml, "https://uukanshu.cc/book/27525/101.html")
        assertEquals("第1章 啟航", chapter.title)
        assertEquals(2, chapter.bodyParagraphs.size)
        assertEquals("星艦引擎發出低沉轟鳴。", chapter.bodyParagraphs[0])
        assertEquals("艦橋上的星圖緩緩展開。", chapter.bodyParagraphs[1])
    }
}
