package com.rokkystudio.dropme

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Применяет сохранённые настройки интерфейса на уровне процесса до создания Activity.
 */
class DropMeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        applySavedUiSettings()
    }

    private fun applySavedUiSettings() {
        val uiSettings = UiSettings(this)
        AppCompatDelegate.setDefaultNightMode(
            when (uiSettings.getTheme()) {
                AppTheme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                AppTheme.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            },
        )
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(uiSettings.getLanguage()),
        )
    }
}
