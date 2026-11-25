package com.novel.reader.model

/**
 * 小說閱讀器運作環境狀態
 * 依據螢幕狀態（開啟/關閉）與前景/背景進行四分法判定：
 * 1. SCREEN_ON_FOREGROUND  : 螢幕開啟且 Reader 在前景 (可見且互動中)
 * 2. SCREEN_ON_BACKGROUND  : 螢幕開啟且 Reader 在背景 (使用者在其他 App 或桌面)
 * 3. SCREEN_OFF_FOREGROUND : 螢幕關閉且 Reader 息屏前在前景 (放口袋/鎖屏聆聽模式)
 * 4. SCREEN_OFF_BACKGROUND : 螢幕關閉且 Reader 在背景 (在其他 App 時關閉螢幕)
 */
enum class ReaderEnvironmentState {
    SCREEN_ON_FOREGROUND,
    SCREEN_ON_BACKGROUND,
    SCREEN_OFF_FOREGROUND,
    SCREEN_OFF_BACKGROUND;

    /**
     * 是否屬於前景環境（含螢幕開啟的前景互動與息屏前的閱讀聆聽模式）
     */
    val isForeground: Boolean
        get() = this == SCREEN_ON_FOREGROUND || this == SCREEN_OFF_FOREGROUND

    /**
     * 是否屬於背景環境（使用者正使用其他 App，或在其他 App 時關閉螢幕）
     */
    val isBackground: Boolean
        get() = !isForeground

    /**
     * 在背景時不可受藍牙耳機改變現有進度（只有播放/閱讀與暫停有效）
     */
    val allowsProgressChange: Boolean
        get() = isForeground

    /**
     * 快速音量控制是否生效（僅在前景時有效）
     */
    val allowsFastVolumeControl: Boolean
        get() = isForeground
}
