package com.novel.reader.util

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BookMetadataParserTest {

    @Test
    fun testParseProjectNameAndIgnoreNestedVolumeName() {
        val yaml = """
            project:
              id: sex
              name: 萬界風月樓
              subtitle: 諸天萬界爽文與風月傳奇

            scale:
              total_volumes: 3
              volumes:
                - volume: 1
                  name: 第一卷 仙門歸來・青玄風雲
        """.trimIndent()

        assertEquals("萬界風月樓", BookMetadataParser.parseTitle(yaml))
    }

    @Test
    fun testParseQuotedTitleAndComments() {
        val yaml = """
            # 測試註解
            book:
              title: "大明風華" # 行內註解
              author: 測試
        """.trimIndent()

        assertEquals("大明風華", BookMetadataParser.parseTitle(yaml))
    }

    @Test
    fun testParseTopLevelTitle() {
        val yaml = """
            title: '孤城殘照'
            language: zh-TW
        """.trimIndent()

        assertEquals("孤城殘照", BookMetadataParser.parseTitle(yaml))
    }

    @Test
    fun testFindTitleInDirectoryAndNestedSubfolder() {
        val tempDir = Files.createTempDirectory("book_meta_test").toFile()
        try {
            val nested = File(tempDir, "novel-main")
            nested.mkdirs()
            File(nested, "book.yaml").writeText(
                """
                project:
                  id: yuan
                  name: 元末明初歷史群像小說
                """.trimIndent(),
                Charsets.UTF_8
            )
            assertEquals("元末明初歷史群像小說", BookMetadataParser.findTitleInDirectory(tempDir))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testStripFileExtension() {
        assertEquals("my_novel", BookMetadataParser.stripFileExtension("my_novel.zip"))
        assertEquals("archive_book", BookMetadataParser.stripFileExtension("archive_book.tar.gz"))
        assertEquals("archive_book", BookMetadataParser.stripFileExtension("archive_book.tgz"))
        assertEquals("chap001", BookMetadataParser.stripFileExtension("chapters/chap001.md"))
        assertEquals("story", BookMetadataParser.stripFileExtension("story.txt"))
    }

    @Test
    fun testRealNovelRepoBookYamls() {
        val novelRoot = File("/home/wade/src/github/novel")
        if (!novelRoot.exists()) return
        val expected = mapOf(
            "yuan" to "元末明初歷史群像小說",
            "song" to "宋末",
            "tang" to "唐末",
            "sex" to "萬界風月樓",
            "ching" to "天下易主",
            "ming" to "明末清初歷史小說"
        )
        for ((dirName, expectedTitle) in expected) {
            val dir = File(novelRoot, dirName)
            if (dir.exists()) {
                assertEquals(expectedTitle, BookMetadataParser.findTitleInDirectory(dir))
            }
        }
    }
}
