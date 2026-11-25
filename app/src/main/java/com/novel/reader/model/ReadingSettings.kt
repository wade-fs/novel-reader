package com.novel.reader.model

enum class VoiceGender(val displayName: String) {
    DEFAULT("系統預設"),
    MALE("男聲"),
    FEMALE("女聲")
}

enum class AppThemeMode(val displayName: String) {
    SYSTEM("跟隨系統"),
    LIGHT("淺色"),
    DARK("深色");

    companion object {
        fun fromName(name: String?): AppThemeMode {
            if (name.isNullOrBlank()) return SYSTEM
            return entries.firstOrNull { it.name == name } ?: SYSTEM
        }
    }
}

data class ReadingSettings(
    var speechRate: Float = 1.0f,            // 0.5f ~ 2.5f
    var pitch: Float = 1.0f,                 // 0.6f ~ 1.6f
    var gender: VoiceGender = VoiceGender.DEFAULT,
    var selectedVoiceName: String? = null,
    var autoNextChapter: Boolean = true,     // 必須自動換下一章
    var filterConfig: ReadingFilterConfig = ReadingFilterConfig(),
    var fontSizeSp: Float = DEFAULT_FONT_SIZE_SP, // 12f ~ 32f
    var themeMode: AppThemeMode = AppThemeMode.SYSTEM
) {
    fun deepCopy(): ReadingSettings = copy(
        filterConfig = filterConfig.copy(),
        fontSizeSp = clampFontSizeSp(fontSizeSp)
    )

    companion object {
        const val MIN_FONT_SIZE_SP = 12f
        const val MAX_FONT_SIZE_SP = 32f
        const val DEFAULT_FONT_SIZE_SP = 17f
        const val FONT_SIZE_STEP_SP = 1f
        private const val CHAPTER_TITLE_EXTRA_SP = 5f

        fun clampFontSizeSp(sizeSp: Float): Float {
            if (sizeSp.isNaN() || sizeSp.isInfinite()) return DEFAULT_FONT_SIZE_SP
            return sizeSp.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
        }

        fun chapterTitleSizeSp(bodyFontSizeSp: Float): Float {
            return clampFontSizeSp(bodyFontSizeSp) + CHAPTER_TITLE_EXTRA_SP
        }
    }
}
