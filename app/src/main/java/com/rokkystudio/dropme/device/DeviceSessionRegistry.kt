package com.rokkystudio.dropme.device

import java.util.concurrent.ConcurrentHashMap

/**
 * Хранит короткие разрешения активных DROPME-сессий.
 *
 * Постоянное решение остаётся привязано к device ID в [DeviceTrustStore], а текущий IP
 * используется только как временный маршрут уже одобренной сессии.
 */
object DeviceSessionRegistry {
    private const val SESSION_LIFETIME_MS = 10 * 60 * 1000L
    private val sessions = ConcurrentHashMap<String, Long>()

    fun grant(remoteIp: String, purpose: String) {
        if (remoteIp.isBlank()) {
            return
        }
        sessions[key(remoteIp, purpose)] = System.currentTimeMillis() + SESSION_LIFETIME_MS
    }

    fun isAuthorized(remoteIp: String, purpose: String): Boolean {
        val key = key(remoteIp, purpose)
        val expiresAt = sessions[key] ?: return false
        if (expiresAt < System.currentTimeMillis()) {
            sessions.remove(key)
            return false
        }
        return true
    }

    fun revoke(remoteIp: String, purpose: String) {
        sessions.remove(key(remoteIp, purpose))
    }

    private fun key(remoteIp: String, purpose: String): String = "$purpose:$remoteIp"
}
