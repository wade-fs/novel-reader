package com.novel.reader.parser

import com.novel.reader.model.ReadingFilterConfig
import org.junit.Assert.*
import org.junit.Test

class NovelChapterParserTest {

    private val sampleMarkdownWithWorkRecord = """
# 第一章　上都煙塵

草原上的七月，日頭落得極慢。

近黃昏時，金蓮川的草浪泛起一層焦乾的鐵銹色。

---

## 章後工作紀錄

- **【史實骨架】：** 
  - 《元史·泰定帝本紀》：泰定五年（致和元年，1328）秋七月庚午，帝崩於上都行宮。
  - 《元史·燕帖木兒傳》：泰定帝崩，中書左丞相倒剌沙等封鎖消息。
- **【合理推演】：** 
  - 泰定帝七月暴崩於上都行宮後，中書左丞相倒剌沙為控制局勢切斷通信。
- **【待核事項】：** 
  - 上都開平府至野狐嶺間各驛站於致和元年的精確驛名。
    """.trimIndent()

    @Test
    fun testParseTitleAndBodyFiltersOutWorkRecords() {
        val chapter = NovelChapterParser.parse(sampleMarkdownWithWorkRecord)
        assertEquals("第一章　上都煙塵", chapter.title)
        assertEquals(2, chapter.bodyParagraphs.size)
        assertEquals("草原上的七月，日頭落得極慢。", chapter.bodyParagraphs[0])
        assertEquals("近黃昏時，金蓮川的草浪泛起一層焦乾的鐵銹色。", chapter.bodyParagraphs[1])
        assertTrue("工作紀錄應完全濾除不保留", chapter.workRecords.isEmpty())
    }

    @Test
    fun testSceneDividerDoesNotTriggerWorkRecord() {
        val storyWithDividers = """
# 第一章　宿慧登樓，青雲危機

「遵命！」

---

### 二、靈脈枯竭，青雲之殤

青玄界，東華靈嶽。

***

後山禁地，斷崖殘石之畔。
        """.trimIndent()

        val chapter = NovelChapterParser.parse(storyWithDividers)
        assertEquals("第一章　宿慧登樓，青雲危機", chapter.title)
        assertTrue(chapter.workRecords.isEmpty())
        assertEquals(4, chapter.bodyParagraphs.size)
        assertEquals("「遵命！」", chapter.bodyParagraphs[0])
        assertEquals("二、靈脈枯竭，青雲之殤", chapter.bodyParagraphs[1])
        assertEquals("青玄界，東華靈嶽。", chapter.bodyParagraphs[2])
        assertEquals("後山禁地，斷崖殘石之畔。", chapter.bodyParagraphs[3])

        val segments = NovelChapterParser.buildTtsSegments(chapter, ReadingFilterConfig())
        val allText = segments.joinToString(" ") { it.displayText }
        assertFalse(allText.contains("章後工作紀錄"))
        assertFalse(allText.contains("工作紀錄"))
        assertTrue(allText.contains("靈脈枯竭"))
        assertTrue(allText.contains("斷崖殘石"))
    }

    @Test
    fun testAllWorkRecordHeaderVariantsAreStripped() {
        val variants = listOf(
            "## 章後工作紀錄",
            "### 章後工作紀錄",
            "## 【本章工作紀錄】",
            "### 【工作紀錄】",
            "### 歷史考據與工作紀錄",
            "### 【史實骨架】",
            "#### 【史實骨架】",
            "#### 史實依據",
            "- **【史實骨架】：** 測試內容",
            "**【史實骨架】**"
        )
        for (header in variants) {
            val md = """
# 第十章　風雲

這是正文第一段。

---

$header
這裡是不應該被顯示或朗讀的工作紀錄內容。
            """.trimIndent()

            val parsed = NovelChapterParser.parse(md)
            assertEquals("Variant failed: $header", 1, parsed.bodyParagraphs.size)
            assertEquals("這是正文第一段。", parsed.bodyParagraphs[0])
            assertTrue("Variant kept workRecords: $header", parsed.workRecords.isEmpty())
        }
    }

    @Test
    fun testDefaultTitleAndBodySegmentsNeverIncludeWorkRecords() {
        val chapter = NovelChapterParser.parse(sampleMarkdownWithWorkRecord)
        val config = ReadingFilterConfig() // default: title + body
        val segments = NovelChapterParser.buildTtsSegments(chapter, config)

        assertEquals(3, segments.size)
        assertEquals("第一章　上都煙塵", segments[0].displayText)
        assertEquals("草原上的七月，日頭落得極慢。", segments[1].displayText)
        assertEquals("近黃昏時，金蓮川的草浪泛起一層焦乾的鐵銹色。", segments[2].displayText)
    }

    @Test
    fun testBodyOnlyModeSegments() {
        val chapter = NovelChapterParser.parse(sampleMarkdownWithWorkRecord)
        val config = ReadingFilterConfig().apply { setBodyOnlyMode() }
        val segments = NovelChapterParser.buildTtsSegments(chapter, config)

        assertEquals(2, segments.size)
        assertEquals("草原上的七月，日頭落得極慢。", segments[0].displayText)
        assertEquals("近黃昏時，金蓮川的草浪泛起一層焦乾的鐵銹色。", segments[1].displayText)
    }

    @Test
    fun testCleanSpeechTextRemovesImagesAndLinks() {
        val rawWithImage = "天武城外。![滄瀾界風雲](../../images/02017.jpeg)滿場數萬名武者[天劍宗](https://example.com)齊聲歡呼。"
        val cleaned = NovelChapterParser.cleanSpeechText(rawWithImage)
        assertFalse(cleaned.contains("!["))
        assertFalse(cleaned.contains(".jpeg"))
        assertFalse(cleaned.contains("https://"))
        assertTrue(cleaned.contains("天武城外。"))
        assertTrue(cleaned.contains("天劍宗"))
        assertTrue(cleaned.contains("齊聲歡呼。"))
    }

    @Test
    fun testParseChapterWithMarkdownImageInBody() {
        val md = """
            # 第十七章　穿門踏虛，初履滄瀾

            ![滄瀾界風雲](../../images/02017.jpeg)

            萬界樓第二層的核心殿堂之內，一座古老高聳的青銅門扉正泛著銀藍色星漩。
        """.trimIndent()

        val parsed = NovelChapterParser.parse(md)
        assertEquals("第十七章　穿門踏虛，初履滄瀾", parsed.title)
        assertEquals(1, parsed.bodyParagraphs.size)
        val segments = NovelChapterParser.buildTtsSegments(parsed, ReadingFilterConfig())
        val speech = segments.joinToString(" ") { it.speechText }
        assertFalse(speech.contains(".jpeg"))
        assertTrue(speech.contains("萬界樓第二層"))
    }
}
