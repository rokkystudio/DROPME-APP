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
 * РџСЂРµРґРѕСЃС‚Р°РІР»СЏРµС‚ СЃРІРµРґРµРЅРёСЏ Рѕ WiвЂ‘Fi СЃРµС‚Рё, С‡РµСЂРµР· РєРѕС‚РѕСЂСѓСЋ РІС‹РїРѕР»РЅСЏСЋС‚СЃСЏ
 * Р»РѕРєР°Р»СЊРЅС‹Рµ РїРѕРґРєР»СЋС‡РµРЅРёСЏ DROPME.
 */
class WifiNetworkProvider(
    context: Context,
) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)

    /**
     * РЎРѕРґРµСЂР¶РёС‚ РїСЂРёРІСЏР·РєСѓ Рє WiвЂ‘Fi СЃРµС‚Рё Рё IPv4-Р°РґСЂРµСЃ СѓСЃС‚СЂРѕР№СЃС‚РІР° РІ СЌС‚РѕР№ СЃРµС‚Рё.
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
     * Р’РѕР·РІСЂР°С‰Р°РµС‚ Р°РєС‚РёРІРЅСѓСЋ WiвЂ‘Fi СЃРµС‚СЊ Рё IPv4-Р°РґСЂРµСЃ СѓСЃС‚СЂРѕР№СЃС‚РІР°.
     */
    fun getWifiNetworkInfo(): WifiNetworkInfo {
        return findWifiNetworkInfo() ?: throw AppError.NoWifiNetwork.asAppException()
    }

    /**
     * Ищет активную Wi‑Fi сеть, доступную для локальных подключений, и ожидает callback сети при отсутствии готовых данных.
     */
    private fun findWifiNetworkInfo(): WifiNetworkInfo? {
        connectivityManager.activeNetwork
            ?.let(::buildWifiNetworkInfo)
            ?.let { return it }

        return awaitWifiNetworkInfo()
    }

    /**
     * РЎРѕР±РёСЂР°РµС‚ РґР°РЅРЅС‹Рµ Рѕ WiвЂ‘Fi СЃРµС‚Рё Рё РѕС‚Р±СЂР°СЃС‹РІР°РµС‚ РІР°СЂРёР°РЅС‚С‹ Р±РµР· РїСЂРёРіРѕРґРЅРѕРіРѕ LAN IPv4.
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
     * Р§РёС‚Р°РµС‚ IPv4 Рё РјР°СЃРєСѓ РёРјРµРЅРЅРѕ WiвЂ‘Fi DHCP, С‡С‚РѕР±С‹ РЅРµ Р±СЂР°С‚СЊ С‚СѓРЅРЅРµР»СЊРЅС‹Р№ VPN Р°РґСЂРµСЃ.
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
     * РћС‚РґР°С‘С‚ РїСЂРёРѕСЂРёС‚РµС‚ Р»РѕРєР°Р»СЊРЅС‹Рј Р°РґСЂРµСЃР°Рј WiвЂ‘Fi РїРµСЂРµРґ VPN РґРёР°РїР°Р·РѕРЅР°РјРё.
     */
    private fun scoreNetworkInfo(info: WifiNetworkInfo): Int {
        val raw = info.ipv4Address.address
        val firstOctet = raw[0].toInt() and 0xFF
        val secondOctet = raw[1].toInt() and 0xFF
        return when {
            firstOctet == 192 && secondOctet == 168 -> 4
            firstOctet == 10 -> 3
            firstOctet == 172 && secondOctet in 16..31 -> 2
            info.ipv4Address.isSiteLocalAddress -> 1
            else -> 1
        }
    }

    /**
     * РћР¶РёРґР°РµС‚ РґРѕСЃС‚СѓРїРЅСѓСЋ WiвЂ‘Fi СЃРµС‚СЊ С‡РµСЂРµР· Р°РєС‚СѓР°Р»СЊРЅС‹Р№ callback API ConnectivityManager.
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
     * РќР°С…РѕРґРёС‚ IPv4-Р°РґСЂРµСЃ СѓСЃС‚СЂРѕР№СЃС‚РІР° РІ СЃРІРѕР№СЃС‚РІР°С… WiвЂ‘Fi СЃРµС‚Рё.
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
     * РџРѕРґРЅРёРјР°РµС‚ РЅР°РІРµСЂС… С‚РёРїРёС‡РЅС‹Р№ LAN IPv4 Рё РѕРїСѓСЃРєР°РµС‚ Р°РґСЂРµСЃР° VPN/СЃР»СѓР¶РµР±РЅС‹С… РґРёР°РїР°Р·РѕРЅРѕРІ.
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
     * РџСЂРµРѕР±СЂР°Р·СѓРµС‚ little-endian int РёР· Android WiвЂ‘Fi API РІ Inet4Address.
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
     * РЎС‡РёС‚Р°РµС‚ РєРѕР»РёС‡РµСЃС‚РІРѕ РµРґРёРЅРёС† РІ РјР°СЃРєРµ РїРѕРґСЃРµС‚Рё.
     */
    private fun Int.countMaskBits(): Int {
        return Integer.bitCount(this)
    }

    /**
     * РќР°С…РѕРґРёС‚ РґР»РёРЅСѓ РїСЂРµС„РёРєСЃР° РґР»СЏ IPv4-Р°РґСЂРµСЃР° WiвЂ‘Fi СЃРµС‚Рё.
     */
    private fun findPrefixLength(linkProperties: LinkProperties, ipv4Address: Inet4Address): Int? {
        return linkProperties.linkAddresses
            .firstOrNull { it.address == ipv4Address }
            ?.prefixLength
    }

    /**
     * РџСЂРѕРІРµСЂСЏРµС‚ РЅР°Р»РёС‡РёРµ WiвЂ‘Fi С‚СЂР°РЅСЃРїРѕСЂС‚Р° Сѓ СЃРµС‚Рё Android.
     */
    private fun NetworkCapabilities.isUsableWifiNetwork(): Boolean {
        return hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /**
     * Р РµРіРёСЃС‚СЂРёСЂСѓРµС‚ callback РїРѕРёСЃРєР° WiвЂ‘Fi СЃРµС‚Рё С‡РµСЂРµР· РґРѕСЃС‚СѓРїРЅС‹Р№ API СѓСЂРѕРІРЅСЏ SDK.
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


