package com.rokkystudio.dropme.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import badfox.logic.neoui.NeoNotificationViews
import com.rokkystudio.dropme.MainActivity
import com.rokkystudio.dropme.R
import com.rokkystudio.dropme.device.DeviceIdentityStore
import com.rokkystudio.dropme.network.AndroidDeviceServer
import com.rokkystudio.dropme.network.AndroidWebDavServer
import com.rokkystudio.dropme.network.WifiNetworkProvider
import com.rokkystudio.dropme.storage.StorageRootsRepository
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Поддерживает Android-устройство доступным для других DROPME-устройств в локальной Wi-Fi сети.
 *
 * Control endpoint работает на фиксированном порту. Файловый backend запускается независимо,
 * когда приложению доступны опубликованные корни хранилища.
 */
class DropMeDeviceService : Service() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val serverLock = Any()
    private val networkHandler = Handler(Looper.getMainLooper())
    private val networkSyncRunnable = Runnable {
        if (running && identityStore.isDeviceServiceEnabled()) {
            executor.execute {
                synchronized(serverLock) {
                    if (running && identityStore.isDeviceServiceEnabled()) {
                        reconcileNetwork()
                    }
                }
            }
        }
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private lateinit var identityStore: DeviceIdentityStore
    private lateinit var wifiNetworkProvider: WifiNetworkProvider
    private lateinit var storageRootsRepository: StorageRootsRepository
    private lateinit var deviceServer: AndroidDeviceServer
    private lateinit var webDavServer: AndroidWebDavServer

    @Volatile
    private var activeNetwork: Network? = null

    @Volatile
    private var activeHost: String = ""

    @Volatile
    private var activeFilePort: Int = 0

    override fun onCreate() {
        super.onCreate()
        identityStore = DeviceIdentityStore(applicationContext)
        wifiNetworkProvider = WifiNetworkProvider(applicationContext)
        storageRootsRepository = StorageRootsRepository(applicationContext)
        deviceServer = AndroidDeviceServer()
        webDavServer = AndroidWebDavServer(applicationContext)
        createNotificationChannel()
        running = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH_STORAGE) {
            executor.execute {
                refreshPublishedStorage()
            }
            return START_STICKY
        }

        if (intent?.action == ACTION_STOP) {
            identityStore.setDeviceServiceEnabled(false)
            stopNetworkMonitoring()
            stopServers()
            stopForegroundCompat()
            stopSelf()
            broadcastState()
            return START_NOT_STICKY
        }

        if (!identityStore.isDeviceServiceEnabled()) {
            stopNetworkMonitoring()
            stopServers()
            stopForegroundCompat()
            stopSelf()
            broadcastState()
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification(getString(R.string.device_service_starting)))
        startNetworkMonitoring()
        requestNetworkSync()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        stopNetworkMonitoring()
        stopServers()
        executor.shutdownNow()
        broadcastState()
        super.onDestroy()
    }

    /**
     * Подписывает работающий сервис на изменения Wi-Fi интерфейсов и адресов.
     */
    private fun startNetworkMonitoring() {
        if (networkCallback != null) {
            return
        }
        networkCallback = wifiNetworkProvider.registerNetworkChanges(::requestNetworkSync)
    }

    /**
     * Освобождает сетевой callback и отменяет запланированные проверки сети.
     */
    private fun stopNetworkMonitoring() {
        networkHandler.removeCallbacks(networkSyncRunnable)
        networkCallback?.let(wifiNetworkProvider::unregisterNetworkChanges)
        networkCallback = null
    }

    /**
     * Планирует немедленное согласование сокетов с текущей Wi-Fi сетью.
     */
    private fun requestNetworkSync() {
        networkHandler.removeCallbacks(networkSyncRunnable)
        networkHandler.post(networkSyncRunnable)
    }

    /**
     * Сопоставляет работающие серверы с актуальной Wi-Fi сетью и IPv4.
     * Без назначенного IP закрывает сокеты и продолжает ожидать сетевых событий.
     */
    private fun reconcileNetwork() {
        val wifiInfo = wifiNetworkProvider.findWifiNetworkInfo()
        if (wifiInfo == null) {
            if (activeNetwork != null || activeHost.isNotBlank()) {
                stopServers()
            }
            updateNotification(getString(R.string.device_service_starting))
            broadcastState()
            return
        }

        val host = wifiInfo.ipv4Address.hostAddress.orEmpty()
        if (activeNetwork == wifiInfo.network && activeHost == host) {
            return
        }
        restartServers(wifiInfo)
    }

    /**
     * Привязывает control и файловый endpoint к IPv4 выбранного интерфейса.
     * Ошибка запуска освобождает сокеты и запускает следующую попытку через интервал.
     */
    private fun restartServers(wifiInfo: WifiNetworkProvider.WifiNetworkInfo) {
        stopServers()
        try {
            val identity = identityStore.getIdentity()
            val roots = storageRootsRepository.listPublishedRoots()

            val runningWebDav = if (roots.isNotEmpty()) {
                runCatching {
                    webDavServer.start(
                        wifiInfo = wifiInfo,
                        roots = roots,
                    )
                }.onFailure { throwable ->
                    Log.w(LOG_TAG, "File backend could not be started", throwable)
                }.getOrNull()
            } else {
                null
            }

            val filePort = runningWebDav?.port ?: 0
            deviceServer.start(
                wifiInfo = wifiInfo,
                identity = identity,
                filePort = filePort,
            )
            activeNetwork = wifiInfo.network
            activeHost = wifiInfo.ipv4Address.hostAddress.orEmpty()
            activeFilePort = filePort

            val text = if (activeFilePort > 0) {
                getString(
                    R.string.device_service_active_with_files,
                    activeHost,
                    AndroidDeviceServer.CONTROL_PORT,
                )
            } else {
                getString(
                    R.string.device_service_active_without_files,
                    activeHost,
                    AndroidDeviceServer.CONTROL_PORT,
                )
            }
            updateNotification(text)
            broadcastState()
        } catch (throwable: Throwable) {
            Log.e(LOG_TAG, "Device service failed to start", throwable)
            stopServers()
            updateNotification(getString(R.string.device_service_error))
            broadcastState()
            if (running && identityStore.isDeviceServiceEnabled()) {
                networkHandler.postDelayed(networkSyncRunnable, NETWORK_RETRY_DELAY_MS)
            }
        }
    }

    /**
     * Применяет новый список опубликованных хранилищ без смены file-port,
     * когда файловый сервер уже работает.
     */
    private fun refreshPublishedStorage() {
        synchronized(serverLock) {
            if (!identityStore.isDeviceServiceEnabled() || activeHost.isBlank()) {
                return
            }

            try {
                val wifiInfo = wifiNetworkProvider.findWifiNetworkInfo()
                if (wifiInfo == null || wifiInfo.network != activeNetwork ||
                    wifiInfo.ipv4Address.hostAddress != activeHost) {
                    requestNetworkSync()
                    return
                }

                val roots = storageRootsRepository.listPublishedRoots()
                if (activeFilePort > 0 && roots.isNotEmpty()) {
                    webDavServer.updateRoots(roots)
                    return
                }

                val identity = identityStore.getIdentity()
                if (roots.isEmpty()) {
                    webDavServer.stop()
                    activeFilePort = 0
                } else {
                    activeFilePort = webDavServer.start(
                        wifiInfo = wifiInfo,
                        roots = roots,
                    ).port
                }

                deviceServer.stop()
                deviceServer.start(
                    wifiInfo = wifiInfo,
                    identity = identity,
                    filePort = activeFilePort,
                )
                broadcastState()
            } catch (throwable: Throwable) {
                Log.w(LOG_TAG, "Published storage refresh failed", throwable)
                stopServers()
                requestNetworkSync()
            }
        }
    }

    /**
     * Закрывает control и WebDAV сокеты и очищает сетевую identity работающего endpoint.
     */
    private fun stopServers() {
        synchronized(serverLock) {
            deviceServer.stop()
            webDavServer.stop()
            activeNetwork = null
            activeHost = ""
            activeFilePort = 0
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP_SERVICE,
            Intent(this, DropMeDeviceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val content = NeoNotificationViews.ServiceNotificationContent(
            title = getString(R.string.device_service_notification_title),
            text = text,
            accentColor = ContextCompat.getColor(this, R.color.status_connected),
            actions = listOf(
                NeoNotificationViews.ServiceNotificationAction(
                    text = getString(R.string.device_service_action_stop),
                    intent = stopIntent,
                ),
            ),
        )

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentIntent(openIntent)
            .setDeleteIntent(stopIntent)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        return NeoNotificationViews.applyServiceNotificationContent(
            builder = builder,
            context = this,
            content = content,
            defaultActionIconResId = R.mipmap.ic_launcher_monochrome,
        ).build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.device_service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.device_service_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun broadcastState() {
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_RUNNING, running && activeHost.isNotBlank())
                .putExtra(EXTRA_HOST, activeHost)
                .putExtra(EXTRA_CONTROL_PORT, AndroidDeviceServer.CONTROL_PORT)
                .putExtra(EXTRA_FILE_PORT, activeFilePort),
        )
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        @Volatile
        private var running = false

        const val ACTION_STATE_CHANGED = "com.rokkystudio.dropme.ACTION_DEVICE_SERVICE_STATE_CHANGED"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_HOST = "host"
        const val EXTRA_CONTROL_PORT = "control_port"
        const val EXTRA_FILE_PORT = "file_port"

        private const val ACTION_START = "com.rokkystudio.dropme.action.START_DEVICE_SERVICE"
        private const val ACTION_STOP = "com.rokkystudio.dropme.action.STOP_DEVICE_SERVICE"
        private const val ACTION_REFRESH_STORAGE = "com.rokkystudio.dropme.action.REFRESH_STORAGE"
        private const val NOTIFICATION_CHANNEL_ID = "dropme_device_service"
        private const val NOTIFICATION_ID = 4301
        private const val REQUEST_OPEN_APP = 4302
        private const val REQUEST_STOP_SERVICE = 4303
        private const val LOG_TAG = "DROPME"
        private const val NETWORK_RETRY_DELAY_MS = 2_000L

        fun isRunning(): Boolean = running

        /**
         * Запускает foreground-service только когда устройство отмечено как доступное другим DROPME-устройствам.
         */
        fun start(context: Context) {
            if (!DeviceIdentityStore(context).isDeviceServiceEnabled()) {
                return
            }
            ContextCompat.startForegroundService(
                context,
                Intent(context, DropMeDeviceService::class.java).setAction(ACTION_START),
            )
        }

        fun refreshStorageRoots(context: Context) {
            if (!running || !DeviceIdentityStore(context).isDeviceServiceEnabled()) {
                return
            }
            context.startService(
                Intent(context, DropMeDeviceService::class.java).setAction(ACTION_REFRESH_STORAGE),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, DropMeDeviceService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
