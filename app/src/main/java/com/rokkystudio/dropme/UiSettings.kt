package com.rokkystudio.dropme

import android.content.Context
import badfox.logic.neoui.NeoThemeMode
import badfox.logic.neoui.NeoTopBarView
import badfox.logic.neoui.NeoUiSettings

/**
 * Stores and applies standard NeoUI interface settings for DROPME.
 */
class UiSettings(context: Context) {
    private val uiSettings = NeoUiSettings(
        context = context,
        preferencesName = PREFERENCES_NAME,
        defaultTheme = NeoThemeMode.DARK,
        defaultLanguageTag = null,
    )

    fun getTheme(): AppTheme = uiSettings.getTheme()

    fun getLanguage(): String = uiSettings.getLanguage()

    /**
     * Применяет сохранённую тему приложения через AppCompat.
     */
    fun applyAppCompatNightMode() {
        uiSettings.applyAppCompatNightMode()
    }

    /**
     * Связывает NeoUI-панель со стандартными настройками темы и языка.
     *
     * NeoUI сохраняет выбор и применяет соответствующую настройку через AppCompat.
     */
    fun bindTopBarUi(topBar: NeoTopBarView) {
        uiSettings.bindTopBar(topBar)
    }

    private companion object {
        const val PREFERENCES_NAME = "dropme_ui"
    }
}

typealias AppTheme = NeoThemeMode