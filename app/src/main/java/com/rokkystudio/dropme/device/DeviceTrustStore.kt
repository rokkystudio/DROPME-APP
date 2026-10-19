package com.rokkystudio.dropme.device

import android.content.Context

/**
 * Хранит постоянные решения пользователя для известных DROPME-устройств.
 *
 * Решение привязано только к deviceId. Имя и IP сохраняются как последние известные
 * отображаемые значения и не участвуют в проверке доверия.
 */
class DeviceTrustStore(
    context: Context,
) {
    enum class Decision {
        ALLOWED,
        BLOCKED,
        UNKNOWN,
    }

    data class KnownDevice(
        val deviceId: String,
        val deviceName: String,
        val lastIp: String,
        val decision: Decision,
        val lastSeenAt: Long,
    )

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun getDecision(deviceId: String): Decision {
        return when (preferences.getString(key(deviceId, SUFFIX_DECISION), null)) {
            VALUE_ALLOWED -> Decision.ALLOWED
            VALUE_BLOCKED -> Decision.BLOCKED
            else -> Decision.UNKNOWN
        }
    }

    fun remember(
        deviceId: String,
        deviceName: String,
        ip: String,
        decision: Decision,
    ) {
        require(decision != Decision.UNKNOWN)
        val ids = preferences.getStringSet(KEY_DEVICE_IDS, emptySet()).orEmpty().toMutableSet()
        ids += deviceId
        preferences.edit()
            .putStringSet(KEY_DEVICE_IDS, ids)
            .putString(key(deviceId, SUFFIX_NAME), deviceName)
            .putString(key(deviceId, SUFFIX_IP), ip)
            .putString(
                key(deviceId, SUFFIX_DECISION),
                if (decision == Decision.ALLOWED) VALUE_ALLOWED else VALUE_BLOCKED,
            )
            .putLong(key(deviceId, SUFFIX_LAST_SEEN), System.currentTimeMillis())
            .apply()
    }

    fun touch(deviceId: String, deviceName: String, ip: String) {
        if (getDecision(deviceId) == Decision.UNKNOWN) {
            return
        }
        preferences.edit()
            .putString(key(deviceId, SUFFIX_NAME), deviceName)
            .putString(key(deviceId, SUFFIX_IP), ip)
            .putLong(key(deviceId, SUFFIX_LAST_SEEN), System.currentTimeMillis())
            .apply()
    }

    fun forget(deviceId: String) {
        val ids = preferences.getStringSet(KEY_DEVICE_IDS, emptySet()).orEmpty().toMutableSet()
        ids.remove(deviceId)
        preferences.edit()
            .putStringSet(KEY_DEVICE_IDS, ids)
            .remove(key(deviceId, SUFFIX_NAME))
            .remove(key(deviceId, SUFFIX_IP))
            .remove(key(deviceId, SUFFIX_DECISION))
            .remove(key(deviceId, SUFFIX_LAST_SEEN))
            .apply()
    }

    fun list(decision: Decision): List<KnownDevice> {
        require(decision != Decision.UNKNOWN)
        return preferences.getStringSet(KEY_DEVICE_IDS, emptySet())
            .orEmpty()
            .mapNotNull { deviceId ->
                val storedDecision = getDecision(deviceId)
                if (storedDecision != decision) {
                    return@mapNotNull null
                }
                KnownDevice(
                    deviceId = deviceId,
                    deviceName = preferences.getString(key(deviceId, SUFFIX_NAME), null)
                        ?.takeIf { it.isNotBlank() }
                        ?: deviceId,
                    lastIp = preferences.getString(key(deviceId, SUFFIX_IP), "").orEmpty(),
                    decision = storedDecision,
                    lastSeenAt = preferences.getLong(key(deviceId, SUFFIX_LAST_SEEN), 0L),
                )
            }
            .sortedByDescending { it.lastSeenAt }
    }

    private fun key(deviceId: String, suffix: String): String = "device_$deviceId$suffix"

    private companion object {
        const val PREFERENCES_NAME = "dropme_trust"
        const val KEY_DEVICE_IDS = "device_ids"
        const val SUFFIX_NAME = "_name"
        const val SUFFIX_IP = "_ip"
        const val SUFFIX_DECISION = "_decision"
        const val SUFFIX_LAST_SEEN = "_last_seen"
        const val VALUE_ALLOWED = "allowed"
        const val VALUE_BLOCKED = "blocked"
    }
}
