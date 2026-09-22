package com.rokkystudio.dropme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.rokkystudio.dropme.network.WindowsServer
import com.rokkystudio.dropme.network.WindowsServerScanner
import com.rokkystudio.dropme.network.WifiNetworkProvider
import com.rokkystudio.dropme.service.AndroidConnectionService
import com.rokkystudio.dropme.service.ConnectionServicePhase
import com.rokkystudio.dropme.service.ConnectionServiceSnapshot
import com.rokkystudio.dropme.service.ConnectionServiceStateStore
import com.rokkystudio.dropme.storage.StorageAccessState
import com.rokkystudio.dropme.storage.StorageRootEntry
import com.rokkystudio.dropme.storage.StorageRootsRepository
import com.rokkystudio.dropme.ui.ShareServerPickerScreen
import com.rokkystudio.dropme.ui.StorageRootsScreen
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Показывает доступные хранилища, непрерывно ищет Windows-серверы, пока экран открыт,
 * и управляет активным Android -> Windows подключением.
 */
class MainActivity : AppCompatActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scanHandler = Handler(Looper.getMainLooper())

    private lateinit var uiSettings: UiSettings
    private lateinit var themeToggleButton: ImageButton
    private lateinit var languageFlag: ImageButton
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var storageRootsContainer: LinearLayout
    private lateinit var serverListContainer: LinearLayout
    private lateinit var noServersText: TextView
    private lateinit var disconnectButton: android.widget.Button

    private lateinit var wifiNetworkProvider: WifiNetworkProvider
    private lateinit var wifiDropScanner: WindowsServerScanner
    private lateinit var storageRootsRepository: StorageRootsRepository
    private lateinit var connectionStateStore: ConnectionServiceStateStore
    private lateinit var serverPickerScreen: ShareServerPickerScreen
    private lateinit var storageRootsScreen: StorageRootsScreen

    private var lastWifiInfo: WifiNetworkProvider.WifiNetworkInfo? = null
    private var lastStorageRoots: List<StorageRootEntry> = emptyList()
    private var discoveredServers: List<WindowsServer> = emptyList()
    private var connectionReceiverRegistered = false

    @Volatile
    private var activityStarted = false

    @Volatile
    private var scanInProgress = false

    private val nextScanRunnable = Runnable {
        startNetworkScan()
    }

    private val connectionStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderConnectionState(connectionStateStore.read())
        }
    }

    private val manageAllFilesAccessLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshStorageRootsState()
            if (!storageRootsRepository.hasAllFilesAccess()) {
                Toast.makeText(this, R.string.main_storage_access_denied, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        uiSettings = UiSettings(this)
        bindViews()
        bindDependencies()
        bindActions()
        renderThemeToggle()
        renderLanguageFlag()
        refreshStorageRootsState()
        renderServerList()
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        registerConnectionStateReceiver()
        refreshStorageRootsState()
        renderConnectionState(connectionStateStore.read())
        startScanLoop()
    }

    override fun onResume() {
        super.onResume()
        refreshStorageRootsState()
    }

    override fun onStop() {
        activityStarted = false
        scanHandler.removeCallbacks(nextScanRunnable)
        if (connectionReceiverRegistered) {
            unregisterReceiver(connectionStateReceiver)
            connectionReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        scanHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun bindViews() {
        themeToggleButton = findViewById(R.id.themeToggleButton)
        languageFlag = findViewById(R.id.languageFlag)
        statusText = findViewById(R.id.mainStatusText)
        detailText = findViewById(R.id.mainDetailText)
        progressBar = findViewById(R.id.mainProgressBar)
        storageRootsContainer = findViewById(R.id.mainStorageRootsContainer)
        serverListContainer = findViewById(R.id.mainServerListContainer)
        noServersText = findViewById(R.id.mainNoServersText)
        disconnectButton = findViewById(R.id.mainDisconnectButton)
    }

    private fun bindDependencies() {
        wifiNetworkProvider = WifiNetworkProvider(applicationContext)
        wifiDropScanner = WindowsServerScanner()
        storageRootsRepository = StorageRootsRepository(applicationContext)
        connectionStateStore = ConnectionServiceStateStore(applicationContext)
        serverPickerScreen = ShareServerPickerScreen(
            context = this,
            container = serverListContainer,
            onServerSelected = ::connectToServer,
        )
        storageRootsScreen = StorageRootsScreen(
            context = this,
            container = storageRootsContainer,
            onRootSelected = ::handleStorageRootSelected,
        )
    }

    private fun bindActions() {
        themeToggleButton.setOnClickListener {
            toggleTheme()
        }
        disconnectButton.setOnClickListener {
            AndroidConnectionService.stop(this)
            renderConnectionState(
                ConnectionServiceSnapshot(
                    phase = ConnectionServicePhase.IDLE,
                    detailMessage = getString(R.string.main_status_disconnected_detail),
                ),
            )
        }
    }

    private fun toggleTheme() {
        val theme = when (uiSettings.getTheme()) {
            AppTheme.LIGHT -> AppTheme.DARK
            AppTheme.DARK -> AppTheme.LIGHT
        }
        uiSettings.setTheme(theme)
        AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                AppTheme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                AppTheme.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            },
        )
    }

    private fun renderThemeToggle() {
        when (uiSettings.getTheme()) {
            AppTheme.LIGHT -> {
                themeToggleButton.setImageResource(R.drawable.theme_sun)
                themeToggleButton.contentDescription = getString(R.string.theme_light)
            }

            AppTheme.DARK -> {
                themeToggleButton.setImageResource(R.drawable.theme_moon)
                themeToggleButton.contentDescription = getString(R.string.theme_dark)
            }
        }
    }

    private fun renderLanguageFlag() {
        val language = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            resources.configuration.locales[0]?.language
        } else {
            @Suppress("DEPRECATION")
            resources.configuration.locale?.language
        }
        languageFlag.setImageResource(
            if (language.equals("ru", ignoreCase = true)) {
                R.drawable.flag_ru
            } else {
                R.drawable.flag_us
            },
        )
    }

    private fun startScanLoop() {
        scanHandler.removeCallbacks(nextScanRunnable)
        renderScanningIndicator()
        startNetworkScan()
    }

    private fun startNetworkScan() {
        if (!activityStarted || scanInProgress) {
            return
        }

        scanInProgress = true
        val startedAt = SystemClock.elapsedRealtime()
        renderScanningIndicator()

        executor.execute {
            try {
                val wifiInfo = wifiNetworkProvider.getWifiNetworkInfo()
                lastWifiInfo = wifiInfo
                wifiDropScanner.scan(
                    wifiInfo = wifiInfo,
                    onServerFound = { servers ->
                        runOnUiThread {
                            if (activityStarted) {
                                mergeDiscoveredServers(servers)
                            }
                        }
                    },
                    shouldContinue = {
                        activityStarted && !Thread.currentThread().isInterrupted
                    },
                ).also { servers ->
                    runOnUiThread {
                        if (activityStarted) {
                            mergeDiscoveredServers(servers)
                            renderScanningIndicator()
                        }
                    }
                }
            } catch (throwable: Throwable) {
                val error = throwable.toAppError(
                    AppError.UnknownError(getString(R.string.main_status_error_title)),
                )
                runOnUiThread {
                    if (activityStarted) {
                        renderScanError(error)
                    }
                }
            } finally {
                scanInProgress = false
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val delay = (SCAN_INTERVAL_MS - elapsed).coerceAtLeast(0L)
                runOnUiThread {
                    if (activityStarted) {
                        scanHandler.removeCallbacks(nextScanRunnable)
                        scanHandler.postDelayed(nextScanRunnable, delay)
                    }
                }
            }
        }
    }

    private fun mergeDiscoveredServers(servers: List<WindowsServer>) {
        if (servers.isEmpty()) {
            renderServerList()
            return
        }

        val merged = LinkedHashMap<String, WindowsServer>()
        discoveredServers.forEach { server ->
            merged[serverKey(server)] = server
        }
        servers.forEach { server ->
            merged[serverKey(server)] = server
        }
        discoveredServers = merged.values.sortedWith(
            compareBy({ it.deviceName.lowercase() }, { it.host }, { it.tcpPort }),
        )
        renderServerList()
    }

    private fun serverKey(server: WindowsServer): String =
        server.host + ":" + server.tcpPort

    private fun renderServerList() {
        serverPickerScreen.show(discoveredServers)
        val hasServers = discoveredServers.isNotEmpty()
        serverListContainer.visibility = if (hasServers) View.VISIBLE else View.GONE
        noServersText.visibility = if (hasServers) View.GONE else View.VISIBLE
    }

    private fun renderScanningIndicator() {
        progressBar.visibility = View.VISIBLE
    }

    private fun renderScanError(error: AppError) {
        progressBar.visibility = View.VISIBLE
        if (connectionStateStore.read().phase == ConnectionServicePhase.IDLE) {
            detailText.text = error.toUserMessage(this)
        }
    }

    private fun connectToServer(server: WindowsServer) {
        if (lastWifiInfo == null) {
            Toast.makeText(
                this,
                AppError.NoWifiNetwork.toUserMessage(this),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        val publishedRoots = storageRootsRepository.listPublishedRoots()
        if (publishedRoots.isEmpty()) {
            refreshStorageRootsState()
            Toast.makeText(
                this,
                R.string.main_error_no_storage_roots_ready,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        AndroidConnectionService.start(this, server)
        renderConnectionState(
            ConnectionServiceSnapshot(
                phase = ConnectionServicePhase.CONNECTING,
                serverName = server.deviceName,
                serverHost = server.host,
                serverPort = server.tcpPort,
                detailMessage = getString(
                    R.string.main_status_connecting_detail,
                    server.host,
                    server.tcpPort,
                ),
            ),
        )
    }

    private fun renderConnectionState(snapshot: ConnectionServiceSnapshot) {
        when (snapshot.phase) {
            ConnectionServicePhase.IDLE -> {
                statusText.text = getString(R.string.main_status_select_server)
                detailText.text = snapshot.detailMessage.orEmpty()
                disconnectButton.visibility = View.GONE
            }

            ConnectionServicePhase.CONNECTING -> {
                statusText.text = getString(
                    R.string.main_status_connecting,
                    snapshot.serverName.orEmpty(),
                )
                detailText.text = snapshot.detailMessage.orEmpty()
                disconnectButton.visibility = View.VISIBLE
            }

            ConnectionServicePhase.CONNECTED -> {
                statusText.text = getString(
                    R.string.main_status_connected,
                    snapshot.serverName.orEmpty(),
                )
                detailText.text = snapshot.detailMessage.orEmpty()
                disconnectButton.visibility = View.VISIBLE
            }

            ConnectionServicePhase.ERROR -> {
                statusText.text = getString(R.string.main_status_error_title)
                detailText.text = snapshot.errorMessage
                    ?: getString(R.string.main_status_error_title)
                disconnectButton.visibility = View.GONE
            }
        }

        renderScanningIndicator()
        renderServerList()
    }

    private fun refreshStorageRootsState() {
        lastStorageRoots = storageRootsRepository.listRoots()
        storageRootsScreen.show(lastStorageRoots)
    }

    private fun handleStorageRootSelected(root: StorageRootEntry) {
        when (root.accessState) {
            StorageAccessState.READY -> Unit
            StorageAccessState.NEEDS_ALL_FILES_ACCESS,
            StorageAccessState.NEEDS_TREE_GRANT -> {
                manageAllFilesAccessLauncher.launch(
                    storageRootsRepository.buildManageAllFilesAccessIntent(),
                )
            }

            StorageAccessState.UNAVAILABLE -> {
                Toast.makeText(
                    this,
                    R.string.storage_state_unavailable,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun registerConnectionStateReceiver() {
        if (connectionReceiverRegistered) {
            return
        }
        val filter = IntentFilter(AndroidConnectionService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectionStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(connectionStateReceiver, filter)
        }
        connectionReceiverRegistered = true
    }

    private companion object {
        const val SCAN_INTERVAL_MS = 10_000L
    }
}
