package com.novel.reader.util

import android.content.Context
import android.content.SharedPreferences
import com.novel.reader.model.AppThemeMode
import com.novel.reader.model.ReadingFilterConfig
import com.novel.reader.model.ReadingSettings
import com.novel.reader.model.VoiceGender

data class SavedReadingProgress(
    val novelId: String,
    val novelTitle: String,
    val chapterIndex: Int,
    val chapterNumber: Int,
    val chapterTitle: String,
    val segmentIndex: Int,
    val uiPosition: Int,
    val timestamp: Long
)

class ReadingProgressManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("novel_reading_progress", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_HAS_PROGRESS = "has_saved_progress"
        private const val KEY_NOVEL_ID = "novel_id"
        private const val KEY_NOVEL_TITLE = "novel_title"
        private const val KEY_CHAPTER_INDEX = "chapter_index"
        private const val KEY_CHAPTER_NUMBER = "chapter_number"
        private const val KEY_CHAPTER_TITLE = "chapter_title"
        private const val KEY_SEGMENT_INDEX = "segment_index"
        private const val KEY_UI_POSITION = "ui_position"
        private const val KEY_TIMESTAMP = "timestamp"

        private const val PREFIX_BOOK = "book_prog_"

        // Settings keys
        private const val KEY_SPEED = "setting_speed"
        private const val KEY_PITCH = "setting_pitch"
        private const val KEY_GENDER = "setting_gender"
        private const val KEY_VOICE_NAME = "setting_voice_name"
        private const val KEY_AUTO_NEXT = "setting_auto_next"
        private const val KEY_FONT_SIZE_SP = "setting_font_size_sp"
        private const val KEY_THEME_MODE = "setting_theme_mode"

        // Filter keys（僅保留是否朗讀章名，工作紀錄一律濾除）
        private const val KEY_FILTER_TITLE = "filter_read_title"
    }

    /**
     * 儲存目前的朗讀進度（同時記錄全域最後進度與每本書各自的閱讀進度）
     */
    fun saveProgress(
        novelId: String,
        novelTitle: String,
        chapterIndex: Int,
        chapterNumber: Int,
        chapterTitle: String,
        segmentIndex: Int,
        uiPosition: Int
    ) {
        if (novelId.isBlank()) return
        val now = System.currentTimeMillis()
        val p = "${PREFIX_BOOK}${novelId}_"
        prefs.edit()
            .putBoolean(KEY_HAS_PROGRESS, true)
            .putString(KEY_NOVEL_ID, novelId)
            .putString(KEY_NOVEL_TITLE, novelTitle)
            .putInt(KEY_CHAPTER_INDEX, chapterIndex)
            .putInt(KEY_CHAPTER_NUMBER, chapterNumber)
            .putString(KEY_CHAPTER_TITLE, chapterTitle)
            .putInt(KEY_SEGMENT_INDEX, segmentIndex)
            .putInt(KEY_UI_POSITION, uiPosition)
            .putLong(KEY_TIMESTAMP, now)
            // 每本書獨立進度
            .putBoolean("${p}has", true)
            .putString("${p}title", novelTitle)
            .putInt("${p}chap_idx", chapterIndex)
            .putInt("${p}chap_num", chapterNumber)
            .putString("${p}chap_title", chapterTitle)
            .putInt("${p}seg_idx", segmentIndex)
            .putInt("${p}ui_pos", uiPosition)
            .putLong("${p}ts", now)
            .apply()
    }

    /**
     * 取得最近一次儲存的朗讀進度
     */
    fun getSavedProgress(): SavedReadingProgress? {
        if (!prefs.getBoolean(KEY_HAS_PROGRESS, false)) return null
        val id = prefs.getString(KEY_NOVEL_ID, "") ?: ""
        if (id.isBlank()) return null
        return SavedReadingProgress(
            novelId = id,
            novelTitle = prefs.getString(KEY_NOVEL_TITLE, "") ?: "",
            chapterIndex = prefs.getInt(KEY_CHAPTER_INDEX, 0),
            chapterNumber = prefs.getInt(KEY_CHAPTER_NUMBER, 1),
            chapterTitle = prefs.getString(KEY_CHAPTER_TITLE, "") ?: "",
            segmentIndex = prefs.getInt(KEY_SEGMENT_INDEX, 0),
            uiPosition = prefs.getInt(KEY_UI_POSITION, 0),
            timestamp = prefs.getLong(KEY_TIMESTAMP, 0L)
        )
    }

    /**
     * 取得特定書籍的獨立朗讀進度
     */
    fun getSavedProgressForBook(novelId: String): SavedReadingProgress? {
        val p = "${PREFIX_BOOK}${novelId}_"
        if (prefs.getBoolean("${p}has", false)) {
            return SavedReadingProgress(
                novelId = novelId,
                novelTitle = prefs.getString("${p}title", "") ?: "",
                chapterIndex = prefs.getInt("${p}chap_idx", 0),
                chapterNumber = prefs.getInt("${p}chap_num", 1),
                chapterTitle = prefs.getString("${p}chap_title", "") ?: "",
                segmentIndex = prefs.getInt("${p}seg_idx", 0),
                uiPosition = prefs.getInt("${p}ui_pos", 0),
                timestamp = prefs.getLong("${p}ts", 0L)
            )
        }
        val latest = getSavedProgress()
        return if (latest?.novelId == novelId) latest else null
    }

    /**
     * 清除特定書籍的朗讀進度（刪除書單時使用）
     */
    fun clearProgressForBook(novelId: String) {
        val p = "${PREFIX_BOOK}${novelId}_"
        val editor = prefs.edit()
            .remove("${p}has")
            .remove("${p}title")
            .remove("${p}chap_idx")
            .remove("${p}chap_num")
            .remove("${p}chap_title")
            .remove("${p}seg_idx")
            .remove("${p}ui_pos")
            .remove("${p}ts")
        if (prefs.getString(KEY_NOVEL_ID, "") == novelId) {
            editor.putBoolean(KEY_HAS_PROGRESS, false)
                .remove(KEY_NOVEL_ID)
                .remove(KEY_NOVEL_TITLE)
        }
        editor.apply()
    }

    /**
     * 儲存閱讀外觀、語音與章節過濾偏好設定
     */
    fun saveSettings(settings: ReadingSettings) {
        prefs.edit()
            .putFloat(KEY_SPEED, settings.speechRate)
            .putFloat(KEY_PITCH, settings.pitch)
            .putString(KEY_GENDER, settings.gender.name)
            .putString(KEY_VOICE_NAME, settings.selectedVoiceName)
            .putBoolean(KEY_AUTO_NEXT, settings.autoNextChapter)
            .putBoolean(KEY_FILTER_TITLE, settings.filterConfig.readChapterTitle)
            .putFloat(KEY_FONT_SIZE_SP, ReadingSettings.clampFontSizeSp(settings.fontSizeSp))
            .putString(KEY_THEME_MODE, settings.themeMode.name)
            .apply()
    }

    /**
     * 載入閱讀外觀、語音與章節過濾偏好設定
     */
    fun loadSettings(): ReadingSettings {
        val genderStr = prefs.getString(KEY_GENDER, VoiceGender.DEFAULT.name)
        val gender = try {
            VoiceGender.valueOf(genderStr ?: VoiceGender.DEFAULT.name)
        } catch (e: Exception) {
            VoiceGender.DEFAULT
        }

        val filter = ReadingFilterConfig(
            readChapterTitle = prefs.getBoolean(KEY_FILTER_TITLE, true)
        )

        val fontSizeSp = ReadingSettings.clampFontSizeSp(
            prefs.getFloat(KEY_FONT_SIZE_SP, ReadingSettings.DEFAULT_FONT_SIZE_SP)
        )
        val themeMode = AppThemeMode.fromName(prefs.getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.name))

        return ReadingSettings(
            speechRate = prefs.getFloat(KEY_SPEED, 1.0f),
            pitch = prefs.getFloat(KEY_PITCH, 1.0f),
            gender = gender,
            selectedVoiceName = prefs.getString(KEY_VOICE_NAME, null),
            autoNextChapter = prefs.getBoolean(KEY_AUTO_NEXT, true),
            filterConfig = filter,
            fontSizeSp = fontSizeSp,
            themeMode = themeMode
        )
    }
}
