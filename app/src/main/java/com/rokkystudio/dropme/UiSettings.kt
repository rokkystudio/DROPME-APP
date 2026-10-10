package com.rokkystudio.dropme

import android.content.Context
import com.rokkystudio.sharedapp.neoui.NeoThemeMode
import com.rokkystudio.sharedapp.neoui.NeoTopBarView
import com.rokkystudio.sharedapp.neoui.NeoUiSettings

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

    fun applyAppCompatNightMode() {
        uiSettings.applyAppCompatNightMode()
    }

    fun bindTopBarUi(topBar: NeoTopBarView, onChanged: () -> Unit) {
        uiSettings.bindTopBar(topBar, onChanged = onChanged)
    }

    private companion object {
        const val PREFERENCES_NAME = "dropme_ui"
    }
}

typealias AppTheme = NeoThemeMode