package com.rokkystudio.dropme

import android.app.Application
import badfox.logic.neoui.NeoThemeMode
import badfox.logic.neoui.NeoUiSettings
import com.rokkystudio.dropme.device.DeviceIdentityStore
import com.rokkystudio.dropme.network.AppContextHolder
import com.rokkystudio.dropme.service.DropMeDeviceService

/**
 * Применяет общие настройки NeoUI и восстанавливает включённую сетевую доступность устройства.
 */
class DropMeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.context = applicationContext
        NeoUiSettings(
            context = this,
            preferencesName = NEO_UI_PREFERENCES_NAME,
            defaultTheme = NeoThemeMode.DARK,
            defaultLanguageTag = null,
        ).apply {
            applyAppCompatNightMode()
            applyAppCompatLanguage()
        }

        if (DeviceIdentityStore(this).isDeviceServiceEnabled()) {
            DropMeDeviceService.start(this)
        }
    }

    private companion object {
        const val NEO_UI_PREFERENCES_NAME = "dropme_ui"
    }
}
