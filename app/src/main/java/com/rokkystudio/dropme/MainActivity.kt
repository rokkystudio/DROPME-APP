package com.rokkystudio.dropme

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import badfox.logic.neoui.NeoButtonView
import badfox.logic.neoui.NeoIconButtonView
import badfox.logic.neoui.NeoTopBarView
import badfox.logic.neoui.NeoThemeMode
import badfox.logic.neoui.NeoUiSettings
import com.rokkystudio.dropme.device.DeviceIdentityStore
import com.rokkystudio.dropme.device.DeviceTrustStore
import com.rokkystudio.dropme.network.WindowsServer
import com.rokkystudio.dropme.network.WindowsServerScanner
import com.rokkystudio.dropme.network.WifiNetworkProvider
import com.rokkystudio.dropme.service.DropMeDeviceService
import com.rokkystudio.dropme.storage.StorageAccessState
import com.rokkystudio.dropme.storage.StorageRootEntry
import com.rokkystudio.dropme.storage.StorageRootsRepository
import com.rokkystudio.dropme.ui.ShareServerPickerScreen
import com.rokkystudio.dropme.ui.StorageRootsScreen
import java.text.DateFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
    private lateinit var storageRootsRepository: StorageRootsRepository
    private lateinit var deviceTrustStore: DeviceTrustStore
    private lateinit var devicePickerScreen: ShareServerPickerScreen
    private lateinit var storageRootsScreen: StorageRootsScreen

    private lateinit var neoTopBar: NeoTopBarView
    private lateinit var deviceNameEdit: EditText
    private lateinit var deviceIpText: TextView
    private lateinit var deviceIdText: TextView
    private lateinit var deviceServiceCheckBox: CheckBox
    private lateinit var saveDeviceNameButton: NeoButtonView
    private lateinit var addStorageRootButton: NeoIconButtonView
    private lateinit var storageRootsContainer: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var serverListContainer: LinearLayout
    private lateinit var noServersText: TextView
    private lateinit var allowedDevicesContainer: LinearLayout
    private lateinit var blockedDevicesContainer: LinearLayout

    private var discoveredDevices: List<WindowsServer> = emptyList()
    private var lastStorageRoots: List<StorageRootEntry> = emptyList()

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
            if (!storageRootsRepository.hasAllFilesAccess()) {
                Toast.makeText(this, R.string.main_storage_access_denied, Toast.LENGTH_SHORT).show()
            }
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
        bindActions()
        renderTopBar()
        renderLocalIdentity()
        refreshStorageRootsState()
        renderDeviceList()
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        renderLocalIdentity()
        refreshStorageRootsState()
        startScanLoop()
    }

    override fun onResume() {
        super.onResume()
        renderLocalIdentity()
        refreshStorageRootsState()
    }

    override fun onStop() {
        activityStarted = false
        scanHandler.removeCallbacks(nextScanRunnable)
        super.onStop()
    }

    override fun onDestroy() {
        scanHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun bindViews() {
        neoTopBar = findViewById(R.id.neoTopBar)
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
        storageRootsRepository = StorageRootsRepository(applicationContext)
        deviceTrustStore = DeviceTrustStore(applicationContext)
        devicePickerScreen = ShareServerPickerScreen(
            context = this,
            container = serverListContainer,
            onServerSelected = ::openDevice,
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
                deviceIpText.text = getString(
                    R.string.main_device_ip,
                    ip.ifBlank { getString(R.string.main_device_ip_unavailable) },
                )
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
        if (!activityStarted || scanInProgress) {
            return
        }

        scanInProgress = true
        val startedAt = SystemClock.elapsedRealtime()
        progressBar.visibility = View.VISIBLE

        executor.execute {
            try {
                val wifiInfo = wifiNetworkProvider.getWifiNetworkInfo()
                runOnUiThread {
                    deviceIpText.text = getString(
                        R.string.main_device_ip,
                        wifiInfo.ipv4Address.hostAddress.orEmpty(),
                    )
                }

                deviceScanner.scan(
                    wifiInfo = wifiInfo,
                    onServerFound = { devices ->
                        runOnUiThread {
                            if (activityStarted) {
                                mergeDiscoveredDevices(devices)
                            }
                        }
                    },
                    shouldContinue = {
                        activityStarted && !Thread.currentThread().isInterrupted
                    },
                ).also { devices ->
                    runOnUiThread {
                        if (activityStarted) {
                            replaceDiscoveredDevices(devices)
                        }
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    if (activityStarted) {
                        discoveredDevices = emptyList()
                        renderDeviceList()
                    }
                }
            } finally {
                scanInProgress = false
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val delay = (SCAN_INTERVAL_MS - elapsed).coerceAtLeast(0L)
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    if (activityStarted) {
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
        devicePickerScreen.show(discoveredDevices)
        val hasDevices = discoveredDevices.isNotEmpty()
        serverListContainer.visibility = if (hasDevices) View.VISIBLE else View.GONE
        noServersText.visibility = if (hasDevices) View.GONE else View.VISIBLE
    }

    private fun openDevice(device: WindowsServer) {
        if (!device.fileAccessAvailable || device.filePort == null) {
            Toast.makeText(this, R.string.main_remote_files_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(RemoteFilesActivity.createIntent(this, device))
    }

    private fun refreshStorageRootsState() {
        lastStorageRoots = storageRootsRepository.listRoots()
        storageRootsScreen.show(lastStorageRoots)
    }

    private fun handleStorageRootEnabledChanged(root: StorageRootEntry, enabled: Boolean) {
        storageRootsRepository.setEnabled(root.id, enabled)
        if (enabled && root.accessState != StorageAccessState.READY) {
            handleStorageRootSelected(root)
        }
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
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = getDrawable(R.drawable.bg_button)
            }
            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            details.addView(TextView(this).apply {
                text = device.deviceName
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
            })
            details.addView(TextView(this).apply {
                text = getString(
                    R.string.main_known_device_info,
                    device.lastIp.ifBlank { "—" },
                    device.deviceId,
                )
                setTextColor(getColor(R.color.text_secondary))
                textSize = 10f
            })
            details.addView(TextView(this).apply {
                text = DateFormat.getDateTimeInstance(
                    DateFormat.SHORT,
                    DateFormat.SHORT,
                ).format(device.lastSeenAt)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 10f
            })
            row.addView(
                details,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )

            row.addView(NeoButtonView(this).apply {
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
            })
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
    private fun restartDeviceServiceIfEnabled() {
        if (identityStore.isDeviceServiceEnabled()) {
            DropMeDeviceService.start(this)
        }
    }

    private companion object {
        const val NEO_UI_PREFERENCES_NAME = "dropme_ui"
        const val SCAN_INTERVAL_MS = 10_000L
    }
}
