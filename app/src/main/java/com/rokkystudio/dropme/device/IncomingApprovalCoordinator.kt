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
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Координирует синхронный сетевой запрос разрешения с пользовательским диалогом Android.
 *
 * Одинаковые pending-запросы объединяются в один диалог, одновременно показывается не больше
 * одного подтверждения, а один IP временно привязывается к одному device ID.
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

    private data class IpBinding(
        val deviceId: String,
        var lastSeenAt: Long,
    )

    private val lock = Any()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val pendingByIdentity = HashMap<String, Pending>()
    private val ipBindings = HashMap<String, IpBinding>()
    private val approvalUiGate = Semaphore(1, true)

    /**
     * Проверяет доверие и при необходимости показывает один popup-dialog.
     * Параллельный одинаковый запрос ждёт уже открытый диалог.
     */
    fun authorize(
        context: Context,
        deviceId: String,
        deviceName: String,
        remoteIp: String,
        purpose: String,
        itemName: String? = null,
    ): Boolean {
        if (!bindIdentity(remoteIp, deviceId)) {
            return false
        }

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

        val identityKey = "$remoteIp:$deviceId:$purpose"
        val state: Pending
        val owner: Boolean
        synchronized(lock) {
            val existing = pendingByIdentity[identityKey]
            if (existing != null) {
                state = existing
                owner = false
            } else {
                val request = Request(
                    requestId = UUID.randomUUID().toString(),
                    deviceId = deviceId,
                    deviceName = deviceName,
                    remoteIp = remoteIp,
                    purpose = purpose,
                    itemName = itemName,
                )
                state = Pending(request)
                pending[request.requestId] = state
                pendingByIdentity[identityKey] = state
                owner = true
            }
        }

        if (!owner) {
            state.latch.await(APPROVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            return state.resolved && state.allowed
        }

        var gateTaken = false
        try {
            gateTaken = approvalUiGate.tryAcquire(
                APPROVAL_QUEUE_WAIT_SECONDS,
                TimeUnit.SECONDS,
            )
            if (!gateTaken) {
                return false
            }

            showApprovalUi(context, state.request)
            state.latch.await(APPROVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            return state.resolved && state.allowed
        } finally {
            if (!state.resolved) {
                state.allowed = false
                state.resolved = true
                state.latch.countDown()
            }

            pending.remove(state.request.requestId)
            synchronized(lock) {
                pendingByIdentity.remove(identityKey, state)
            }
            context.getSystemService(NotificationManager::class.java)
                .cancel(APPROVAL_NOTIFICATION_ID)

            if (gateTaken) {
                approvalUiGate.release()
            }
        }
    }

    fun get(requestId: String): Request? = pending[requestId]?.request

    /**
     * Завершает активный popup-dialog и при необходимости сохраняет постоянное решение.
     */
    fun resolve(
        context: Context,
        requestId: String,
        allowed: Boolean,
        remember: Boolean,
    ) {
        val state = pending[requestId] ?: return
        if (state.resolved) {
            return
        }

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

    /**
     * На короткое время закрепляет IP за первым device ID.
     * Запрос с другим ID с того же IP отклоняется без показа UI.
     */
    private fun bindIdentity(remoteIp: String, deviceId: String): Boolean {
        if (remoteIp.isBlank()) {
            return false
        }

        val now = System.currentTimeMillis()
        synchronized(lock) {
            val iterator = ipBindings.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.lastSeenAt >= IP_BINDING_LIFETIME_MS) {
                    iterator.remove()
                }
            }

            val binding = ipBindings[remoteIp]
            if (binding != null) {
                if (binding.deviceId != deviceId) {
                    return false
                }
                binding.lastSeenAt = now
                return true
            }

            ipBindings[remoteIp] = IpBinding(
                deviceId = deviceId,
                lastSeenAt = now,
            )
            return true
        }
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
            APPROVAL_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val allowIntent = PendingIntent.getBroadcast(
            context,
            APPROVAL_ALLOW_REQUEST_CODE,
            Intent(context, ApprovalActionReceiver::class.java)
                .setAction(ApprovalActionReceiver.ACTION_ALLOW)
                .putExtra(ApprovalActionReceiver.EXTRA_REQUEST_ID, request.requestId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val denyIntent = PendingIntent.getBroadcast(
            context,
            APPROVAL_DENY_REQUEST_CODE,
            Intent(context, ApprovalActionReceiver::class.java)
                .setAction(ApprovalActionReceiver.ACTION_DENY)
                .putExtra(ApprovalActionReceiver.EXTRA_REQUEST_ID, request.requestId),
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
            .addAction(
                R.mipmap.ic_launcher_monochrome,
                context.getString(R.string.approval_deny),
                denyIntent,
            )
            .addAction(
                R.mipmap.ic_launcher_monochrome,
                context.getString(R.string.approval_allow),
                allowIntent,
            )
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(APPROVAL_NOTIFICATION_ID, notification)
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

    private const val APPROVAL_TIMEOUT_SECONDS = 60L
    private const val APPROVAL_QUEUE_WAIT_SECONDS = 2L
    private const val IP_BINDING_LIFETIME_MS = 10L * 60L * 1000L
    private const val NOTIFICATION_CHANNEL_ID = "dropme_approval"
    private const val APPROVAL_NOTIFICATION_ID = 5000
    private const val APPROVAL_ALLOW_REQUEST_CODE = 5001
    private const val APPROVAL_DENY_REQUEST_CODE = 5002
}
