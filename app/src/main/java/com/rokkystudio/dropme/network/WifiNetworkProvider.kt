package com.rokkystudio.dropme.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rokkystudio.dropme.AppError
import com.rokkystudio.dropme.asAppException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Предоставляет сведения о Wi‑Fi сети, через которую выполняются
 * локальные подключения DROPME.
 */
class WifiNetworkProvider(
    context: Context,
) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)

    /**
     * Содержит привязку к Wi‑Fi сети и IPv4-адрес устройства в этой сети.
     */
    data class WifiNetworkInfo(
        val network: Network,
        val ipv4Address: Inet4Address,
        val prefixLength: Int,
    )

    private data class DhcpWifiInfo(
        val ipv4Address: Inet4Address,
        val prefixLength: Int,
    )

    /**
     * Возвращает активную Wi‑Fi сеть и IPv4-адрес устройства.
     */
    fun getWifiNetworkInfo(): WifiNetworkInfo {
        return findWifiNetworkInfo() ?: throw AppError.NoWifiNetwork.asAppException()
    }

    /**
     * Ищет Wi‑Fi сеть, которую можно использовать для локальных подключений.
     */
    private fun findWifiNetworkInfo(): WifiNetworkInfo? {
        val activeNetwork = connectivityManager.activeNetwork
        if (activeNetwork != null) {
            buildWifiNetworkInfo(activeNetwork)?.let { return it }
        }

        connectivityManager.allNetworks
            .asSequence()
            .filter { it != activeNetwork }
            .mapNotNull(::buildWifiNetworkInfo)
            .sortedByDescending(::scoreNetworkInfo)
            .firstOrNull()
            ?.let { return it }

        return awaitWifiNetworkInfo()
    }

    /**
     * Собирает данные о Wi‑Fi сети и отбрасывает варианты без пригодного LAN IPv4.
     */
    private fun buildWifiNetworkInfo(network: Network): WifiNetworkInfo? {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
        if (!capabilities.isUsableWifiNetwork()) {
            return null
        }

        val linkProperties = connectivityManager.getLinkProperties(network) ?: return null
        val dhcpInfo = getWifiDhcpInfo()
        val ipv4Address = dhcpInfo?.ipv4Address ?: findIpv4Address(linkProperties) ?: return null
        val prefixLength = dhcpInfo?.prefixLength ?: findPrefixLength(linkProperties, ipv4Address) ?: return null
        val info = WifiNetworkInfo(
            network = network,
            ipv4Address = ipv4Address,
            prefixLength = prefixLength,
        )
        Log.d(
            LOG_TAG,
            "Wi-Fi candidate ${linkProperties.interfaceName ?: "<unknown>"} ${ipv4Address.hostAddress}/$prefixLength",
        )
        return info
    }

    /**
     * Читает IPv4 и маску именно Wi‑Fi DHCP, чтобы не брать туннельный VPN адрес.
     */
    @Suppress("DEPRECATION")
    private fun getWifiDhcpInfo(): DhcpWifiInfo? {
        val dhcpInfo = wifiManager?.dhcpInfo ?: return null
        if (dhcpInfo.ipAddress == 0 || dhcpInfo.netmask == 0) {
            return null
        }
        val ipv4Address = dhcpInfo.ipAddress.toInet4AddressLittleEndian() ?: return null
        val prefixLength = dhcpInfo.netmask.countMaskBits()
        if (prefixLength !in 1..30) {
            return null
        }
        Log.d(LOG_TAG, "Wi-Fi DHCP ${ipv4Address.hostAddress}/$prefixLength")
        return DhcpWifiInfo(
            ipv4Address = ipv4Address,
            prefixLength = prefixLength,
        )
    }

    /**
     * Отдаёт приоритет локальным адресам Wi‑Fi перед VPN диапазонами.
     */
    private fun scoreNetworkInfo(info: WifiNetworkInfo): Int {
        val raw = info.ipv4Address.address
        val firstOctet = raw[0].toInt() and 0xFF
        val secondOctet = raw[1].toInt() and 0xFF
        return when {
            firstOctet == 192 && secondOctet == 168 -> 3
            info.ipv4Address.isSiteLocalAddress -> 2
            else -> 1
        }
    }

    /**
     * Ожидает доступную Wi‑Fi сеть через актуальный callback API ConnectivityManager.
     */
    private fun awaitWifiNetworkInfo(): WifiNetworkInfo? {
        val selectedNetwork = AtomicReference<WifiNetworkInfo?>()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val latch = CountDownLatch(1)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val wifiInfo = buildWifiNetworkInfo(network) ?: return
                val current = selectedNetwork.get()
                if (current == null || scoreNetworkInfo(wifiInfo) > scoreNetworkInfo(current)) {
                    selectedNetwork.set(wifiInfo)
                }
                if (scoreNetworkInfo(wifiInfo) >= PREFERRED_WIFI_SCORE) {
                    latch.countDown()
                }
            }

            override fun onUnavailable() {
                latch.countDown()
            }
        }

        registerWifiCallback(request, callback)
        return try {
            latch.await(WIFI_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            selectedNetwork.get()
        } finally {
            runCatching {
                connectivityManager.unregisterNetworkCallback(callback)
            }
        }
    }

    /**
     * Находит IPv4-адрес устройства в свойствах Wi‑Fi сети.
     */
    private fun findIpv4Address(linkProperties: LinkProperties): Inet4Address? {
        linkProperties.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .sortedByDescending(::scoreIpv4Address)
            .firstOrNull()
            ?.let { return it }

        val interfaceName = linkProperties.interfaceName ?: return null
        val networkInterface = NetworkInterface.getByName(interfaceName) ?: return null
        return networkInterface.inetAddresses
            .toList()
            .filterIsInstance<Inet4Address>()
            .sortedByDescending(::scoreIpv4Address)
            .firstOrNull()
    }

    /**
     * Поднимает наверх типичный LAN IPv4 и опускает адреса VPN/служебных диапазонов.
     */
    private fun scoreIpv4Address(address: Inet4Address): Int {
        val raw = address.address
        val firstOctet = raw[0].toInt() and 0xFF
        val secondOctet = raw[1].toInt() and 0xFF
        return when {
            address.isLoopbackAddress || address.isLinkLocalAddress -> 0
            firstOctet == 192 && secondOctet == 168 -> 4
            address.isSiteLocalAddress -> 3
            else -> 1
        }
    }

    /**
     * Преобразует little-endian int из Android Wi‑Fi API в Inet4Address.
     */
    private fun Int.toInet4AddressLittleEndian(): Inet4Address? {
        val bytes = byteArrayOf(
            (this and 0xFF).toByte(),
            ((this ushr 8) and 0xFF).toByte(),
            ((this ushr 16) and 0xFF).toByte(),
            ((this ushr 24) and 0xFF).toByte(),
        )
        return runCatching {
            java.net.InetAddress.getByAddress(bytes) as Inet4Address
        }.getOrNull()
    }

    /**
     * Считает количество единиц в маске подсети.
     */
    private fun Int.countMaskBits(): Int {
        return Integer.bitCount(this)
    }

    /**
     * Находит длину префикса для IPv4-адреса Wi‑Fi сети.
     */
    private fun findPrefixLength(linkProperties: LinkProperties, ipv4Address: Inet4Address): Int? {
        return linkProperties.linkAddresses
            .firstOrNull { it.address == ipv4Address }
            ?.prefixLength
    }

    /**
     * Проверяет наличие Wi‑Fi транспорта у сети Android.
     */
    private fun NetworkCapabilities.isUsableWifiNetwork(): Boolean {
        return hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Регистрирует callback поиска Wi‑Fi сети через доступный API уровня SDK.
     */
    private fun registerWifiCallback(
        request: NetworkRequest,
        callback: ConnectivityManager.NetworkCallback,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            connectivityManager.registerBestMatchingNetworkCallback(
                request,
                callback,
                Handler(Looper.getMainLooper()),
            )
        } else {
            connectivityManager.registerNetworkCallback(request, callback)
        }
    }

    private companion object {
        const val LOG_TAG = "DROPME"
        const val WIFI_CALLBACK_TIMEOUT_MS = 750L
        const val PREFERRED_WIFI_SCORE = 3
    }
}


