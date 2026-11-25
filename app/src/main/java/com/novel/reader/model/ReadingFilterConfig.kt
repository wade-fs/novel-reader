package com.novel.reader.model

/**
 * 朗讀過濾設定：
 * 一律只朗讀小說本文（章後工作紀錄已完全濾除），可選擇是否朗讀章節標題。
 */
data class ReadingFilterConfig(
    var readChapterTitle: Boolean = true
) {
    /**
     * 模式：只讀正文 (不讀章名)
     */
    fun setBodyOnlyMode() {
        readChapterTitle = false
    }

    /**
     * 模式：讀章名 + 正文 (預設)
     */
    fun setTitleAndBodyMode() {
        readChapterTitle = true
    }

    /**
     * 判斷當前是否為「只讀正文」
     */
    val isBodyOnly: Boolean
        get() = !readChapterTitle

    /**
     * 判斷當前是否為「讀章名 + 正文」
     */
    val isTitleAndBody: Boolean
        get() = readChapterTitle
}
