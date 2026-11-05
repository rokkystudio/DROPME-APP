package com.rokkystudio.dropme.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.util.Log
import com.rokkystudio.dropme.AppError
import com.rokkystudio.dropme.asAppException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Предоставляет адрес и сетевой маршрут Wi-Fi для локального взаимодействия устройств DROPME.
 * Отслеживает появление, потерю и изменение IP-адресов независимо от сети по умолчанию.
 */
class WifiNetworkProvider(
    context: Context,
) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)

    /**
     * Содержит Android Network, назначенный IPv4 и длину префикса локальной Wi-Fi подсети.
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
     * Возвращает готовую Wi-Fi сеть или кратковременно ожидает назначение IPv4.
     */
    fun getWifiNetworkInfo(): WifiNetworkInfo {
        return findWifiNetworkInfo() ?: awaitWifiNetworkInfo()
            ?: throw AppError.NoWifiNetwork.asAppException()
    }

    /**
     * Возвращает доступную Wi-Fi сеть с LAN IPv4 независимо от default network Android.
     * При отсутствии настроенного адреса возвращает null без ожидания.
     */
    fun findWifiNetworkInfo(): WifiNetworkInfo? {
        val activeNetwork = connectivityManager.activeNetwork
        activeNetwork?.let(::buildWifiNetworkInfo)?.let { return it }

        for (network in connectivityManager.allNetworks) {
            if (network != activeNetwork) {
                buildWifiNetworkInfo(network)?.let { return it }
            }
        }
        return null
    }

    /**
     * Регистрирует длительное наблюдение за Wi-Fi интерфейсами, их адресами и параметрами.
     * Возвращённый callback освобождается через unregisterNetworkChanges.
     */
    fun registerNetworkChanges(onChanged: () -> Unit): ConnectivityManager.NetworkCallback {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onChanged()
            }

            override fun onLost(network: Network) {
                onChanged()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                onChanged()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                onChanged()
            }
        }
        registerWifiCallback(request, callback)
        return callback
    }

    /**
     * Отменяет наблюдение за Wi-Fi интерфейсами для указанного callback.
     */
    fun unregisterNetworkChanges(callback: ConnectivityManager.NetworkCallback) {
        connectivityManager.unregisterNetworkCallback(callback)
    }

    /**
     * Создаёт сведения о Wi-Fi маршруте только при наличии пригодного LAN IPv4 и префикса.
     */
    private fun buildWifiNetworkInfo(network: Network): WifiNetworkInfo? {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
        if (!capabilities.isUsableWifiNetwork()) {
            return null
        }

        val linkProperties = connectivityManager.getLinkProperties(network) ?: return null
        val dhcpInfo = getWifiDhcpInfo()
        val linkIpv4Address = findIpv4Address(linkProperties) ?: return null
        val linkPrefixLength = findPrefixLength(linkProperties, linkIpv4Address) ?: return null
        val usesDhcpAddress = dhcpInfo?.ipv4Address == linkIpv4Address
        val ipv4Address = if (usesDhcpAddress) dhcpInfo.ipv4Address else linkIpv4Address
        val prefixLength = if (usesDhcpAddress) dhcpInfo.prefixLength else linkPrefixLength
        val info = WifiNetworkInfo(
            network = network,
            ipv4Address = ipv4Address,
            prefixLength = prefixLength,
        )
        Log.d(
            LOG_TAG,
            "Wi-Fi candidate ${linkProperties.interfaceName ?: "<unknown>"} ${ipv4Address.hostAddress}/$prefixLength " +
                "transportInfo=${capabilities.transportInfo?.javaClass?.simpleName ?: "<none>"} " +
                "dhcpMatch=$usesDhcpAddress",
        )
        return info
    }

    /**
     * Читает IPv4 и маску из Wi-Fi DHCP, не используя адреса VPN-интерфейсов.
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
     * Ожидает назначение IPv4 через callbacks доступности и настройки Wi-Fi сети.
     */
    private fun awaitWifiNetworkInfo(): WifiNetworkInfo? {
        val selectedNetwork = AtomicReference<WifiNetworkInfo?>()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val latch = CountDownLatch(1)
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun checkNetwork(network: Network) {
                val wifiInfo = buildWifiNetworkInfo(network) ?: return
                selectedNetwork.set(wifiInfo)
                latch.countDown()
            }

            override fun onAvailable(network: Network) {
                checkNetwork(network)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                checkNetwork(network)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                checkNetwork(network)
            }

            override fun onUnavailable() {
                latch.countDown()
            }
        }

        registerWifiCallback(request, callback)
        return try {
            findWifiNetworkInfo()?.let { return it }
            latch.await(WIFI_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            findWifiNetworkInfo() ?: selectedNetwork.get()
        } finally {
            unregisterNetworkChanges(callback)
        }
    }

    /**
     * Возвращает пригодный IPv4 из LinkProperties или сетевого интерфейса Android.
     */
    private fun findIpv4Address(linkProperties: LinkProperties): Inet4Address? {
        linkProperties.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .filter { scoreIpv4Address(it) > 0 }
            .sortedByDescending(::scoreIpv4Address)
            .firstOrNull()
            ?.let { return it }

        val interfaceName = linkProperties.interfaceName ?: return null
        val networkInterface = NetworkInterface.getByName(interfaceName) ?: return null
        return networkInterface.inetAddresses
            .toList()
            .filterIsInstance<Inet4Address>()
            .filter { scoreIpv4Address(it) > 0 }
            .sortedByDescending(::scoreIpv4Address)
            .firstOrNull()
    }

    /**
     * Приоритизирует локальные IPv4, исключая loopback и link-local адреса.
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
     * Преобразует little-endian адрес Android DHCP в Inet4Address.
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
     * Возвращает количество установленных битов IPv4 маски.
     */
    private fun Int.countMaskBits(): Int {
        return Integer.bitCount(this)
    }

    /**
     * Находит префикс для указанного IPv4 в LinkProperties сети.
     */
    private fun findPrefixLength(linkProperties: LinkProperties, ipv4Address: Inet4Address): Int? {
        return linkProperties.linkAddresses
            .firstOrNull { it.address == ipv4Address }
            ?.prefixLength
    }

    /**
     * Проверяет наличие Wi-Fi транспорта без VPN у сети Android.
     */
    private fun NetworkCapabilities.isUsableWifiNetwork(): Boolean {
        return hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /**
     * Подписывает callback на все Wi-Fi сети, включая не выбранные системой по умолчанию.
     */
    private fun registerWifiCallback(
        request: NetworkRequest,
        callback: ConnectivityManager.NetworkCallback,
    ) {
        connectivityManager.registerNetworkCallback(request, callback)
    }

    private companion object {
        const val LOG_TAG = "DROPME"
        const val WIFI_CALLBACK_TIMEOUT_MS = 750L
    }
}


