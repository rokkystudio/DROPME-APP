package com.rokkystudio.dropme.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.rokkystudio.dropme.R
import com.rokkystudio.dropme.device.DeviceIdentityStore
import com.rokkystudio.dropme.network.WindowsServer

/**
 * Показывает локальное устройство и найденные DROPME-устройства в едином списке подключений.
 */
class ConnectionDevicesScreen(
    private val context: Context,
    private val container: LinearLayout,
    private val onLocalDeviceSelected: () -> Unit,
    private val onRemoteDeviceSelected: (WindowsServer) -> Unit,
) {
    private val inflater = LayoutInflater.from(context)

    /**
     * Отображает локальное устройство первым, затем найденные сетевые устройства.
     */
    fun show(
        localIdentity: DeviceIdentityStore.DeviceIdentity,
        localIp: String,
        remoteDevices: List<WindowsServer>,
    ) {
        container.removeAllViews()
        container.addView(
            createRow(
                title = localIdentity.name,
                subtitle = context.getString(
                    R.string.connection_local_device_info,
                    localIp.ifBlank { context.getString(R.string.main_device_ip_unavailable) },
                ),
                iconResId = iconForDeviceType(localIdentity.type),
                onClick = onLocalDeviceSelected,
            ),
        )

        remoteDevices.forEach { device ->
            container.addView(
                createRow(
                    title = device.deviceName,
                    subtitle = context.getString(
                        R.string.connection_remote_device_info,
                        device.host,
                        device.deviceId,
                    ),
                    iconResId = iconForDeviceType(device.deviceType),
                    onClick = { onRemoteDeviceSelected(device) },
                ),
            )
        }
    }

    private fun createRow(
        title: String,
        subtitle: String,
        iconResId: Int,
        onClick: () -> Unit,
    ): View {
        val row = inflater.inflate(R.layout.share_server_row, container, false)
        row.findViewById<ImageView>(R.id.shareServerRowIcon).setImageResource(iconResId)
        row.findViewById<TextView>(R.id.shareServerRowTitle).text = title
        row.findViewById<TextView>(R.id.shareServerRowSubtitle).text = subtitle
        row.setOnClickListener { onClick() }
        return row
    }

    /**
     * Возвращает отдельную пиктограмму для каждого поддерживаемого типа устройства.
     */
    private fun iconForDeviceType(deviceType: String): Int {
        return when (deviceType.lowercase()) {
            "windows" -> R.drawable.ic_device_windows
            "android" -> R.drawable.ic_device_android
            "iphone", "ios" -> R.drawable.ic_device_iphone
            "mac", "macos", "apple_laptop" -> R.drawable.ic_device_apple_laptop
            "linux" -> R.drawable.ic_device_linux
            else -> R.drawable.ic_computer
        }
    }
}
