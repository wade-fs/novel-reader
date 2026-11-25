package com.novel.reader.repository

import com.novel.reader.parser.NovelChapterParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NovelRepositoryIntegrationTest {

    private val novelRootDir = File("/home/wade/src/github/novel")

    private fun scanLocalNovel(novelDir: File): List<Pair<Int, String>> {
        val chaptersDir = if (File(novelDir, "chapters").isDirectory) File(novelDir, "chapters") else novelDir
        val result = mutableListOf<Pair<Int, String>>()

        fun scan(file: File) {
            if (file.isDirectory) {
                file.listFiles()?.forEach { scan(it) }
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

                var title = ""
                file.bufferedReader(Charsets.UTF_8).use { r ->
                    var line: String?
                    while (r.readLine().also { line = it } != null) {
                        val trimmed = line!!.trim()
                        if (trimmed.startsWith("# ")) {
                            title = NovelChapterParser.cleanMarkdownSymbols(trimmed.removePrefix("#").trim())
                            break
                        }
                    }
                }
                if (title.isEmpty()) title = "第 $num 章"
                result.add(num to title)
            }
        }

        scan(chaptersDir)
        result.sortBy { it.first }
        return result
    }

    @Test
    fun testYuanChapters() {
        val yuanDir = File(novelRootDir, "yuan")
        if (!yuanDir.exists()) return
        val chapters = scanLocalNovel(yuanDir)
        assertEquals(540, chapters.size)
        assertEquals(1, chapters.first().first)
        assertEquals(540, chapters.last().first)
        assertTrue(chapters.first().second.contains("上都煙塵"))
    }

    @Test
    fun testSongChapters() {
        val songDir = File(novelRootDir, "song")
        if (!songDir.exists()) return
        val chapters = scanLocalNovel(songDir)
        assertEquals(720, chapters.size)
        assertEquals(1, chapters.first().first)
        assertEquals(720, chapters.last().first)
        assertTrue(chapters.first().second.contains("虹橋清晨"))
        assertTrue(chapters.last().second.contains("日月重開"))
    }

    @Test
    fun testSexChapters() {
        val sexDir = File(novelRootDir, "sex")
        if (!sexDir.exists()) return
        val chapters = scanLocalNovel(sexDir)
        assertEquals(88, chapters.size)
        assertEquals(1, chapters.first().first)
        assertEquals(88, chapters.last().first)
        assertTrue(chapters.first().second.contains("宿慧登樓"))
        assertTrue(chapters[15].second.contains("雷霆滌城"))
        assertTrue(chapters[16].second.contains("穿門踏虛"))
        assertTrue(chapters[51].second.contains("雪峰定錨"))
        assertTrue(chapters[52].second.contains("演武初試"))
        assertTrue(chapters.last().second.contains("九曜起程"))
    }

    @Test
    fun testMingChapters() {
        val mingDir = File(novelRootDir, "ming")
        if (!mingDir.exists()) return
        val chapters = scanLocalNovel(mingDir)
        assertEquals(1, chapters.size)
        assertEquals(1, chapters.first().first)
        assertTrue(chapters.first().second.contains("乾清宮密霧"))
    }
}
