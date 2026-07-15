package com.rokkystudio.dropme.network

/**
 * Описывает совместимые API-маршруты Windows-сервера.
 */
object WindowsServerApi {
    val basePaths = listOf("/dropme", "/wifidrop")
    val acceptedAppIds = setOf("DROPME", "WiFiDrop")

    fun buildUrl(
        host: String,
        port: Int,
        basePath: String,
        suffix: String,
    ): String {
        return "http://$host:$port$basePath$suffix"
    }
}
