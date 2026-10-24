package com.rokkystudio.dropme.network

import android.util.Log
import com.rokkystudio.dropme.AppError
import com.rokkystudio.dropme.asAppException
import com.rokkystudio.dropme.toAppError
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Выполняет HTTP-поиск DROPME-устройств в Wi-Fi подсети устройства.
 */
class WindowsServerScanner {
    private val rememberedHosts = ConcurrentHashMap.newKeySet<String>()
    /**
     * Сообщает о прогрессе IP-сканирования подсети.
     */
    data class ScanProgress(
        val currentHost: String,
        val scannedHosts: Int,
        val totalHosts: Int,
        val foundServers: Int,
    )

    /**
     * Сканирует подсеть и возвращает найденные DROPME-устройства.
     */
    fun scan(
        wifiInfo: WifiNetworkProvider.WifiNetworkInfo,
        onProgress: ((ScanProgress) -> Unit)? = null,
        onServerFound: ((List<WindowsServer>) -> Unit)? = null,
        shouldContinue: (() -> Boolean)? = null,
    ): List<WindowsServer> {
        val rememberedSnapshot = rememberedHosts.toSet()
        val candidateHosts = (
            rememberedSnapshot + buildCandidateHosts(wifiInfo.ipv4Address, wifiInfo.prefixLength)
        ).distinct()
        if (candidateHosts.size > MAX_SCAN_HOSTS + rememberedSnapshot.size) {
            throw AppError.UnknownError("Подсеть Wi-Fi слишком большая для быстрого поиска устройств").asAppException()
        }

        Log.d(LOG_TAG, "Старт scan по ${candidateHosts.size} адресам")
        val client = OkHttpClient.Builder()
            .socketFactory(WifiBoundSocketFactory(wifiInfo.network))
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()

        val results = ConcurrentHashMap<String, WindowsServer>()
        val scannedHosts = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(minOf(MAX_PARALLEL_REQUESTS, candidateHosts.size.coerceAtLeast(1)))
        try {
            val futures = ArrayList<Future<*>>(candidateHosts.size)
            for (host in candidateHosts) {
                if (shouldContinue?.invoke() == false) {
                    break
                }
                futures += executor.submit {
                    if (shouldContinue?.invoke() == false) {
                        return@submit
                    }
                    val attempts = if (host in rememberedSnapshot) REMEMBERED_HOST_ATTEMPTS else 1
                    fetchServer(client, host, attempts)?.let { server ->
                        rememberedHosts += host
                        val existing = results.put(server.deviceId, server)
                        if (existing != server) {
                            onServerFound?.invoke(sortedDevices(results.values))
                        }
                    }
                    onProgress?.invoke(
                        ScanProgress(
                            currentHost = host,
                            scannedHosts = scannedHosts.incrementAndGet(),
                            totalHosts = candidateHosts.size,
                            foundServers = results.size,
                        ),
                    )
                }
            }
            futures.forEach { future ->
                if (shouldContinue?.invoke() == false) {
                    future.cancel(true)
                } else {
                    future.get()
                }
            }
        } catch (throwable: Throwable) {
            throw throwable.toAppError(
                AppError.UnknownError("Не удалось завершить поиск устройств DROPME"),
            ).asAppException(throwable)
        } finally {
            executor.shutdownNow()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }

        val servers = sortedDevices(results.values)
        Log.d(LOG_TAG, "Найдено устройств: ${servers.size}")
        servers.forEach { server ->
            Log.d(
                LOG_TAG,
                "Устройство ${server.deviceName} ${server.host}:${server.tcpPort} id=${server.deviceId} type=${server.deviceType}",
            )
        }
        return servers
    }

    /**
     * Выполняет HTTP-запрос к одному IP-адресу и проверяет DROPME protocol v3 identity.
     */
    /**
     * Проверяет один IP несколько раз. Повторы используются для адресов,
     * которые уже находились в текущем сеансе приложения.
     */
    private fun fetchServer(
        client: OkHttpClient,
        host: String,
        attempts: Int,
    ): WindowsServer? {
        repeat(attempts) {
            val server = fetchServerOnce(client, host)
            if (server != null) {
                return server
            }
        }
        return null
    }

    private fun fetchServerOnce(client: OkHttpClient, host: String): WindowsServer? {
        return try {
            for (basePath in WindowsServerApi.basePaths) {
                val request = Request.Builder()
                    .url(WindowsServerApi.buildUrl(host, DEFAULT_HTTP_PORT, basePath, "/info"))
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.code == 404) {
                        continue
                    }
                    if (!response.isSuccessful) {
                        return null
                    }
                    val body = response.body?.string().orEmpty()
                    parseServerInfo(host, body)?.let { return it }
                }
            }
            null
        } catch (throwable: Throwable) {
            val error = throwable.toAppError(AppError.UnknownError())
            if (error is AppError.LocalNetworkBlocked) {
                throw error.asAppException(throwable)
            }
            Log.d(LOG_TAG, "HTTP scan request failed for $host:$DEFAULT_HTTP_PORT", throwable)
            null
        }
    }

    /**
     * Преобразует protocol v3 /info в модель удалённого DROPME-устройства.
     */
    private fun parseServerInfo(host: String, body: String): WindowsServer? {
        return runCatching {
            val json = JSONObject(body)
            if (json.optString("app") !in WindowsServerApi.acceptedAppIds) {
                return null
            }
            if (json.optString("role") != "device") {
                return null
            }
            val protocolVersion = json.optInt("protocolVersion", -1)
            if (protocolVersion != PROTOCOL_VERSION) {
                return null
            }

            val deviceId = json.optString("deviceId").trim()
            if (!deviceId.matches(DEVICE_ID_PATTERN)) {
                return null
            }

            val filePort = json.optInt("filePort", 0).takeIf { it > 0 }
            WindowsServer(
                host = host,
                tcpPort = json.optInt("tcpPort", DEFAULT_HTTP_PORT),
                udpPort = json.optInt("udpPort", 0).takeIf { it > 0 },
                deviceName = json.optString("deviceName").ifBlank { host },
                protocolVersion = protocolVersion,
                deviceId = deviceId,
                deviceType = json.optString("deviceType").ifBlank { "unknown" },
                filePort = filePort,
                fileAccessAvailable = json.optBoolean("fileAccessAvailable", filePort != null),
                shareAvailable = json.optBoolean("shareAvailable", false),
            )
        }.getOrNull()
    }

    /**
     * Строит список IPv4-адресов в подсети, кроме адреса текущего устройства.
     */
    private fun buildCandidateHosts(address: Inet4Address, prefixLength: Int): List<String> {
        if (prefixLength !in 1..30) {
            throw AppError.UnknownError("Не удалось определить подсеть Wi-Fi для поиска устройств").asAppException()
        }

        val ipValue = address.toUnsignedLong()
        val mask = (-1L shl (32 - prefixLength)) and IPV4_MASK
        val network = ipValue and mask
        val broadcast = network or (IPV4_MASK xor mask)
        val firstHost = network + 1
        val lastHost = broadcast - 1
        val result = ArrayList<String>((lastHost - firstHost + 1).toInt().coerceAtLeast(0))
        var current = firstHost
        while (current <= lastHost) {
            if (current != ipValue) {
                result += current.toIpv4String()
            }
            current++
        }
        return result
    }

    private fun sortedDevices(devices: Collection<WindowsServer>): List<WindowsServer> {
        return devices.sortedWith(
            compareBy(
                { it.deviceName.lowercase() },
                { it.deviceId },
            ),
        )
    }

    /**
     * Преобразует IPv4-адрес в беззнаковое число.
     */
    private fun Inet4Address.toUnsignedLong(): Long {
        val bytes = address
        return ((bytes[0].toLong() and 0xFF) shl 24) or
            ((bytes[1].toLong() and 0xFF) shl 16) or
            ((bytes[2].toLong() and 0xFF) shl 8) or
            (bytes[3].toLong() and 0xFF)
    }

    /**
     * Преобразует беззнаковое число в строку IPv4-адреса.
     */
    private fun Long.toIpv4String(): String {
        return listOf(
            (this shr 24) and 0xFF,
            (this shr 16) and 0xFF,
            (this shr 8) and 0xFF,
            this and 0xFF,
        ).joinToString(".")
    }

    private companion object {
        const val LOG_TAG = "DROPME"
        const val DEFAULT_HTTP_PORT = 49231
        const val PROTOCOL_VERSION = 3
        const val CONNECT_TIMEOUT_MS = 300L
        const val READ_TIMEOUT_MS = 500L
        const val MAX_PARALLEL_REQUESTS = 24
        const val MAX_SCAN_HOSTS = 4096
        const val REMEMBERED_HOST_ATTEMPTS = 3
        const val IPV4_MASK = 0xFFFFFFFFL
        val DEVICE_ID_PATTERN = Regex("^[0-9a-f]{32}$")
    }
}
