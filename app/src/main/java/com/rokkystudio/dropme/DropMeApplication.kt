package com.rokkystudio.dropme

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * Применяет сохранённую тему на уровне процесса до создания любой Activity.
 * Это гарантирует одинаковую тему для launcher, Sharing и будущих точек входа.
 */
class DropMeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        applySavedTheme()
    }

    private fun applySavedTheme() {
        AppCompatDelegate.setDefaultNightMode(
            when (UiSettings(this).getTheme()) {
                AppTheme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                AppTheme.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            },
        )
    }
}
