package com.rokkystudio.dropme

import android.content.Context

/**
 * Хранит выбранную пользователем тему интерфейса DROPME.
 */
class UiSettings(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun getTheme(): AppTheme {
        return when (preferences.getString(KEY_THEME, null)) {
            AppTheme.LIGHT.preferenceValue -> AppTheme.LIGHT
            AppTheme.DARK.preferenceValue -> AppTheme.DARK
            else -> AppTheme.DARK
        }
    }

    fun setTheme(theme: AppTheme) {
        preferences.edit().putString(KEY_THEME, theme.preferenceValue).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "dropme_ui"
        const val KEY_THEME = "theme"
    }
}

enum class AppTheme(val preferenceValue: String) {
    LIGHT("light"),
    DARK("dark"),
}
