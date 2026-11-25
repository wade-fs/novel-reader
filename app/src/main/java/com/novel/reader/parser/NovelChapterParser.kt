package com.novel.reader.parser

import com.novel.reader.model.*

object NovelChapterParser {

    private val TITLE_REGEX = Regex("""^#\s+(.+)$""")
    private val HORIZONTAL_RULE_REGEX = Regex("""^[-*_]{3,}\s*$""")

    // 匹配真正的章後工作紀錄大標題或分類標題（絕不將純分隔線 --- 誤判為工作紀錄）
    private val WORK_RECORD_HEADING_REGEX = Regex(
        """^#{1,6}\s*.*(工作紀錄|工作记录|章後紀錄|章后记录|考據與工作|考据与工作|寫作紀錄|写作记录|創作紀錄|创作记录).*$"""
    )
    private val WORK_RECORD_CATEGORY_HEADING_REGEX = Regex(
        """^#{1,6}\s*【?(史實骨架|史實依據|史实骨架|史实依据|待核事項|待核事项|合理推演|小說虛構|小说虚构|連續性變更|连续性变更|後續銜接|后续衔接)】?[:：]?\s*$"""
    )
    private val WORK_RECORD_BULLET_OR_BOLD_REGEX = Regex(
        """^(?:[-*]\s+)?\*{0,2}【(?:章後工作紀錄|本章工作紀錄|工作紀錄|工作记录|章後紀錄|章后记录|史實骨架|史實依據|史实骨架|史实依据|待核事項|待核事项|合理推演|小說虛構|小说虚构|連續性變更|连续性变更|後續銜接|后续衔接)】[:：]?\*{0,2}(?:[:：].*)?$"""
    )
    private val WORK_RECORD_BOLD_TITLE_REGEX = Regex(
        """^\*{2}【?(?:章後工作紀錄|本章工作紀錄|工作紀錄|工作记录|章後紀錄|章后记录|歷史考據與工作紀錄|历史考据与工作记录)】?[:：]?\*{2}\s*$"""
    )
    private val BODY_SUBHEADING_REGEX = Regex("""^#{2,6}\s+(.+)$""")

    /**
     * 判斷該行是否為章後工作紀錄區塊的起點
     */
    fun isWorkRecordStart(trimmedLine: String): Boolean {
        if (trimmedLine.isEmpty()) return false
        return WORK_RECORD_HEADING_REGEX.matches(trimmedLine) ||
                WORK_RECORD_CATEGORY_HEADING_REGEX.matches(trimmedLine) ||
                WORK_RECORD_BULLET_OR_BOLD_REGEX.matches(trimmedLine) ||
                WORK_RECORD_BOLD_TITLE_REGEX.matches(trimmedLine)
    }

    /**
     * 解析 Markdown 文章全文為結構化的 ChapterContent。
     * 所有章後工作紀錄一律過濾不顯示、不朗讀；正文中的場景分隔線 (--- / ***) 僅作為段落分隔，不影響後續正文。
     */
    fun parse(rawMarkdown: String): ChapterContent {
        val lines = rawMarkdown.lines()
        var title = ""
        val bodyParagraphs = mutableListOf<String>()
        val currentParagraphBuilder = StringBuilder()

        fun flushParagraph() {
            if (currentParagraphBuilder.isNotBlank()) {
                val rawPara = currentParagraphBuilder.toString().trim()
                val cleaned = cleanMarkdownSymbols(rawPara)
                if (cleaned.isNotBlank()) {
                    bodyParagraphs.add(cleaned)
                }
                currentParagraphBuilder.clear()
            }
        }

        for (line in lines) {
            val trimmed = line.trim()

            // 1. 識別章節主標題 (# 第一章 ...)
            if (title.isEmpty() && trimmed.startsWith("# ")) {
                val match = TITLE_REGEX.find(trimmed)
                title = cleanMarkdownSymbols(match?.groupValues?.get(1)?.trim() ?: trimmed.removePrefix("#").trim())
                continue
            }

            // 2. 遇到真正的章後工作紀錄區塊：一律濾除後續所有工作紀錄內容
            if (isWorkRecordStart(trimmed)) {
                flushParagraph()
                break
            }

            // 3. 正文中的場景分隔線 (--- / *** / ___)：僅結束當前段落，不截斷後續正文
            if (HORIZONTAL_RULE_REGEX.matches(trimmed)) {
                flushParagraph()
                continue
            }

            // 4. 空行：結束當前段落
            if (trimmed.isEmpty()) {
                flushParagraph()
                continue
            }

            // 5. 正文內部的章節小標 (如 ### 二、靈脈枯竭，青雲之殤)：獨立成段並清除 # 符號
            val subheadingMatch = BODY_SUBHEADING_REGEX.find(trimmed)
            if (subheadingMatch != null) {
                flushParagraph()
                val cleanedSub = cleanMarkdownSymbols(subheadingMatch.groupValues[1])
                if (cleanedSub.isNotBlank()) {
                    bodyParagraphs.add(cleanedSub)
                }
                continue
            }

            // 6. 一般正文段落
            if (currentParagraphBuilder.isNotEmpty()) {
                currentParagraphBuilder.append("\n")
            }
            currentParagraphBuilder.append(trimmed)
        }

        // 收尾正文最後一段
        flushParagraph()

        return ChapterContent(
            title = title.ifEmpty { "章節" },
            bodyParagraphs = bodyParagraphs,
            workRecords = emptyList(),
            rawMarkdown = rawMarkdown
        )
    }

    /**
     * 依照使用者的 ReadingFilterConfig 生成 TTS 朗讀單元序列。
     * 一律只讀本文（可選是否朗讀章名），完全不包含工作紀錄。
     */
    fun buildTtsSegments(
        chapterContent: ChapterContent,
        filterConfig: ReadingFilterConfig
    ): List<TtsSegment> {
        val segments = mutableListOf<TtsSegment>()
        var uiPos = 0

        // 1. 章名
        if (filterConfig.readChapterTitle && chapterContent.title.isNotBlank()) {
            val cleanTitle = cleanMarkdownSymbols(chapterContent.title)
            val speechTitle = cleanSpeechText(cleanTitle)
            if (speechTitle.isNotBlank()) {
                segments.add(
                    TtsSegment(
                        id = "title",
                        type = SegmentType.CHAPTER_TITLE,
                        displayText = chapterContent.title,
                        speechText = speechTitle,
                        uiPosition = uiPos
                    )
                )
            }
        }
        uiPos++ // 章名佔據 UI 位置 0

        // 2. 正文各段落（一律朗讀本文）
        chapterContent.bodyParagraphs.forEachIndexed { index, paragraph ->
            if (paragraph.isNotBlank()) {
                val cleanPara = cleanMarkdownSymbols(paragraph)
                val speechPara = cleanSpeechText(cleanPara)
                if (speechPara.isNotBlank()) {
                    segments.add(
                        TtsSegment(
                            id = "body_$index",
                            type = SegmentType.BODY_PARAGRAPH,
                            displayText = paragraph,
                            speechText = speechPara,
                            uiPosition = uiPos
                        )
                    )
                }
            }
            uiPos++
        }

        return segments
    }

    /**
     * 清理 Markdown 特殊符號 (如粗體 **、斜體 *、反引號 `、圖片等)
     */
    fun cleanMarkdownSymbols(text: String): String {
        return text
            .replace(Regex("""!\[.*?\]\(.*?\)"""), "")
            .replace("**", "")
            .replace("*", "")
            .replace("`", "")
            .replace(Regex("""^#+\s*""", RegexOption.MULTILINE), "")
            .trim()
    }

    /**
     * 清理用語音發音的字元：
     * 移除特殊括號符號以使發音流暢，移除圖片標籤，保留正常停頓逗點句號
     */
    fun cleanSpeechText(text: String): String {
        return text
            .replace(Regex("""!\[.*?\]\(.*?\)"""), "")
            .replace(Regex("""\[(.*?)\]\(.*?\)"""), "$1")
            .replace("【", "")
            .replace("】", "：")
            .replace("──", "，")
            .replace("——", "，")
            .replace("……", "。")
            .replace("…", "。")
            .replace(Regex("""[#*`~>]"""), "")
            .trim()
    }
}
