package com.novel.reader

import android.app.Application
import com.novel.reader.util.ReadingProgressManager
import com.novel.reader.util.ThemeManager

class NovelReaderApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val settings = ReadingProgressManager(this).loadSettings()
        ThemeManager.applyThemeMode(settings.themeMode)
    }
}
