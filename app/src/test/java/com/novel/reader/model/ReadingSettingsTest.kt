package com.novel.reader.model

import androidx.appcompat.app.AppCompatDelegate
import com.novel.reader.util.ThemeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class ReadingSettingsTest {

    @Test
    fun testClampFontSizeSp() {
        assertEquals(ReadingSettings.DEFAULT_FONT_SIZE_SP, ReadingSettings.clampFontSizeSp(17f), 0.001f)
        assertEquals(ReadingSettings.MIN_FONT_SIZE_SP, ReadingSettings.clampFontSizeSp(4f), 0.001f)
        assertEquals(ReadingSettings.MAX_FONT_SIZE_SP, ReadingSettings.clampFontSizeSp(60f), 0.001f)
        assertEquals(ReadingSettings.DEFAULT_FONT_SIZE_SP, ReadingSettings.clampFontSizeSp(Float.NaN), 0.001f)
        assertEquals(
            ReadingSettings.DEFAULT_FONT_SIZE_SP,
            ReadingSettings.clampFontSizeSp(Float.POSITIVE_INFINITY),
            0.001f
        )
    }

    @Test
    fun testChapterTitleSizeSpScalesWithBodyFontSize() {
        assertEquals(22f, ReadingSettings.chapterTitleSizeSp(17f), 0.001f)
        assertEquals(17f, ReadingSettings.chapterTitleSizeSp(8f), 0.001f)
        assertEquals(37f, ReadingSettings.chapterTitleSizeSp(40f), 0.001f)
    }

    @Test
    fun testAppThemeModeFromName() {
        assertEquals(AppThemeMode.LIGHT, AppThemeMode.fromName("LIGHT"))
        assertEquals(AppThemeMode.DARK, AppThemeMode.fromName("DARK"))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromName("SYSTEM"))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromName(null))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromName(""))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromName("UNKNOWN_MODE"))
    }

    @Test
    fun testThemeManagerNightModeMapping() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, ThemeManager.toNightMode(AppThemeMode.LIGHT))
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, ThemeManager.toNightMode(AppThemeMode.DARK))
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, ThemeManager.toNightMode(AppThemeMode.SYSTEM))
    }

    @Test
    fun testDeepCopyIsolatesFilterConfigAndClampsFontSize() {
        val original = ReadingSettings(
            speechRate = 1.25f,
            pitch = 1.1f,
            gender = VoiceGender.FEMALE,
            selectedVoiceName = "tw-female",
            autoNextChapter = false,
            filterConfig = ReadingFilterConfig(readChapterTitle = true),
            fontSizeSp = 99f,
            themeMode = AppThemeMode.DARK
        )

        val copied = original.deepCopy()
        assertNotSame(original.filterConfig, copied.filterConfig)
        assertEquals(ReadingSettings.MAX_FONT_SIZE_SP, copied.fontSizeSp, 0.001f)
        assertEquals(AppThemeMode.DARK, copied.themeMode)

        copied.filterConfig.setBodyOnlyMode()
        copied.fontSizeSp = 14f
        copied.themeMode = AppThemeMode.LIGHT

        assertEquals(true, original.filterConfig.readChapterTitle)
        assertEquals(99f, original.fontSizeSp, 0.001f)
        assertEquals(AppThemeMode.DARK, original.themeMode)
    }
}
