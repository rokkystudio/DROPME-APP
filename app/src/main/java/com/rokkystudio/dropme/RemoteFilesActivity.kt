package com.rokkystudio.dropme

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import badfox.logic.neoui.NeoButtonView
import badfox.logic.neoui.NeoTopBarView
import badfox.logic.neoui.NeoThemeMode
import badfox.logic.neoui.NeoUiSettings
import com.rokkystudio.dropme.network.RemoteFileClient
import com.rokkystudio.dropme.network.WifiNetworkProvider
import com.rokkystudio.dropme.network.WindowsServer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Показывает удалённые файлы DROPME внутри приложения без внешнего браузера.
 */
class RemoteFilesActivity : AppCompatActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val previewExecutor: ExecutorService = Executors.newFixedThreadPool(4)

    private lateinit var neoTopBar: NeoTopBarView
    private lateinit var deviceInfoText: TextView
    private lateinit var pathText: TextView
    private lateinit var upButton: NeoButtonView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var filesContainer: LinearLayout

    private lateinit var wifiNetworkProvider: WifiNetworkProvider
    private lateinit var remoteFileClient: RemoteFileClient
    private lateinit var device: WindowsServer

    private var currentPath: String = "/"
    private var sessionAuthorized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        device = readDevice(intent) ?: run {
            finish()
            return
        }

        setContentView(R.layout.activity_remote_files)
        bindViews()
        wifiNetworkProvider = WifiNetworkProvider(applicationContext)
        remoteFileClient = RemoteFileClient(applicationContext)

        neoTopBar.setAppInfo(
            title = device.deviceName,
            subtitle = getString(R.string.remote_files_title),
            version = BuildConfig.VERSION_NAME,
            iconResId = R.mipmap.ic_launcher,
        )
        NeoUiSettings(
            context = this,
            preferencesName = NEO_UI_PREFERENCES_NAME,
            defaultTheme = NeoThemeMode.DARK,
            defaultLanguageTag = null,
        ).bindTopBar(neoTopBar)

        deviceInfoText.text = getString(
            R.string.remote_files_device_info,
            device.host,
            device.deviceId,
        )
        upButton.setOnClickListener {
            navigateUp()
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (currentPath == "/") {
                        finish()
                    } else {
                        navigateUp()
                    }
                }
            },
        )

        loadPath("/")
    }

    override fun onDestroy() {
        executor.shutdownNow()
        previewExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun bindViews() {
        neoTopBar = findViewById(R.id.neoTopBar)
        deviceInfoText = findViewById(R.id.remoteDeviceInfoText)
        pathText = findViewById(R.id.remotePathText)
        upButton = findViewById(R.id.remoteUpButton)
        progressBar = findViewById(R.id.remoteProgressBar)
        emptyText = findViewById(R.id.remoteEmptyText)
        filesContainer = findViewById(R.id.remoteFilesContainer)
    }

    private fun loadPath(path: String) {
        currentPath = normalizePath(path)
        pathText.text = currentPath
        upButton.isEnabled = currentPath != "/"
        progressBar.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        filesContainer.removeAllViews()

        executor.execute {
            runCatching {
                val wifiInfo = wifiNetworkProvider.getWifiNetworkInfo()
                if (!sessionAuthorized) {
                    remoteFileClient.authorize(wifiInfo, device)
                    sessionAuthorized = true
                }
                remoteFileClient.list(wifiInfo, device, currentPath)
            }.onSuccess { entries ->
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    renderEntries(entries)
                }
            }.onFailure { throwable ->
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    Toast.makeText(
                        this,
                        throwable.message ?: getString(R.string.remote_files_load_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun renderEntries(entries: List<RemoteFileClient.Entry>) {
        filesContainer.removeAllViews()
        emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        val renderedPath = currentPath
        entries.forEach { entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = getDrawable(R.drawable.bg_button)
                isClickable = true
                isFocusable = true
            }

            val previewView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                    marginEnd = dp(10)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                visibility = View.GONE
            }
            row.addView(previewView)

            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            details.addView(TextView(this).apply {
                text = if (entry.isDirectory) "📁 ${entry.name}" else entry.name
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
            })
            details.addView(TextView(this).apply {
                text = if (entry.isDirectory) {
                    getString(R.string.remote_files_folder)
                } else {
                    Formatter.formatShortFileSize(this@RemoteFilesActivity, entry.size)
                }
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11f
            })
            row.addView(
                details,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ),
            )

            row.setOnClickListener {
                if (entry.isDirectory) {
                    loadPath(childPath(currentPath, entry.name))
                } else {
                    downloadFile(entry)
                }
            }

            filesContainer.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(8)
                },
            )

            if (!entry.isDirectory && supportsPreview(entry.name)) {
                loadPreview(
                    entry = entry,
                    remotePath = childPath(renderedPath, entry.name),
                    renderedPath = renderedPath,
                    previewView = previewView,
                )
            }
        }
    }

    private fun loadPreview(
        entry: RemoteFileClient.Entry,
        remotePath: String,
        renderedPath: String,
        previewView: ImageView,
    ) {
        previewExecutor.execute {
            val previewFile = runCatching {
                val wifiInfo = wifiNetworkProvider.getWifiNetworkInfo()
                remoteFileClient.preview(
                    wifiInfo = wifiInfo,
                    device = device,
                    remotePath = remotePath,
                    lastModified = entry.lastModified,
                    requestedSize = 256,
                )
            }.getOrNull() ?: return@execute
            val bitmap = BitmapFactory.decodeFile(previewFile.absolutePath) ?: return@execute
            runOnUiThread {
                if (currentPath == renderedPath && previewView.isAttachedToWindow) {
                    previewView.setImageBitmap(bitmap)
                    previewView.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun supportsPreview(fileName: String): Boolean {
        return fileName.substringAfterLast('.', "").lowercase() in PREVIEW_EXTENSIONS
    }

    private fun downloadFile(entry: RemoteFileClient.Entry) {
        progressBar.visibility = View.VISIBLE
        executor.execute {
            runCatching {
                val wifiInfo = wifiNetworkProvider.getWifiNetworkInfo()
                remoteFileClient.download(
                    wifiInfo = wifiInfo,
                    device = device,
                    remotePath = childPath(currentPath, entry.name),
                    fileName = entry.name,
                )
            }.onSuccess { file ->
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    Toast.makeText(
                        this,
                        getString(R.string.remote_files_downloaded, file.absolutePath),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }.onFailure { throwable ->
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    Toast.makeText(
                        this,
                        throwable.message ?: getString(R.string.remote_files_download_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun navigateUp() {
        if (currentPath == "/") {
            return
        }
        val segments = currentPath.trim('/').split('/').filter { it.isNotBlank() }.dropLast(1)
        loadPath(if (segments.isEmpty()) "/" else "/" + segments.joinToString("/"))
    }

    private fun childPath(parent: String, name: String): String {
        return if (parent == "/") "/$name" else "$parent/$name"
    }

    private fun normalizePath(path: String): String {
        val normalized = "/" + path.trim('/').split('/').filter { it.isNotBlank() }.joinToString("/")
        return if (normalized == "/") "/" else normalized
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val NEO_UI_PREFERENCES_NAME = "dropme_ui"
        private val PREVIEW_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif",
            "mp4", "m4v", "mov", "mkv", "webm", "3gp", "avi",
        )
        private const val EXTRA_HOST = "host"
        private const val EXTRA_TCP_PORT = "tcp_port"
        private const val EXTRA_FILE_PORT = "file_port"
        private const val EXTRA_DEVICE_ID = "device_id"
        private const val EXTRA_DEVICE_NAME = "device_name"
        private const val EXTRA_DEVICE_TYPE = "device_type"

        fun createIntent(context: Context, device: WindowsServer): Intent {
            return Intent(context, RemoteFilesActivity::class.java)
                .putExtra(EXTRA_HOST, device.host)
                .putExtra(EXTRA_TCP_PORT, device.tcpPort)
                .putExtra(EXTRA_FILE_PORT, device.filePort ?: 0)
                .putExtra(EXTRA_DEVICE_ID, device.deviceId)
                .putExtra(EXTRA_DEVICE_NAME, device.deviceName)
                .putExtra(EXTRA_DEVICE_TYPE, device.deviceType)
        }

        private fun readDevice(intent: Intent): WindowsServer? {
            val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
            val tcpPort = intent.getIntExtra(EXTRA_TCP_PORT, 0)
            val filePort = intent.getIntExtra(EXTRA_FILE_PORT, 0).takeIf { it > 0 }
            val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty()
            val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME).orEmpty()
            val deviceType = intent.getStringExtra(EXTRA_DEVICE_TYPE).orEmpty()
            if (host.isBlank() || tcpPort <= 0 || filePort == null || deviceId.isBlank()) {
                return null
            }
            return WindowsServer(
                host = host,
                tcpPort = tcpPort,
                udpPort = null,
                deviceName = deviceName.ifBlank { host },
                protocolVersion = 3,
                deviceId = deviceId,
                deviceType = deviceType.ifBlank { "unknown" },
                filePort = filePort,
                fileAccessAvailable = true,
                shareAvailable = false,
            )
        }
    }
}
