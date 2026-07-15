package com.rokkystudio.wifidrop

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.rokkystudio.wifidrop.network.WifiDropScanner
import com.rokkystudio.wifidrop.network.WifiNetworkProvider
import com.rokkystudio.wifidrop.network.WindowsServer
import com.rokkystudio.wifidrop.network.WindowsUploadClient
import com.rokkystudio.wifidrop.storage.SharedFileReader
import com.rokkystudio.wifidrop.ui.ShareServerPickerScreen
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Принимает Share Intent, ищет Windows-серверы WiFiDrop в локальной Wi‑Fi сети
 * и загружает выбранные файлы в upload endpoint Windows.
 */
class ShareActivity : AppCompatActivity() {
    private enum class FileTransferState {
        PENDING,
        SUCCESS,
        FAILED,
    }

    private data class FileTransferUiItem(
        val file: SharedFileReader.SharedFile,
        val state: FileTransferState,
        val message: String,
    )

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var serverListTitle: TextView
    private lateinit var serverListContainer: LinearLayout
    private lateinit var fileListTitle: TextView
    private lateinit var fileListContainer: LinearLayout
    private lateinit var retryButton: Button
    private lateinit var closeButton: Button

    private lateinit var wifiNetworkProvider: WifiNetworkProvider
    private lateinit var wifiDropScanner: WifiDropScanner
    private lateinit var sharedFileReader: SharedFileReader
    private lateinit var windowsUploadClient: WindowsUploadClient
    private lateinit var serverPickerScreen: ShareServerPickerScreen

    private var sharedFiles: List<SharedFileReader.SharedFile> = emptyList()
    private var lastWifiInfo: WifiNetworkProvider.WifiNetworkInfo? = null
    private val layoutInflaterInstance by lazy { LayoutInflater.from(this) }

    /**
     * Создаёт экран обработки Share Intent и запускает сценарий передачи.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_share)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.shareRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        bindViews()
        bindDependencies()
        bindActions()
        startShareFlow()
    }

    /**
     * Останавливает фоновый executor при закрытии экрана.
     */
    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    /**
     * Привязывает view из layout.
     */
    private fun bindViews() {
        statusText = findViewById(R.id.shareStatusText)
        detailText = findViewById(R.id.shareDetailText)
        progressBar = findViewById(R.id.shareProgressBar)
        serverListTitle = findViewById(R.id.shareServerListTitle)
        serverListContainer = findViewById(R.id.shareServerListContainer)
        fileListTitle = findViewById(R.id.shareFileListTitle)
        fileListContainer = findViewById(R.id.shareFileListContainer)
        retryButton = findViewById(R.id.retryButton)
        closeButton = findViewById(R.id.closeButton)
    }

    /**
     * Создаёт зависимости Share Activity.
     */
    private fun bindDependencies() {
        wifiNetworkProvider = WifiNetworkProvider(applicationContext)
        wifiDropScanner = WifiDropScanner()
        sharedFileReader = SharedFileReader(applicationContext)
        windowsUploadClient = WindowsUploadClient(sharedFileReader)
        serverPickerScreen = ShareServerPickerScreen(
            context = this,
            container = serverListContainer,
            onServerSelected = ::uploadToServer,
        )
    }

    /**
     * Назначает действия для кнопок экрана.
     */
    private fun bindActions() {
        retryButton.setOnClickListener {
            startShareFlow()
        }
        closeButton.setOnClickListener {
            finish()
        }
    }

    /**
     * Запускает чтение Share Intent и поиск Windows-серверов.
     */
    private fun startShareFlow() {
        serverListContainer.removeAllViews()
        renderFileItems(emptyList())
        showLoading(getString(R.string.share_status_preparing), null)
        executor.execute {
            try {
                sharedFiles = sharedFileReader.readFromIntent(intent)
                runOnUiThread {
                    renderFileItems(sharedFiles.map(::pendingFileItem))
                }
                val wifiInfo = wifiNetworkProvider.getWifiNetworkInfo()
                lastWifiInfo = wifiInfo
                runOnUiThread {
                    showLoading(getString(R.string.share_status_scanning), null)
                }
                val servers = wifiDropScanner.scan(wifiInfo)
                runOnUiThread {
                    handleDiscoveredServers(servers)
                }
            } catch (throwable: Throwable) {
                val error = throwable.toWiFiDropError(
                    WiFiDropError.UnknownError(getString(R.string.share_status_error_title)),
                )
                runOnUiThread {
                    handleError(error)
                }
            }
        }
    }

    /**
     * Обрабатывает найденные Windows-серверы и продолжает сценарий передачи.
     */
    private fun handleDiscoveredServers(servers: List<WindowsServer>) {
        if (servers.isEmpty()) {
            handleError(WiFiDropError.ServerNotFound)
            return
        }

        statusText.text = getString(R.string.share_status_select_server)
        detailText.text = if (servers.size == 1) {
            getString(R.string.share_status_select_server_detail_single)
        } else {
            getString(R.string.share_status_select_server_detail_multiple, servers.size)
        }
        progressBar.visibility = View.GONE
        retryButton.visibility = View.GONE
        closeButton.visibility = View.VISIBLE
        serverListTitle.visibility = View.VISIBLE
        serverListContainer.visibility = View.VISIBLE
        serverPickerScreen.show(servers)
    }

    /**
     * Выполняет отправку файлов на выбранный Windows-сервер.
     */
    private fun uploadToServer(server: WindowsServer) {
        val wifiInfo = lastWifiInfo
        if (wifiInfo == null) {
            handleError(WiFiDropError.NoWifiNetwork)
            return
        }

        renderFileItems(sharedFiles.map(::pendingFileItem))
        showLoading(
            getString(R.string.share_status_uploading, server.deviceName),
            getString(R.string.share_status_uploading_detail, server.host, server.tcpPort),
        )
        executor.execute {
            try {
                val results = windowsUploadClient.uploadFiles(wifiInfo, server, sharedFiles)
                runOnUiThread {
                    showUploadResults(server, results)
                }
            } catch (throwable: Throwable) {
                val error = throwable.toWiFiDropError(
                    WiFiDropError.UploadFailed(reason = getString(R.string.share_status_error_title)),
                )
                val failedResults = sharedFiles.map { file ->
                    WindowsUploadClient.UploadResult(
                        file = file,
                        isSuccess = false,
                        errorMessage = error.toUserMessage(this),
                    )
                }
                runOnUiThread {
                    showUploadResults(server, failedResults)
                }
            }
        }
    }

    /**
     * Отображает ошибку в соответствии с правилами Share MVP.
     */
    private fun handleError(error: WiFiDropError) {
        when (error) {
            WiFiDropError.NoWifiNetwork -> {
                Toast.makeText(this, getString(R.string.share_error_wifi_only), Toast.LENGTH_LONG).show()
                finish()
            }

            WiFiDropError.LocalNetworkBlocked -> {
                Toast.makeText(this, getString(R.string.share_error_local_network_blocked), Toast.LENGTH_LONG).show()
                finish()
            }

            else -> {
                statusText.text = getString(R.string.share_status_error_title)
                detailText.text = error.toUserMessage(this)
                progressBar.visibility = View.GONE
                serverListTitle.visibility = View.GONE
                serverListContainer.visibility = View.GONE
                retryButton.visibility = if (error == WiFiDropError.ServerNotFound) View.VISIBLE else View.GONE
                closeButton.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Показывает состояние ожидания во время поиска или отправки.
     */
    private fun showLoading(status: String, detail: String?) {
        statusText.text = status
        detailText.text = detail
        progressBar.visibility = View.VISIBLE
        serverListTitle.visibility = View.GONE
        serverListContainer.visibility = View.GONE
        retryButton.visibility = View.GONE
        closeButton.visibility = View.GONE
    }

    /**
     * Отображает результаты отправки по каждому файлу и закрывает окно через секунду.
     */
    private fun showUploadResults(
        server: WindowsServer,
        results: List<WindowsUploadClient.UploadResult>,
    ) {
        val successCount = results.count { it.isSuccess }
        val failureCount = results.size - successCount
        statusText.text = when {
            failureCount == 0 -> getString(R.string.share_status_completed_success)
            successCount == 0 -> getString(R.string.share_status_completed_failed)
            else -> getString(R.string.share_status_completed_partial)
        }
        detailText.text = when {
            failureCount == 0 -> getString(R.string.share_result_summary_success, server.deviceName)
            successCount == 0 -> getString(R.string.share_result_summary_failed, server.deviceName)
            else -> getString(R.string.share_result_summary_partial, server.deviceName, failureCount)
        }
        progressBar.visibility = View.GONE
        serverListTitle.visibility = View.GONE
        serverListContainer.visibility = View.GONE
        retryButton.visibility = View.GONE
        closeButton.visibility = View.GONE
        renderFileItems(
            results.map { result ->
                if (result.isSuccess) {
                    FileTransferUiItem(
                        file = result.file,
                        state = FileTransferState.SUCCESS,
                        message = getString(R.string.share_file_status_success),
                    )
                } else {
                    FileTransferUiItem(
                        file = result.file,
                        state = FileTransferState.FAILED,
                        message = getString(
                            R.string.share_file_status_failed,
                            result.errorMessage.orEmpty(),
                        ),
                    )
                }
            },
        )

        val toastMessage = when {
            failureCount == 0 -> getString(R.string.share_result_all_success)
            successCount == 0 -> getString(R.string.share_result_all_failed)
            else -> getString(R.string.share_result_partial, successCount, failureCount)
        }
        Toast.makeText(this, toastMessage, Toast.LENGTH_LONG).show()
        fileListContainer.postDelayed({ finish() }, RESULT_CLOSE_DELAY_MS)
    }

    /**
     * Перерисовывает список файлов с текущими статусами.
     */
    private fun renderFileItems(items: List<FileTransferUiItem>) {
        fileListContainer.removeAllViews()
        fileListTitle.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        fileListContainer.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        items.forEach { item ->
            val row = layoutInflaterInstance.inflate(R.layout.share_file_row, fileListContainer, false)
            val iconView = row.findViewById<TextView>(R.id.shareFileStatusIcon)
            val titleView = row.findViewById<TextView>(R.id.shareFileRowTitle)
            val subtitleView = row.findViewById<TextView>(R.id.shareFileRowSubtitle)
            titleView.text = item.file.displayName
            subtitleView.text = item.message
            when (item.state) {
                FileTransferState.PENDING -> {
                    iconView.text = "•"
                    iconView.setTextColor(ContextCompat.getColor(this, R.color.share_pending))
                    subtitleView.setTextColor(ContextCompat.getColor(this, R.color.share_pending))
                }

                FileTransferState.SUCCESS -> {
                    iconView.text = "✓"
                    iconView.setTextColor(ContextCompat.getColor(this, R.color.share_success))
                    subtitleView.setTextColor(ContextCompat.getColor(this, R.color.share_success))
                }

                FileTransferState.FAILED -> {
                    iconView.text = "✗"
                    iconView.setTextColor(ContextCompat.getColor(this, R.color.share_error))
                    subtitleView.setTextColor(ContextCompat.getColor(this, R.color.share_error))
                }
            }
            fileListContainer.addView(row)
        }
    }

    private fun pendingFileItem(file: SharedFileReader.SharedFile): FileTransferUiItem {
        return FileTransferUiItem(
            file = file,
            state = FileTransferState.PENDING,
            message = getString(R.string.share_file_status_pending),
        )
    }

    private companion object {
        const val RESULT_CLOSE_DELAY_MS = 1_000L
    }
}
