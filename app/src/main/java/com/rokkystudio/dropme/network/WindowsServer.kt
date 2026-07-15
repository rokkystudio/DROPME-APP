package com.rokkystudio.dropme.network

/**
 * Описывает найденный Windows DROPME Server.
 */
data class WindowsServer(
    val host: String,
    val tcpPort: Int,
    val udpPort: Int?,
    val deviceName: String,
    val protocolVersion: Int,
)

