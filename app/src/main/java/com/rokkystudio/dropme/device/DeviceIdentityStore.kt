package com.rokkystudio.dropme.device

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

/**
 * Хранит постоянный opaque ID устройства, отображаемое имя и признак сетевой доступности DROPME.
 *
 * Имя можно менять независимо от ID. IP-адрес в identity не хранится, потому что он может
 * изменяться между подключениями к Wi-Fi.
 */
class DeviceIdentityStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    data class DeviceIdentity(
        val id: String,
        val name: String,
        val type: String,
    )

    fun getIdentity(): DeviceIdentity {
        return DeviceIdentity(
            id = getOrCreateDeviceId(),
            name = getDeviceName(),
            type = DEVICE_TYPE_ANDROID,
        )
    }

    fun getDeviceName(): String {
        return preferences.getString(KEY_DEVICE_NAME, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: Build.MODEL.trim().ifEmpty { DEFAULT_DEVICE_NAME }
    }

    fun setDeviceName(name: String) {
        val normalized = name.trim()
        require(normalized.isNotEmpty()) { "Device name must not be empty" }
        preferences.edit().putString(KEY_DEVICE_NAME, normalized).apply()
    }

    fun isDeviceServiceEnabled(): Boolean {
        return preferences.getBoolean(KEY_DEVICE_SERVICE_ENABLED, true)
    }

    fun setDeviceServiceEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_DEVICE_SERVICE_ENABLED, enabled).apply()
    }

    private fun getOrCreateDeviceId(): String {
        preferences.getString(KEY_DEVICE_ID, null)
            ?.takeIf { it.matches(DEVICE_ID_PATTERN) }
            ?.let { return it }

        val androidId = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ANDROID_ID,
        ).orEmpty()

        val source = androidId.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
        val id = digest
            .take(DEVICE_ID_BYTES)
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }

        preferences.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    private companion object {
        const val PREFERENCES_NAME = "dropme_device"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_DEVICE_SERVICE_ENABLED = "device_service_enabled"
        const val DEFAULT_DEVICE_NAME = "Android"
        const val DEVICE_TYPE_ANDROID = "android"
        const val DEVICE_ID_BYTES = 16
        val DEVICE_ID_PATTERN = Regex("^[0-9a-f]{32}$")
    }
}
