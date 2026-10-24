package com.rokkystudio.dropme.device

import android.content.Context

/**
 * Хранит постоянные решения отдельно для приёма файлов (share) и доступа к папкам (files).
 *
 * Старое единое BLOCKED-решение мигрирует в блокировку обоих прав. Старое ALLOWED-решение
 * сбрасывается в UNKNOWN, чтобы после обновления пользователь отдельно подтвердил каждое право.
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
        val shareDecision: Decision,
        val filesDecision: Decision,
        val lastSeenAt: Long,
    ) {
        val aggregateDecision: Decision
            get() = when {
                shareDecision == Decision.ALLOWED || filesDecision == Decision.ALLOWED -> Decision.ALLOWED
                shareDecision == Decision.BLOCKED || filesDecision == Decision.BLOCKED -> Decision.BLOCKED
                else -> Decision.UNKNOWN
            }
    }

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    init {
        migrateLegacyDecisions()
    }

    fun getDecision(deviceId: String, purpose: String): Decision {
        val suffix = when (purpose) {
            PURPOSE_SHARE -> SUFFIX_SHARE_DECISION
            PURPOSE_FILES -> SUFFIX_FILES_DECISION
            else -> return Decision.UNKNOWN
        }
        return parseDecision(preferences.getString(key(deviceId, suffix), null))
    }

    fun remember(
        deviceId: String,
        deviceName: String,
        ip: String,
        purpose: String,
        decision: Decision,
    ) {
        require(decision != Decision.UNKNOWN)
        val suffix = when (purpose) {
            PURPOSE_SHARE -> SUFFIX_SHARE_DECISION
            PURPOSE_FILES -> SUFFIX_FILES_DECISION
            else -> error("Unsupported permission purpose: $purpose")
        }
        val ids = preferences.getStringSet(KEY_DEVICE_IDS, emptySet()).orEmpty().toMutableSet()
        ids += deviceId
        preferences.edit()
            .putStringSet(KEY_DEVICE_IDS, ids)
            .putString(key(deviceId, SUFFIX_NAME), deviceName)
            .putString(key(deviceId, SUFFIX_IP), ip)
            .putString(key(deviceId, suffix), formatDecision(decision))
            .putLong(key(deviceId, SUFFIX_LAST_SEEN), System.currentTimeMillis())
            .apply()
    }

    fun touch(deviceId: String, deviceName: String, ip: String) {
        if (getDecision(deviceId, PURPOSE_SHARE) == Decision.UNKNOWN &&
            getDecision(deviceId, PURPOSE_FILES) == Decision.UNKNOWN
        ) {
            return
        }
        preferences.edit()
            .putString(key(deviceId, SUFFIX_NAME), deviceName)
            .putString(key(deviceId, SUFFIX_IP), ip)
            .putLong(key(deviceId, SUFFIX_LAST_SEEN), System.currentTimeMillis())
            .apply()
    }

    fun blockAll(deviceId: String, deviceName: String, ip: String) {
        remember(deviceId, deviceName, ip, PURPOSE_SHARE, Decision.BLOCKED)
        remember(deviceId, deviceName, ip, PURPOSE_FILES, Decision.BLOCKED)
    }

    /** Сбрасывает оба постоянных решения до UNKNOWN без выдачи разрешений. */
    fun clearDecisions(deviceId: String) {
        preferences.edit()
            .remove(key(deviceId, SUFFIX_SHARE_DECISION))
            .remove(key(deviceId, SUFFIX_FILES_DECISION))
            .apply()
    }

    fun forget(deviceId: String) {
        val ids = preferences.getStringSet(KEY_DEVICE_IDS, emptySet()).orEmpty().toMutableSet()
        ids.remove(deviceId)
        preferences.edit()
            .putStringSet(KEY_DEVICE_IDS, ids)
            .remove(key(deviceId, SUFFIX_NAME))
            .remove(key(deviceId, SUFFIX_IP))
            .remove(key(deviceId, SUFFIX_SHARE_DECISION))
            .remove(key(deviceId, SUFFIX_FILES_DECISION))
            .remove(key(deviceId, SUFFIX_DECISION_LEGACY))
            .remove(key(deviceId, SUFFIX_LAST_SEEN))
            .apply()
    }

    fun list(decision: Decision): List<KnownDevice> {
        require(decision != Decision.UNKNOWN)
        return preferences.getStringSet(KEY_DEVICE_IDS, emptySet())
            .orEmpty()
            .mapNotNull { deviceId ->
                val device = KnownDevice(
                    deviceId = deviceId,
                    deviceName = preferences.getString(key(deviceId, SUFFIX_NAME), null)
                        ?.takeIf { it.isNotBlank() }
                        ?: deviceId,
                    lastIp = preferences.getString(key(deviceId, SUFFIX_IP), "").orEmpty(),
                    shareDecision = getDecision(deviceId, PURPOSE_SHARE),
                    filesDecision = getDecision(deviceId, PURPOSE_FILES),
                    lastSeenAt = preferences.getLong(key(deviceId, SUFFIX_LAST_SEEN), 0L),
                )
                device.takeIf { it.aggregateDecision == decision }
            }
            .sortedByDescending { it.lastSeenAt }
    }

    private fun migrateLegacyDecisions() {
        val ids = preferences.getStringSet(KEY_DEVICE_IDS, emptySet()).orEmpty()
        if (ids.isEmpty()) {
            return
        }

        val editor = preferences.edit()
        var changed = false
        ids.forEach { deviceId ->
            val legacyKey = key(deviceId, SUFFIX_DECISION_LEGACY)
            val legacy = preferences.getString(legacyKey, null) ?: return@forEach
            val legacyDecision = parseDecision(legacy)
            if (legacyDecision == Decision.BLOCKED) {
                editor.putString(key(deviceId, SUFFIX_SHARE_DECISION), VALUE_BLOCKED)
                editor.putString(key(deviceId, SUFFIX_FILES_DECISION), VALUE_BLOCKED)
            }
            editor.remove(legacyKey)
            changed = true
        }
        if (changed) {
            editor.apply()
        }
    }

    private fun parseDecision(value: String?): Decision = when (value) {
        VALUE_ALLOWED -> Decision.ALLOWED
        VALUE_BLOCKED -> Decision.BLOCKED
        else -> Decision.UNKNOWN
    }

    private fun formatDecision(decision: Decision): String = when (decision) {
        Decision.ALLOWED -> VALUE_ALLOWED
        Decision.BLOCKED -> VALUE_BLOCKED
        Decision.UNKNOWN -> error("UNKNOWN cannot be persisted")
    }

    private fun key(deviceId: String, suffix: String): String = "device_$deviceId$suffix"

    companion object {
        const val PURPOSE_SHARE = "share"
        const val PURPOSE_FILES = "files"

        private const val PREFERENCES_NAME = "dropme_trust"
        private const val KEY_DEVICE_IDS = "device_ids"
        private const val SUFFIX_NAME = "_name"
        private const val SUFFIX_IP = "_ip"
        private const val SUFFIX_DECISION_LEGACY = "_decision"
        private const val SUFFIX_SHARE_DECISION = "_share_decision"
        private const val SUFFIX_FILES_DECISION = "_files_decision"
        private const val SUFFIX_LAST_SEEN = "_last_seen"
        private const val VALUE_ALLOWED = "allowed"
        private const val VALUE_BLOCKED = "blocked"
    }
}
