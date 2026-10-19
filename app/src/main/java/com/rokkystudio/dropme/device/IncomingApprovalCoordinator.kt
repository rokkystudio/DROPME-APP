package com.rokkystudio.dropme.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.rokkystudio.dropme.DeviceApprovalActivity
import com.rokkystudio.dropme.R
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Координирует синхронный сетевой запрос разрешения с пользовательским диалогом Android.
 */
object IncomingApprovalCoordinator {
    data class Request(
        val requestId: String,
        val deviceId: String,
        val deviceName: String,
        val remoteIp: String,
        val purpose: String,
        val itemName: String?,
    )

    private data class Pending(
        val request: Request,
        val latch: CountDownLatch = CountDownLatch(1),
        @Volatile var allowed: Boolean = false,
        @Volatile var resolved: Boolean = false,
    )

    private val pending = ConcurrentHashMap<String, Pending>()

    fun authorize(
        context: Context,
        deviceId: String,
        deviceName: String,
        remoteIp: String,
        purpose: String,
        itemName: String? = null,
    ): Boolean {
        val trustStore = DeviceTrustStore(context)
        when (trustStore.getDecision(deviceId)) {
            DeviceTrustStore.Decision.ALLOWED -> {
                trustStore.touch(deviceId, deviceName, remoteIp)
                return true
            }

            DeviceTrustStore.Decision.BLOCKED -> {
                trustStore.touch(deviceId, deviceName, remoteIp)
                return false
            }

            DeviceTrustStore.Decision.UNKNOWN -> Unit
        }

        val request = Request(
            requestId = UUID.randomUUID().toString(),
            deviceId = deviceId,
            deviceName = deviceName,
            remoteIp = remoteIp,
            purpose = purpose,
            itemName = itemName,
        )
        val state = Pending(request)
        pending[request.requestId] = state
        showApprovalUi(context, request)

        state.latch.await(APPROVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        pending.remove(request.requestId)
        context.getSystemService(NotificationManager::class.java)
            .cancel(notificationId(request.requestId))
        return state.resolved && state.allowed
    }

    fun get(requestId: String): Request? = pending[requestId]?.request

    fun resolve(
        context: Context,
        requestId: String,
        allowed: Boolean,
        remember: Boolean,
    ) {
        val state = pending[requestId] ?: return
        if (remember) {
            DeviceTrustStore(context).remember(
                deviceId = state.request.deviceId,
                deviceName = state.request.deviceName,
                ip = state.request.remoteIp,
                decision = if (allowed) {
                    DeviceTrustStore.Decision.ALLOWED
                } else {
                    DeviceTrustStore.Decision.BLOCKED
                },
            )
        }
        state.allowed = allowed
        state.resolved = true
        state.latch.countDown()
    }

    private fun showApprovalUi(context: Context, request: Request) {
        createNotificationChannel(context)
        val intent = DeviceApprovalActivity.createIntent(context, request.requestId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        runCatching {
            context.startActivity(intent)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId(request.requestId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.approval_notification_title))
            .setContentText(
                context.getString(
                    R.string.approval_notification_text,
                    request.deviceName,
                    request.remoteIp,
                ),
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId(request.requestId), notification)
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.approval_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.approval_channel_description)
            },
        )
    }

    private fun notificationId(requestId: String): Int {
        return NOTIFICATION_ID_BASE + (requestId.hashCode() and 0x0fff)
    }

    private const val APPROVAL_TIMEOUT_SECONDS = 60L
    private const val NOTIFICATION_CHANNEL_ID = "dropme_approval"
    private const val NOTIFICATION_ID_BASE = 5000
}
