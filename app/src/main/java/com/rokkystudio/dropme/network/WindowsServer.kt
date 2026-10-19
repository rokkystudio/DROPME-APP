package com.rokkystudio.dropme.network

/**
 * Описывает найденное DROPME-устройство в текущей локальной сети.
 *
 * Доверие привязывается к deviceId, а host является только текущим сетевым адресом устройства.
 */
data class WindowsServer(
    val host: String,
    val tcpPort: Int,
    val udpPort: Int?,
    val deviceName: String,
    val protocolVersion: Int,
    val deviceId: String = "",
    val deviceType: String = "unknown",
    val filePort: Int? = null,
    val fileAccessAvailable: Boolean = false,
    val shareAvailable: Boolean = false,
)
