package com.novel.reader.util

import com.novel.reader.model.Chapter
import com.novel.reader.model.ChapterContent
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class WebBookStoreTest {

    @Test
    fun testSaveLoadTocAndChapterCache() {
        val tempDir = Files.createTempDirectory("web_store_test").toFile()
        try {
            val chapters = listOf(
                Chapter(
                    id = "https://www.piaotia.com/html/3/3759/1.html",
                    volumeIndex = 1,
                    volumeName = "第一部",
                    chapterNumber = 1,
                    title = "第一章 火刑架",
                    remoteUrl = "https://www.piaotia.com/html/3/3759/1.html"
                ),
                Chapter(
                    id = "https://www.piaotia.com/html/3/3759/2.html",
                    volumeIndex = 1,
                    volumeName = "第一部",
                    chapterNumber = 2,
                    title = "第二章 覺醒",
                    remoteUrl = "https://www.piaotia.com/html/3/3759/2.html"
                )
            )

            WebBookStore.saveBook(
                bookDir = tempDir,
                title = "奧術神座",
                sourceUrl = "https://www.piaotia.com/html/3/3759/",
                preferWebView = false,
                chapters = chapters
            )

            val cacheFile = WebBookStore.getCachedChapterFile(tempDir, 1)
            WebBookStore.saveCachedChapter(
                cacheFile,
                ChapterContent(
                    title = "第一章 火刑架",
                    bodyParagraphs = listOf("段落一", "段落二"),
                    workRecords = emptyList(),
                    rawMarkdown = ""
                )
            )

            val loaded = WebBookStore.loadBook(tempDir)
            assertNotNull(loaded)
            assertEquals("奧術神座", loaded!!.title)
            assertEquals("https://www.piaotia.com/html/3/3759/", loaded.sourceUrl)
            assertFalse(loaded.preferWebView)
            assertEquals(2, loaded.chapters.size)
            assertNotNull(loaded.chapters[0].fileUriString)
            assertNull(loaded.chapters[1].fileUriString)

            val cachedContent = WebBookStore.readCachedChapter(cacheFile)
            assertNotNull(cachedContent)
            assertEquals("第一章 火刑架", cachedContent!!.title)
            assertEquals(listOf("段落一", "段落二"), cachedContent.bodyParagraphs)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
