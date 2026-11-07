package com.rokkystudio.dropme.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.rokkystudio.dropme.IncomingTransferActivity
import com.rokkystudio.dropme.R
import java.io.File
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Сопоставляет входящие HTTP-передачи с уведомлениями Android и экраном хода передачи.
 *
 * Смахивание активного уведомления отменяет соответствующую передачу и закрывает её сокет.
 * Уведомления завершённых передач исчезают автоматически.
 */
object IncomingTransferCoordinator {
    enum class Status {
        RECEIVING,
        VERIFYING,
        COMPLETED,
        FAILED,
        CANCELED,
    }

    data class Snapshot(
        val id: Int,
        val fileName: String,
        val senderName: String,
        val receivedBytes: Long,
        val totalBytes: Long,
        val status: Status,
        val savedPath: String? = null,
    )

    class Transfer internal constructor(
        val id: Int,
        internal val socket: Socket,
        @Volatile internal var snapshot: Snapshot,
    ) {
        @Volatile
        var cancelled: Boolean = false
            internal set
    }

    private val nextNotificationId = AtomicInteger(5200)
    private val active = ConcurrentHashMap<Int, Transfer>()
    private val recent = LinkedHashMap<Int, Snapshot>()
    private val recentLock = Any()

    /**
     * Регистрирует входящий файл и показывает уведомление со ссылкой на его экран.
     */
    fun begin(
        context: Context,
        socket: Socket,
        fileName: String,
        senderName: String,
        totalBytes: Long,
    ): Transfer {
        createChannel(context)
        val id = nextNotificationId.incrementAndGet()
        val transfer = Transfer(
            id = id,
            socket = socket,
            snapshot = Snapshot(
                id = id,
                fileName = fileName,
                senderName = senderName,
                receivedBytes = 0L,
                totalBytes = totalBytes,
                status = Status.RECEIVING,
            ),
        )
        active[id] = transfer
        synchronized(transfer) {
            showProgress(context, transfer.snapshot)
        }
        publishUpdate(context, id)
        return transfer
    }

    /**
     * Обновляет число принятых байтов активной передачи и отображает прогресс до 99%.
     * Состояние 100% соответствует только сохранённому и проверенному файлу.
     */
    fun progress(context: Context, transfer: Transfer, receivedBytes: Long) {
        synchronized(transfer) {
            if (active[transfer.id] !== transfer) {
                return
            }
            transfer.snapshot = transfer.snapshot.copy(receivedBytes = receivedBytes)
            showProgress(context, transfer.snapshot)
            publishUpdate(context, transfer.id)
        }
    }

    /**
     * Показывает проверку целостности и сохранение файла после получения его тела.
     */
    fun verifying(context: Context, transfer: Transfer) {
        synchronized(transfer) {
            if (active[transfer.id] !== transfer) {
                return
            }
            transfer.snapshot = transfer.snapshot.copy(status = Status.VERIFYING)
            showProgress(context, transfer.snapshot)
            publishUpdate(context, transfer.id)
        }
    }

    /**
     * Завершает активную передачу после проверки файла и показывает краткое уведомление
     * с переходом к результату. Возвращает false при уже запрошенной отмене.
     */
    fun complete(context: Context, transfer: Transfer, file: File): Boolean {
        synchronized(transfer) {
            if (!active.remove(transfer.id, transfer)) {
                return false
            }
            val snapshot = transfer.snapshot.copy(
                receivedBytes = file.length(),
                status = Status.COMPLETED,
                savedPath = file.absolutePath,
            )
            transfer.snapshot = snapshot
            remember(snapshot)
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.cancel(snapshot.id)
            manager.notify(
                snapshot.id,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher_monochrome)
                    .setContentTitle(context.getString(R.string.incoming_transfer_saved, file.name))
                    .setContentText(context.getString(R.string.incoming_transfer_saved_detail, file.parent.orEmpty()))
                    .setContentIntent(openTransferIntent(context, snapshot.id))
                    .setAutoCancel(true)
                    .setTimeoutAfter(SUCCESS_NOTIFICATION_DURATION_MS)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build(),
            )
            publishUpdate(context, snapshot.id)
        }

        runCatching {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        }.onFailure { throwable ->
            Log.w("DROPME", "Could not publish saved file in MediaStore", throwable)
        }
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                context,
                context.getString(R.string.incoming_transfer_saved, file.name),
                Toast.LENGTH_SHORT,
            ).show()
        }
        return true
    }

    /**
     * Завершает передачу с ошибкой, освобождает активный ID и показывает уведомление
     * с ограниченным временем отображения.
     */
    fun fail(context: Context, transfer: Transfer) {
        synchronized(transfer) {
            if (!active.remove(transfer.id, transfer)) {
                return
            }
            val snapshot = transfer.snapshot.copy(status = Status.FAILED)
            transfer.snapshot = snapshot
            remember(snapshot)
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.cancel(snapshot.id)
            manager.notify(
                snapshot.id,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher_monochrome)
                    .setContentTitle(context.getString(R.string.incoming_transfer_failed, snapshot.fileName))
                    .setContentIntent(openTransferIntent(context, snapshot.id))
                    .setAutoCancel(true)
                    .setTimeoutAfter(FAILURE_NOTIFICATION_DURATION_MS)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build(),
            )
            publishUpdate(context, snapshot.id)
        }
    }

    /**
     * Отменяет одну активную передачу, закрывает сетевой сокет и скрывает уведомление.
     * Завершённые передачи не затрагиваются.
     */
    fun cancel(context: Context, id: Int) {
        val transfer = active[id] ?: return
        synchronized(transfer) {
            if (!active.remove(id, transfer)) {
                return
            }
            transfer.cancelled = true
            val snapshot = transfer.snapshot.copy(status = Status.CANCELED)
            transfer.snapshot = snapshot
            remember(snapshot)
            context.getSystemService(NotificationManager::class.java).cancel(id)
            runCatching { transfer.socket.close() }
            publishUpdate(context, id)
        }
    }

    /**
     * Отменяет все входящие передачи при остановке сетевого сервера.
     */
    fun cancelAll(context: Context) {
        active.keys.toList().forEach { id ->
            cancel(context, id)
        }
    }

    /**
     * Возвращает актуальное или последнее состояние передачи для экрана уведомления.
     */
    fun get(id: Int): Snapshot? {
        return active[id]?.snapshot ?: synchronized(recentLock) { recent[id] }
    }

    /**
     * Сохраняет последние результаты без удержания сокетов завершённых передач.
     */
    private fun remember(snapshot: Snapshot) {
        synchronized(recentLock) {
            recent[snapshot.id] = snapshot
            while (recent.size > MAX_RECENT_TRANSFERS) {
                recent.remove(recent.keys.first())
            }
        }
    }

    /**
     * Обновляет системное уведомление приёма и связывает смахивание с отменой HTTP-сессии.
     */
    private fun showProgress(context: Context, snapshot: Snapshot) {
        val percent = if (snapshot.totalBytes <= 0L) {
            0
        } else {
            ((snapshot.receivedBytes * 100L) / snapshot.totalBytes).toInt().coerceIn(0, 99)
        }
        val verifying = snapshot.status == Status.VERIFYING
        val text = if (verifying) {
            context.getString(R.string.incoming_transfer_verifying)
        } else {
            context.getString(
                R.string.incoming_transfer_progress,
                snapshot.senderName,
                percent,
                Formatter.formatShortFileSize(context, snapshot.receivedBytes),
                Formatter.formatShortFileSize(context, snapshot.totalBytes),
            )
        }
        val cancelIntent = PendingIntent.getBroadcast(
            context,
            snapshot.id,
            Intent(context, IncomingTransferActionReceiver::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_TRANSFER_ID, snapshot.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.incoming_transfer_title, snapshot.fileName))
            .setContentText(text)
            .setContentIntent(openTransferIntent(context, snapshot.id))
            .setDeleteIntent(cancelIntent)
            .addAction(
                R.mipmap.ic_launcher_monochrome,
                context.getString(R.string.incoming_transfer_cancel),
                cancelIntent,
            )
            .setProgress(100, percent, verifying)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        context.getSystemService(NotificationManager::class.java).notify(snapshot.id, notification)
    }

    /**
     * Создаёт уникальное действие открытия экрана конкретной входящей передачи.
     */
    private fun openTransferIntent(context: Context, id: Int): PendingIntent {
        return PendingIntent.getActivity(
            context,
            id,
            Intent(context, IncomingTransferActivity::class.java)
                .putExtra(EXTRA_TRANSFER_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Оповещает открытый экран передачи об обновлении прогресса или результата.
     */
    private fun publishUpdate(context: Context, id: Int) {
        context.sendBroadcast(
            Intent(ACTION_TRANSFER_UPDATED)
                .setPackage(context.packageName)
                .putExtra(EXTRA_TRANSFER_ID, id),
        )
    }

    /**
     * Создаёт канал для уведомлений входящих файлов.
     */
    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.incoming_transfer_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.incoming_transfer_channel_description)
            },
        )
    }

    const val ACTION_CANCEL = "com.rokkystudio.dropme.action.CANCEL_INCOMING_TRANSFER"
    const val ACTION_TRANSFER_UPDATED = "com.rokkystudio.dropme.action.INCOMING_TRANSFER_UPDATED"
    const val EXTRA_TRANSFER_ID = "transfer_id"

    private const val CHANNEL_ID = "dropme_incoming_transfers"
    private const val SUCCESS_NOTIFICATION_DURATION_MS = 6_000L
    private const val FAILURE_NOTIFICATION_DURATION_MS = 12_000L
    private const val MAX_RECENT_TRANSFERS = 32
}
