package com.rokkystudio.dropme

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import badfox.logic.neoui.NeoButtonView
import badfox.logic.neoui.NeoIconActionView
import badfox.logic.icons.MaterialSymbols
import badfox.logic.neoui.NeoScreenItem
import badfox.logic.neoui.NeoScreenNavigatorView
import badfox.logic.neoui.NeoTopBarView
import badfox.logic.neoui.NeoThemeMode
import badfox.logic.neoui.NeoUiSettings
import com.rokkystudio.dropme.device.DeviceIdentityStore
import com.rokkystudio.dropme.device.DeviceTrustStore
import com.rokkystudio.dropme.network.RemoteFileClient
import com.rokkystudio.dropme.network.WindowsServer
import com.rokkystudio.dropme.network.WindowsServerScanner
import com.rokkystudio.dropme.network.WifiNetworkProvider
import com.rokkystudio.dropme.service.DropMeDeviceService
import com.rokkystudio.dropme.storage.StorageAccessState
import com.rokkystudio.dropme.storage.StorageRootEntry
import com.rokkystudio.dropme.storage.StorageRootsRepository
import com.rokkystudio.dropme.ui.ConnectionDevicesScreen
import com.rokkystudio.dropme.ui.StorageRootsScreen
import java.io.File
import java.text.DateFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Управляет identity и сетевой доступностью текущего устройства и показывает другие DROPME-устройства.
 */
class MainActivity : AppCompatActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scanHandler = Handler(Looper.getMainLooper())

    private lateinit var neoUiSettings: NeoUiSettings
    private lateinit var identityStore: DeviceIdentityStore
    private lateinit var wifiNetworkProvider: WifiNetworkProvider
    private lateinit var deviceScanner: WindowsServerScanner
    private lateinit var remoteFileClient: RemoteFileClient
    private lateinit var storageRootsRepository: StorageRootsRepository
    private lateinit var deviceTrustStore: DeviceTrustStore
    private lateinit var connectionDevicesScreen: ConnectionDevicesScreen
    private lateinit var storageRootsScreen: StorageRootsScreen

    private lateinit var neoTopBar: NeoTopBarView
    private lateinit var navigator: NeoScreenNavigatorView
    private lateinit var deviceScroll: View
    private lateinit var connectionScroll: View
    private lateinit var deviceNameEdit: EditText
    private lateinit var deviceIpText: TextView
    private lateinit var deviceIdText: TextView
    private lateinit var deviceServiceCheckBox: CheckBox
    private lateinit var saveDeviceNameButton: NeoButtonView
    private lateinit var addStorageRootButton: NeoIconActionView
    private lateinit var storageRootsContainer: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var serverListContainer: LinearLayout
    private lateinit var noServersText: TextView
    private lateinit var connectionBrowserPanel: View
    private lateinit var connectionBrowserBack: NeoIconActionView
    private lateinit var connectionBrowserTitle: TextView
    private lateinit var connectionBrowserPath: TextView
    private lateinit var connectionBrowserProgress: ProgressBar
    private lateinit var connectionBrowserEmpty: TextView
    private lateinit var connectionBrowserFiles: LinearLayout
    private lateinit var allowedDevicesContainer: LinearLayout
    private lateinit var blockedDevicesContainer: LinearLayout

    private var discoveredDevices: List<WindowsServer> = emptyList()
    private var lastStorageRoots: List<StorageRootEntry> = emptyList()
    private var localIp: String = ""
    private var selectedScreen = MainScreen.DEVICE
    private var connectionMode = ConnectionMode.DEVICE_LIST
    private var currentLocalStorageRoot: StorageRootEntry? = null
    private var currentLocalDirectory: File? = null
    private var currentRemoteDevice: WindowsServer? = null
    private var currentRemotePath: String = "/"
    private var remoteSessionAuthorized = false
    private var connectionDialog: AlertDialog? = null
    private var localBrowsePermissionPending = false

    @Volatile
    private var activityStarted = false

    @Volatile
    private var scanInProgress = false

    private val nextScanRunnable = Runnable {
        startNetworkScan()
    }

    private val manageAllFilesAccessLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshStorageRootsState()
            DropMeDeviceService.refreshStorageRoots(this)
            if (localBrowsePermissionPending) {
                localBrowsePermissionPending = false
                if (storageRootsRepository.hasAllFilesAccess()) {
                    showLocalStorageRoots()
                } else {
                    showLocalFilePermissionDialog()
                }
            } else if (!storageRootsRepository.hasAllFilesAccess()) {
                Toast.makeText(this, R.string.main_storage_access_denied, Toast.LENGTH_SHORT).show()
            }
        }

    private val readStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (localBrowsePermissionPending) {
                localBrowsePermissionPending = false
                if (granted) {
                    showLocalStorageRoots()
                } else {
                    showLocalFilePermissionDialog()
                }
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            Unit
        }

    private val addStorageRootLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) {
                return@registerForActivityResult
            }
            runCatching {
                storageRootsRepository.addCustomRoot(uri)
            }.onFailure {
                Toast.makeText(this, R.string.storage_add_failed, Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            refreshStorageRootsState()
            DropMeDeviceService.refreshStorageRoots(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        bindDependencies()
        requestNotificationPermissionIfNeeded()
        bindActions()
        renderTopBar()
        renderLocalIdentity()
        refreshStorageRootsState()
        renderTrustLists()
        renderDeviceList()
        renderScreen()
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        renderLocalIdentity()
        refreshStorageRootsState()
        renderTrustLists()
        if (selectedScreen == MainScreen.CONNECTION) {
            startScanLoop()
        }
    }

    override fun onResume() {
        super.onResume()
        renderLocalIdentity()
        refreshStorageRootsState()
        renderTrustLists()
    }

    override fun onStop() {
        activityStarted = false
        scanHandler.removeCallbacks(nextScanRunnable)
        super.onStop()
    }

    override fun onDestroy() {
        connectionDialog?.dismiss()
        connectionDialog = null
        scanHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun bindViews() {
        neoTopBar = findViewById(R.id.neoTopBar)
        navigator = findViewById(R.id.mainNavigator)
        deviceScroll = findViewById(R.id.mainDeviceScroll)
        connectionScroll = findViewById(R.id.mainConnectionScroll)
        deviceNameEdit = findViewById(R.id.mainDeviceNameEdit)
        deviceIpText = findViewById(R.id.mainDeviceIpText)
        deviceIdText = findViewById(R.id.mainDeviceIdText)
        deviceServiceCheckBox = findViewById(R.id.mainDeviceServiceCheckBox)
        saveDeviceNameButton = findViewById(R.id.mainSaveDeviceNameButton)
        addStorageRootButton = findViewById(R.id.mainAddStorageRootButton)
        storageRootsContainer = findViewById(R.id.mainStorageRootsContainer)
        progressBar = findViewById(R.id.mainProgressBar)
        serverListContainer = findViewById(R.id.mainServerListContainer)
        noServersText = findViewById(R.id.mainNoServersText)
        connectionBrowserPanel = findViewById(R.id.mainConnectionBrowserPanel)
        connectionBrowserBack = findViewById(R.id.mainConnectionBrowserBack)
        connectionBrowserTitle = findViewById(R.id.mainConnectionBrowserTitle)
        connectionBrowserPath = findViewById(R.id.mainConnectionBrowserPath)
        connectionBrowserProgress = findViewById(R.id.mainConnectionBrowserProgress)
        connectionBrowserEmpty = findViewById(R.id.mainConnectionBrowserEmpty)
        connectionBrowserFiles = findViewById(R.id.mainConnectionBrowserFiles)
        allowedDevicesContainer = findViewById(R.id.mainAllowedDevicesContainer)
        blockedDevicesContainer = findViewById(R.id.mainBlockedDevicesContainer)
    }

    private fun bindDependencies() {
        neoUiSettings = NeoUiSettings(
            context = this,
            preferencesName = NEO_UI_PREFERENCES_NAME,
            defaultTheme = NeoThemeMode.DARK,
            defaultLanguageTag = null,
        )
        identityStore = DeviceIdentityStore(applicationContext)
        wifiNetworkProvider = WifiNetworkProvider(applicationContext)
        deviceScanner = WindowsServerScanner()
        remoteFileClient = RemoteFileClient(applicationContext)
        storageRootsRepository = StorageRootsRepository(applicationContext)
        deviceTrustStore = DeviceTrustStore(applicationContext)
        connectionDevicesScreen = ConnectionDevicesScreen(
            context = this,
            container = serverListContainer,
            onLocalDeviceSelected = ::openLocalDevice,
            onRemoteDeviceSelected = ::openDevice,
        )
        storageRootsScreen = StorageRootsScreen(
            context = this,
            container = storageRootsContainer,
            onEnabledChanged = ::handleStorageRootEnabledChanged,
            onRootSelected = ::handleStorageRootSelected,
            onRemoveRoot = ::handleStorageRootRemove,
        )
    }

    private fun bindActions() {
        navigator.setItems(
            items = listOf(
                NeoScreenItem(
                    value = MainScreen.DEVICE,
                    title = getString(R.string.navigation_device),
                    iconResId = R.drawable.ic_nav_device,
                ),
                NeoScreenItem(
                    value = MainScreen.CONNECTION,
                    title = getString(R.string.navigation_connection),
                    iconResId = R.drawable.ic_nav_connection,
                ),
            ),
            selectedValue = selectedScreen,
            placement = NeoScreenNavigatorView.Placement.BOTTOM,
        ) { screen ->
            selectedScreen = screen
            if (screen != MainScreen.CONNECTION) {
                showConnectionDeviceList()
            }
            renderScreen()
        }

        connectionBrowserBack.setOnClickListener {
            navigateConnectionBack()
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (selectedScreen == MainScreen.CONNECTION &&
                        connectionMode != ConnectionMode.DEVICE_LIST &&
                        navigateConnectionBack()
                    ) {
                        return
                    }
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            },
        )

        deviceNameEdit.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    text: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    text: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {
                    updateSaveDeviceNameVisibility()
                }

                override fun afterTextChanged(text: Editable?) = Unit
            },
        )

        saveDeviceNameButton.setOnClickListener {
            val name = deviceNameEdit.text?.toString().orEmpty().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, R.string.main_device_name_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            identityStore.setDeviceName(name)
            restartDeviceServiceIfEnabled()
            renderLocalIdentity()
            updateSaveDeviceNameVisibility()
            Toast.makeText(this, R.string.main_device_name_saved, Toast.LENGTH_SHORT).show()
        }

        addStorageRootButton.setOnClickListener {
            addStorageRootLauncher.launch(null)
        }

        deviceServiceCheckBox.setOnClickListener {
            if (deviceServiceCheckBox.isChecked) {
                identityStore.setDeviceServiceEnabled(true)
                DropMeDeviceService.start(this)
            } else {
                identityStore.setDeviceServiceEnabled(false)
                DropMeDeviceService.stop(this)
            }
            renderLocalIdentity()
        }
    }

    private fun renderTopBar() {
        neoTopBar.setAppInfo(
            title = getString(R.string.app_name),
            subtitle = getString(R.string.main_header_subtitle),
            version = BuildConfig.VERSION_NAME,
            iconResId = R.mipmap.ic_launcher,
        )
        neoUiSettings.bindTopBar(neoTopBar)
    }

    private fun renderLocalIdentity() {
        val identity = identityStore.getIdentity()
        if (!deviceNameEdit.hasFocus()) {
            deviceNameEdit.setText(identity.name)
        }
        deviceIdText.text = getString(R.string.main_device_id, identity.id)
        deviceServiceCheckBox.isChecked = identityStore.isDeviceServiceEnabled()
        updateSaveDeviceNameVisibility()

        executor.execute {
            val ip = runCatching {
                wifiNetworkProvider.getWifiNetworkInfo().ipv4Address.hostAddress.orEmpty()
            }.getOrDefault("")
            runOnUiThread {
                localIp = ip
                deviceIpText.text = getString(
                    R.string.main_device_ip,
                    ip.ifBlank { getString(R.string.main_device_ip_unavailable) },
                )
                renderDeviceList()
            }
        }
    }

    /**
     * Показывает сохранение имени только если введённое значение отличается от сохранённого имени.
     */
    private fun updateSaveDeviceNameVisibility() {
        val editedName = deviceNameEdit.text?.toString().orEmpty().trim()
        val savedName = identityStore.getDeviceName()
        saveDeviceNameButton.visibility =
            if (editedName != savedName) View.VISIBLE else View.GONE
    }
    private fun startScanLoop() {
        scanHandler.removeCallbacks(nextScanRunnable)
        startNetworkScan()
    }

    private fun startNetworkScan() {
        if (!activityStarted || selectedScreen != MainScreen.CONNECTION || connectionMode != ConnectionMode.DEVICE_LIST || scanInProgress) {
            return
        }

        scanInProgress = true
        val startedAt = SystemClock.elapsedRealtime()
        progressBar.visibility = View.VISIBLE

        executor.execute {
            val scanCanceled = AtomicBoolean(false)
            try {
                val wifiInfo = wifiNetworkProvider.getWifiNetworkInfo()
                runOnUiThread {
                    localIp = wifiInfo.ipv4Address.hostAddress.orEmpty()
                    deviceIpText.text = getString(
                        R.string.main_device_ip,
                        localIp,
                    )
                    renderDeviceList()
                }

                val devices = deviceScanner.scan(
                    wifiInfo = wifiInfo,
                    onServerFound = { foundDevices ->
                        runOnUiThread {
                            if (activityStarted) {
                                mergeDiscoveredDevices(foundDevices)
                            }
                        }
                    },
                    shouldContinue = {
                        val shouldContinue =
                            activityStarted &&
                                selectedScreen == MainScreen.CONNECTION &&
                                connectionMode == ConnectionMode.DEVICE_LIST &&
                                !Thread.currentThread().isInterrupted
                        if (!shouldContinue) {
                            scanCanceled.set(true)
                        }
                        shouldContinue
                    },
                )

                if (!scanCanceled.get()) {
                    runOnUiThread {
                        if (activityStarted) {
                            replaceDiscoveredDevices(devices)
                        }
                    }
                }
            } catch (_: Throwable) {
                // Сохраняем последний подтверждённый список. Ошибка или отмена сканирования
                // не означают, что ранее найденные устройства уже исчезли из сети.
            } finally {
                scanInProgress = false
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val delay = (SCAN_INTERVAL_MS - elapsed).coerceAtLeast(0L)
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    if (activityStarted && selectedScreen == MainScreen.CONNECTION && connectionMode == ConnectionMode.DEVICE_LIST) {
                        scanHandler.removeCallbacks(nextScanRunnable)
                        scanHandler.postDelayed(nextScanRunnable, delay)
                    }
                }
            }
        }
    }

    private fun mergeDiscoveredDevices(devices: List<WindowsServer>) {
        val merged = LinkedHashMap<String, WindowsServer>()
        discoveredDevices.forEach { device ->
            merged[device.deviceId] = device
        }
        devices.forEach { device ->
            merged[device.deviceId] = device
        }
        discoveredDevices = sortDevices(merged.values)
        renderDeviceList()
    }

    private fun replaceDiscoveredDevices(devices: List<WindowsServer>) {
        discoveredDevices = sortDevices(devices)
        renderDeviceList()
    }

    private fun sortDevices(devices: Collection<WindowsServer>): List<WindowsServer> {
        return devices.sortedWith(compareBy({ it.deviceName.lowercase() }, { it.deviceId }))
    }

    private fun renderDeviceList() {
        connectionDevicesScreen.show(
            localIdentity = identityStore.getIdentity(),
            localIp = localIp,
            remoteDevices = discoveredDevices,
        )
        serverListContainer.visibility = View.VISIBLE
        noServersText.visibility = if (discoveredDevices.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * Переключает основное содержимое между настройками устройства и подключениями.
     */
    private fun renderScreen() {
        val connectionSelected = selectedScreen == MainScreen.CONNECTION
        deviceScroll.visibility = if (connectionSelected) View.GONE else View.VISIBLE
        connectionScroll.visibility = if (connectionSelected) View.VISIBLE else View.GONE
        navigator.select(selectedScreen)
        if (connectionSelected && connectionMode == ConnectionMode.DEVICE_LIST && activityStarted) {
            startScanLoop()
        } else {
            scanHandler.removeCallbacks(nextScanRunnable)
            progressBar.visibility = View.GONE
        }
    }

    /**
     * Открывает файлы текущего Android-устройства внутри вкладки «Подключение».
     */
    private fun openLocalDevice() {
        if (!hasLocalFileAccess()) {
            showLocalFilePermissionDialog()
            return
        }
        showLocalStorageRoots()
    }

    /**
     * Открывает удалённое устройство внутри текущей вкладки, не создавая новый Activity.
     */
    private fun openDevice(device: WindowsServer) {
        if (!device.fileAccessAvailable || device.filePort == null) {
            Toast.makeText(this, R.string.main_remote_files_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        currentRemoteDevice = device
        currentRemotePath = "/"
        remoteSessionAuthorized = false
        connectionMode = ConnectionMode.REMOTE_FILES
        showConnectionBrowser(
            title = device.deviceName,
            path = currentRemotePath,
        )
        loadRemotePath(currentRemotePath)
    }

    private fun showConnectionDeviceList() {
        connectionMode = ConnectionMode.DEVICE_LIST
        currentLocalStorageRoot = null
        currentLocalDirectory = null
        currentRemoteDevice = null
        currentRemotePath = "/"
        remoteSessionAuthorized = false
        connectionDialog?.dismiss()
        connectionDialog = null
        findViewById<View>(R.id.mainNetworkPanel).visibility = View.VISIBLE
        connectionBrowserPanel.visibility = View.GONE
        connectionBrowserFiles.removeAllViews()
        connectionBrowserProgress.visibility = View.GONE
        connectionBrowserEmpty.visibility = View.GONE
        if (selectedScreen == MainScreen.CONNECTION && activityStarted) {
            startScanLoop()
        }
    }

    private fun showConnectionBrowser(title: String, path: String) {
        scanHandler.removeCallbacks(nextScanRunnable)
        findViewById<View>(R.id.mainNetworkPanel).visibility = View.GONE
        connectionBrowserPanel.visibility = View.VISIBLE
        connectionBrowserTitle.text = title
        connectionBrowserPath.text = path
        connectionBrowserFiles.removeAllViews()
        connectionBrowserEmpty.visibility = View.GONE
    }

    private fun hasLocalFileAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showLocalFilePermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.local_files_permission_title)
            .setMessage(R.string.local_files_permission_message)
            .setPositiveButton(R.string.local_files_permission_grant) { _, _ ->
                localBrowsePermissionPending = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    manageAllFilesAccessLauncher.launch(
                        storageRootsRepository.buildManageAllFilesAccessIntent(),
                    )
                } else {
                    readStoragePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            .setNegativeButton(R.string.remote_connection_close, null)
            .show()
    }

    /**
     * Показывает все локальные физические хранилища прямо внутри вкладки «Подключение».
     */
    private fun showLocalStorageRoots() {
        connectionMode = ConnectionMode.LOCAL_ROOTS
        currentLocalStorageRoot = null
        currentLocalDirectory = null
        showConnectionBrowser(
            title = identityStore.getDeviceName(),
            path = getString(R.string.local_files_storages),
        )
        connectionBrowserProgress.visibility = View.GONE

        val roots = storageRootsRepository.listRoots()
            .mapNotNull { root ->
                val path = root.directPath ?: return@mapNotNull null
                val directory = File(path)
                if (!directory.isDirectory) return@mapNotNull null
                root to directory
            }

        connectionBrowserEmpty.visibility = if (roots.isEmpty()) View.VISIBLE else View.GONE
        roots.forEach { (root, directory) ->
            connectionBrowserFiles.addView(
                createBrowserRow(
                    title = root.displayName,
                    subtitle = directory.absolutePath,
                    clickable = true,
                ) {
                    currentLocalStorageRoot = root
                    openLocalDirectory(directory)
                },
            )
        }
    }

    private fun openLocalDirectory(directory: File) {
        val root = currentLocalStorageRoot
            ?: throw IllegalStateException("Local storage root is not selected")
        val rootDirectory = root.directPath?.let(::File)
            ?: throw IllegalStateException("Local storage root has no direct path")

        connectionMode = ConnectionMode.LOCAL_FILES
        currentLocalDirectory = directory
        val relativePath = directory.relativeToOrNull(rootDirectory)?.path.orEmpty()
        showConnectionBrowser(
            title = root.displayName,
            path = if (relativePath.isBlank()) "/" else "/${relativePath}",
        )
        connectionBrowserProgress.visibility = View.GONE

        val entries = directory.listFiles()
            .orEmpty()
            .filter { it.exists() }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))

        connectionBrowserEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        entries.forEach { entry ->
            connectionBrowserFiles.addView(
                createBrowserRow(
                    title = entry.name,
                    subtitle = if (entry.isDirectory) {
                        getString(R.string.remote_files_folder)
                    } else {
                        Formatter.formatShortFileSize(this, entry.length())
                    },
                    clickable = entry.isDirectory,
                ) {
                    if (entry.isDirectory) {
                        openLocalDirectory(entry)
                    }
                },
            )
        }
    }

    private fun loadRemotePath(path: String) {
        val device = currentRemoteDevice ?: return
        currentRemotePath = normalizeRemotePath(path)
        connectionMode = ConnectionMode.REMOTE_FILES
        showConnectionBrowser(device.deviceName, currentRemotePath)
        connectionBrowserProgress.visibility = View.VISIBLE

        val requiresAuthorization = !remoteSessionAuthorized
        if (requiresAuthorization) {
            showRemoteWaitingDialog(device)
        }

        executor.execute {
            runCatching {
                val wifiInfo = wifiNetworkProvider.getWifiNetworkInfo()
                if (!remoteSessionAuthorized) {
                    remoteFileClient.authorize(wifiInfo, device)
                    remoteSessionAuthorized = true
                }
                remoteFileClient.list(wifiInfo, device, currentRemotePath)
            }.onSuccess { entries ->
                runOnUiThread {
                    connectionDialog?.dismiss()
                    connectionDialog = null
                    connectionBrowserProgress.visibility = View.GONE
                    renderRemoteEntries(entries)
                }
            }.onFailure { throwable ->
                runOnUiThread {
                    connectionDialog?.dismiss()
                    connectionDialog = null
                    connectionBrowserProgress.visibility = View.GONE
                    showRemoteConnectionError(throwable)
                }
            }
        }
    }

    private fun renderRemoteEntries(entries: List<RemoteFileClient.Entry>) {
        connectionBrowserFiles.removeAllViews()
        connectionBrowserEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        entries.forEach { entry ->
            connectionBrowserFiles.addView(
                createBrowserRow(
                    title = entry.name,
                    subtitle = if (entry.isDirectory) {
                        getString(R.string.remote_files_folder)
                    } else {
                        Formatter.formatShortFileSize(this, entry.size)
                    },
                    clickable = true,
                ) {
                    val entryPath = joinRemotePath(currentRemotePath, entry.name)
                    if (entry.isDirectory) {
                        loadRemotePath(entryPath)
                    } else {
                        downloadRemoteFile(entryPath, entry.name)
                    }
                },
            )
        }
    }

    private fun downloadRemoteFile(remotePath: String, fileName: String) {
        val device = currentRemoteDevice ?: return
        connectionBrowserProgress.visibility = View.VISIBLE
        executor.execute {
            runCatching {
                val wifiInfo = wifiNetworkProvider.getWifiNetworkInfo()
                remoteFileClient.download(wifiInfo, device, remotePath, fileName)
            }.onSuccess { file ->
                runOnUiThread {
                    connectionBrowserProgress.visibility = View.GONE
                    Toast.makeText(
                        this,
                        getString(R.string.remote_files_downloaded, file.absolutePath),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }.onFailure { throwable ->
                runOnUiThread {
                    connectionBrowserProgress.visibility = View.GONE
                    Toast.makeText(
                        this,
                        throwable.findAppError()?.toUserMessage(this)
                            ?: throwable.message
                            ?: getString(R.string.remote_files_download_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun createBrowserRow(
        title: String,
        subtitle: String,
        clickable: Boolean,
        onClick: () -> Unit,
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = getDrawable(R.drawable.bg_button)
            isClickable = clickable
            isFocusable = clickable

            addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11f
            })
            if (clickable) {
                setOnClickListener { onClick() }
            }
        }
    }

    private fun navigateConnectionBack(): Boolean {
        return when (connectionMode) {
            ConnectionMode.DEVICE_LIST -> false
            ConnectionMode.LOCAL_ROOTS -> {
                showConnectionDeviceList()
                true
            }
            ConnectionMode.LOCAL_FILES -> {
                val root = currentLocalStorageRoot?.directPath?.let(::File)
                    ?: return false
                val directory = currentLocalDirectory ?: return false
                if (directory == root) {
                    showLocalStorageRoots()
                } else {
                    val parentDirectory = directory.parentFile
                    if (parentDirectory == null || !parentDirectory.absolutePath.startsWith(root.absolutePath)) {
                        showLocalStorageRoots()
                    } else {
                        openLocalDirectory(parentDirectory)
                    }
                }
                true
            }
            ConnectionMode.REMOTE_FILES -> {
                if (currentRemotePath == "/") {
                    showConnectionDeviceList()
                } else {
                    loadRemotePath(parentRemotePath(currentRemotePath))
                }
                true
            }
        }
    }

    private fun showRemoteWaitingDialog(device: WindowsServer) {
        connectionDialog?.dismiss()
        connectionDialog = AlertDialog.Builder(this)
            .setTitle(R.string.remote_connection_waiting_title)
            .setMessage(getString(R.string.remote_connection_waiting_message, device.deviceName))
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    private fun showRemoteConnectionError(throwable: Throwable) {
        val error = throwable.findAppError()
        val title = when (error) {
            AppError.RemoteConnectionRejected -> R.string.remote_connection_rejected_title
            AppError.RemoteApprovalTimedOut -> R.string.remote_connection_timeout_title
            else -> R.string.remote_connection_error_title
        }
        val message = error?.toUserMessage(this)
            ?: throwable.message
            ?: getString(R.string.remote_files_load_failed)
        connectionDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.remote_connection_retry) { _, _ ->
                loadRemotePath(currentRemotePath)
            }
            .setNegativeButton(R.string.remote_connection_close) { _, _ ->
                showConnectionDeviceList()
            }
            .setOnCancelListener {
                showConnectionDeviceList()
            }
            .create()
            .also { it.show() }
    }

    private fun normalizeRemotePath(path: String): String {
        val normalized = path.trim().replace('\\', '/')
        if (normalized.isBlank() || normalized == "/") return "/"
        return "/" + normalized.trim('/').split('/').filter { it.isNotBlank() }.joinToString("/")
    }

    private fun joinRemotePath(parentPath: String, child: String): String {
        return normalizeRemotePath("${parentPath.trimEnd('/')}/${child}")
    }

    private fun parentRemotePath(path: String): String {
        val normalized = normalizeRemotePath(path)
        if (normalized == "/") return "/"
        val parts = normalized.trim('/').split('/').dropLast(1)
        return if (parts.isEmpty()) "/" else "/" + parts.joinToString("/")
    }

    private fun refreshStorageRootsState() {
        lastStorageRoots = storageRootsRepository.listRoots()
        storageRootsScreen.show(lastStorageRoots)
    }

    private fun handleStorageRootEnabledChanged(root: StorageRootEntry, enabled: Boolean) {
        if (enabled && root.accessState != StorageAccessState.READY) {
            refreshStorageRootsState()
            showStoragePermissionDialog(root)
            return
        }

        storageRootsRepository.setEnabled(root.id, enabled)
        refreshStorageRootsState()
        DropMeDeviceService.refreshStorageRoots(this)
    }

    private fun handleStorageRootRemove(root: StorageRootEntry) {
        if (!root.canRemove) {
            return
        }
        storageRootsRepository.removeCustomRoot(root.id)
        refreshStorageRootsState()
        DropMeDeviceService.refreshStorageRoots(this)
    }

    private fun handleStorageRootSelected(root: StorageRootEntry) {
        if (root.accessState != StorageAccessState.READY) {
            showStoragePermissionDialog(root)
        }
    }

    private fun showStoragePermissionDialog(root: StorageRootEntry) {
        when (root.accessState) {
            StorageAccessState.READY -> Unit
            StorageAccessState.NEEDS_ALL_FILES_ACCESS -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.storage_permission_required_title)
                    .setMessage(
                        getString(
                            R.string.storage_permission_required_all_files,
                            root.displayName,
                        ),
                    )
                    .setPositiveButton(R.string.storage_permission_grant) { _, _ ->
                        manageAllFilesAccessLauncher.launch(
                            storageRootsRepository.buildManageAllFilesAccessIntent(),
                        )
                    }
                    .setNegativeButton(R.string.remote_connection_close, null)
                    .show()
            }

            StorageAccessState.NEEDS_TREE_GRANT -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.storage_permission_required_title)
                    .setMessage(
                        getString(
                            R.string.storage_permission_required_tree,
                            root.displayName,
                        ),
                    )
                    .setPositiveButton(R.string.storage_permission_grant) { _, _ ->
                        addStorageRootLauncher.launch(root.treeUri)
                    }
                    .setNegativeButton(R.string.remote_connection_close, null)
                    .show()
            }

            StorageAccessState.UNAVAILABLE -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.storage_permission_required_title)
                    .setMessage(
                        getString(
                            R.string.storage_permission_unavailable,
                            root.displayName,
                        ),
                    )
                    .setPositiveButton(R.string.remote_connection_close, null)
                    .show()
            }
        }
    }

    /**
     * Показывает постоянные решения отдельно для разрешённых и заблокированных устройств.
     */
    private fun renderTrustLists() {
        renderTrustSection(
            container = allowedDevicesContainer,
            devices = deviceTrustStore.list(DeviceTrustStore.Decision.ALLOWED),
            replacement = DeviceTrustStore.Decision.BLOCKED,
        )
        renderTrustSection(
            container = blockedDevicesContainer,
            devices = deviceTrustStore.list(DeviceTrustStore.Decision.BLOCKED),
            replacement = DeviceTrustStore.Decision.ALLOWED,
        )
    }

    private fun renderTrustSection(
        container: LinearLayout,
        devices: List<DeviceTrustStore.KnownDevice>,
        replacement: DeviceTrustStore.Decision,
    ) {
        container.removeAllViews()
        if (devices.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.main_trust_list_empty)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }

        devices.forEach { device ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = getDrawable(R.drawable.bg_button)
            }

            row.addView(TextView(this).apply {
                text = device.deviceName
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
            })
            row.addView(TextView(this).apply {
                text = getString(
                    R.string.main_known_device_info,
                    device.lastIp.ifBlank { "—" },
                    device.deviceId,
                )
                setTextColor(getColor(R.color.text_secondary))
                textSize = 10f
            })
            row.addView(TextView(this).apply {
                text = DateFormat.getDateTimeInstance(
                    DateFormat.SHORT,
                    DateFormat.SHORT,
                ).format(device.lastSeenAt)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 10f
            })

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
            }

            actions.addView(
                NeoButtonView(this).apply {
                    text = getString(
                        if (replacement == DeviceTrustStore.Decision.ALLOWED) {
                            R.string.main_trust_allow
                        } else {
                            R.string.main_trust_block
                        },
                    )
                    isAllCaps = false
                    setOnClickListener {
                        deviceTrustStore.remember(
                            deviceId = device.deviceId,
                            deviceName = device.deviceName,
                            ip = device.lastIp,
                            decision = replacement,
                        )
                        renderTrustLists()
                    }
                },
                LinearLayout.LayoutParams(
                    0,
                    dp(44),
                    1f,
                ),
            )

            actions.addView(
                NeoIconActionView(this).apply {
                    setImageResource(MaterialSymbols.close)
                    contentDescription = getString(R.string.main_trust_forget)
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(R.string.main_trust_forget_title)
                            .setMessage(
                                getString(
                                    R.string.main_trust_forget_message,
                                    device.deviceName,
                                ),
                            )
                            .setPositiveButton(R.string.main_trust_forget) { _, _ ->
                                deviceTrustStore.forget(device.deviceId)
                                renderTrustLists()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                },
                LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                    marginStart = dp(8)
                },
            )

            row.addView(
                actions,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )

            container.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(6)
                },
            )
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    /**
     * Запрашивает право показывать approval-уведомления на Android 13+.
     * Без этого background approval может быть полностью скрыт системой.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun restartDeviceServiceIfEnabled() {
        if (identityStore.isDeviceServiceEnabled()) {
            DropMeDeviceService.start(this)
        }
    }

    private enum class MainScreen {
        DEVICE,
        CONNECTION,
    }

    private enum class ConnectionMode {
        DEVICE_LIST,
        LOCAL_ROOTS,
        LOCAL_FILES,
        REMOTE_FILES,
    }

    private companion object {
        const val NEO_UI_PREFERENCES_NAME = "dropme_ui"
        const val SCAN_INTERVAL_MS = 10_000L
    }
}
