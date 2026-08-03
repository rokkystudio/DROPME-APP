package com.rokkystudio.dropme

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.rokkystudio.dropme.network.WifiNetworkProvider
import com.rokkystudio.dropme.network.WindowsServer
import com.rokkystudio.dropme.network.WindowsServerScanner
import com.rokkystudio.dropme.network.WindowsUploadClient
import com.rokkystudio.dropme.storage.SharedFileReader
import com.rokkystudio.dropme.ui.ShareServerPickerScreen
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Принимает Share Intent, ищет Windows-серверы DROPME в локальной Wi‑Fi сети
 * и загружает выбранные файлы в upload endpoint Windows.
 */
class ShareActivity : AppCompatActivity() {
    private enum class FileTransferState {
        PENDING,
        UPLOADING,
        SUCCESS,
        FAILED,
        CANCELED,
    }

    private data class FileTransferUiItem(
        val file: SharedFileReader.SharedFile,
        val state: FileTransferState,
        val message: String,
        val transferredBytes: Long,
        val totalBytes: Long?,
    )

    private data class FileRowViews(
        val iconView: ImageView,
        val titleView: TextView,
        val statusView: TextView,
        val progressBar: ProgressBar,
        val progressTextView: TextView,
    )

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressSummaryText: TextView
    private lateinit var serverListTitle: TextView
    private lateinit var serverListContainer: LinearLayout
    private lateinit var fileListTitle: TextView
    private lateinit var fileListContainer: LinearLayout
    private lateinit var retryButton: Button
    private lateinit var closeButton: Button

    private lateinit var wifiNetworkProvider: WifiNetworkProvider
    private lateinit var wifiDropScanner: WindowsServerScanner
    private lateinit var sharedFileReader: SharedFileReader
    private lateinit var windowsUploadClient: WindowsUploadClient
    private lateinit var serverPickerScreen: ShareServerPickerScreen

    private var sharedFiles: List<SharedFileReader.SharedFile> = emptyList()
    private var lastWifiInfo: WifiNetworkProvider.WifiNetworkInfo? = null
    private val layoutInflaterInstance by lazy { LayoutInflater.from(this) }
    private val fileItems = linkedMapOf<SharedFileReader.SharedFile, FileTransferUiItem>()
    private val fileRowViews = linkedMapOf<SharedFileReader.SharedFile, FileRowViews>()

    @Volatile
    private var cancelRequested = false
    private var isUploadInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_share)
        val rootView = findViewById<View>(R.id.shareRoot)
        val basePaddingLeft = rootView.paddingLeft
        val basePaddingTop = rootView.paddingTop
        val basePaddingRight = rootView.paddingRight
        val basePaddingBottom = rootView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                basePaddingLeft + bars.left,
                basePaddingTop + bars.top,
                basePaddingRight + bars.right,
                basePaddingBottom + bars.bottom,
            )
            insets
        }

        bindViews()
        bindDependencies()
        bindActions()
        startShareFlow()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun bindViews() {
        statusText = findViewById(R.id.shareStatusText)
        detailText = findViewById(R.id.shareDetailText)
        progressBar = findViewById(R.id.shareProgressBar)
        progressSummaryText = findViewById(R.id.shareProgressSummaryText)
        serverListTitle = findViewById(R.id.shareServerListTitle)
        serverListContainer = findViewById(R.id.shareServerListContainer)
        fileListTitle = findViewById(R.id.shareFileListTitle)
        fileListContainer = findViewById(R.id.shareFileListContainer)
        retryButton = findViewById(R.id.retryButton)
        closeButton = findViewById(R.id.closeButton)
    }

    private fun bindDependencies() {
        wifiNetworkProvider = WifiNetworkProvider(applicationContext)
        wifiDropScanner = WindowsServerScanner()
        sharedFileReader = SharedFileReader(applicationContext)
        windowsUploadClient = WindowsUploadClient(sharedFileReader)
        serverPickerScreen = ShareServerPickerScreen(
            context = this,
            container = serverListContainer,
            onServerSelected = ::uploadToServer,
        )
    }

    private fun bindActions() {
        retryButton.setOnClickListener {
            startShareFlow()
        }
        closeButton.setOnClickListener {
            if (isUploadInProgress) {
                requestUploadCancellation()
            } else {
                finish()
            }
        }
    }

    private fun startShareFlow() {
        cancelRequested = false
        isUploadInProgress = false
        closeButton.isEnabled = true
        closeButton.setText(R.string.share_action_close)
        retryButton.visibility = View.GONE
        closeButton.visibility = View.GONE
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
                val error = throwable.toAppError(
                    AppError.UnknownError(getString(R.string.share_status_error_title)),
                )
                runOnUiThread {
                    handleError(error)
                }
            }
        }
    }

    private fun handleDiscoveredServers(servers: List<WindowsServer>) {
        if (servers.isEmpty()) {
            handleError(AppError.ServerNotFound)
            return
        }

        statusText.text = getString(R.string.share_status_select_server)
        detailText.text = if (servers.size == 1) {
            getString(R.string.share_status_select_server_detail_single)
        } else {
            getString(R.string.share_status_select_server_detail_multiple, servers.size)
        }
        progressBar.visibility = View.GONE
        progressSummaryText.visibility = View.GONE
        retryButton.visibility = View.GONE
        closeButton.visibility = View.VISIBLE
        closeButton.isEnabled = true
        closeButton.setText(R.string.share_action_close)
        serverListTitle.visibility = View.VISIBLE
        serverListContainer.visibility = View.VISIBLE
        serverPickerScreen.show(servers)
    }

    private fun uploadToServer(server: WindowsServer) {
        val wifiInfo = lastWifiInfo
        if (wifiInfo == null) {
            handleError(AppError.NoWifiNetwork)
            return
        }

        renderFileItems(sharedFiles.map(::pendingFileItem))
        showUploadLoading(
            getString(R.string.share_status_uploading, server.deviceName),
            getString(R.string.share_status_uploading_detail, server.host, server.tcpPort),
        )
        executor.execute {
            try {
                val results = windowsUploadClient.uploadFiles(
                    wifiInfo = wifiInfo,
                    server = server,
                    files = sharedFiles,
                    onProgress = { progress ->
                        runOnUiThread {
                            showUploadProgress(progress)
                        }
                    },
                    onFileCompleted = { result ->
                        runOnUiThread {
                            updateFileItem(result.toUiItem())
                        }
                    },
                    shouldCancel = { cancelRequested },
                )
                runOnUiThread {
                    showUploadResults(server, results)
                }
            } catch (throwable: Throwable) {
                val error = throwable.toAppError(
                    AppError.UploadFailed(reason = getString(R.string.share_status_error_title)),
                )
                val failedResults = sharedFiles.map { file ->
                    WindowsUploadClient.UploadResult(
                        file = file,
                        status = WindowsUploadClient.UploadStatus.FAILED,
                        errorMessage = error.toUserMessage(this),
                    )
                }
                runOnUiThread {
                    showUploadResults(server, failedResults)
                }
            }
        }
    }

    private fun handleError(error: AppError) {
        isUploadInProgress = false
        closeButton.isEnabled = true
        closeButton.setText(R.string.share_action_close)
        when (error) {
            AppError.NoWifiNetwork -> {
                Toast.makeText(this, getString(R.string.share_error_wifi_only), Toast.LENGTH_LONG).show()
                finish()
            }

            AppError.LocalNetworkBlocked -> {
                Toast.makeText(this, getString(R.string.share_error_local_network_blocked), Toast.LENGTH_LONG).show()
                finish()
            }

            else -> {
                statusText.text = getString(R.string.share_status_error_title)
                detailText.text = error.toUserMessage(this)
                progressBar.visibility = View.GONE
                progressSummaryText.visibility = View.GONE
                serverListTitle.visibility = View.GONE
                serverListContainer.visibility = View.GONE
                retryButton.visibility = if (error == AppError.ServerNotFound) View.VISIBLE else View.GONE
                closeButton.visibility = View.VISIBLE
            }
        }
    }

    private fun showLoading(status: String, detail: String?) {
        statusText.text = status
        detailText.text = detail
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        progressSummaryText.visibility = View.GONE
        serverListTitle.visibility = View.GONE
        serverListContainer.visibility = View.GONE
        retryButton.visibility = View.GONE
        closeButton.visibility = View.GONE
    }

    private fun showUploadLoading(status: String, detail: String?) {
        isUploadInProgress = true
        cancelRequested = false
        showLoading(status, detail)
        progressBar.isIndeterminate = false
        progressBar.max = MAX_PROGRESS
        progressBar.progress = 0
        progressBar.progressTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.share_uploading),
        )
        renderOverallProgress(0L, totalUploadBytes())
        closeButton.visibility = View.VISIBLE
        closeButton.isEnabled = true
        closeButton.setText(R.string.share_action_cancel)
    }

    private fun showUploadProgress(progress: WindowsUploadClient.UploadProgress) {
        renderOverallProgress(progress.totalBytesUploaded, progress.totalBytesToUpload)
        updateFileItem(
            FileTransferUiItem(
                file = progress.file,
                state = FileTransferState.UPLOADING,
                message = getString(R.string.share_file_status_uploading),
                transferredBytes = progress.fileBytesUploaded,
                totalBytes = progress.fileSizeBytes,
            ),
        )
    }

    private fun showUploadResults(
        server: WindowsServer,
        results: List<WindowsUploadClient.UploadResult>,
    ) {
        isUploadInProgress = false
        closeButton.isEnabled = true
        closeButton.setText(R.string.share_action_close)

        val successCount = results.count { it.status == WindowsUploadClient.UploadStatus.SUCCESS }
        val failureCount = results.count { it.status == WindowsUploadClient.UploadStatus.FAILED }
        val canceledCount = results.count { it.status == WindowsUploadClient.UploadStatus.CANCELED }

        statusText.text = when {
            canceledCount > 0 && successCount > 0 -> getString(R.string.share_status_completed_canceled_partial)
            canceledCount > 0 -> getString(R.string.share_status_completed_canceled)
            failureCount == 0 -> getString(R.string.share_status_completed_success)
            successCount == 0 -> getString(R.string.share_status_completed_failed)
            else -> getString(R.string.share_status_completed_partial)
        }
        detailText.text = when {
            canceledCount > 0 -> getString(
                R.string.share_result_summary_canceled,
                server.deviceName,
                successCount,
                canceledCount,
            )
            failureCount == 0 -> getString(R.string.share_result_summary_success, server.deviceName)
            successCount == 0 -> getString(R.string.share_result_summary_failed, server.deviceName)
            else -> getString(R.string.share_result_summary_partial, server.deviceName, failureCount)
        }
        progressBar.visibility = View.GONE
        progressSummaryText.visibility = View.GONE
        serverListTitle.visibility = View.GONE
        serverListContainer.visibility = View.GONE
        retryButton.visibility = View.GONE
        closeButton.visibility = View.VISIBLE
        renderFileItems(results.map { result -> result.toUiItem() })

        val toastMessage = when {
            canceledCount > 0 -> getString(R.string.share_result_canceled, successCount, canceledCount)
            failureCount == 0 -> getString(R.string.share_result_all_success)
            successCount == 0 -> getString(R.string.share_result_all_failed)
            else -> getString(R.string.share_result_partial, successCount, failureCount)
        }
        Toast.makeText(this, toastMessage, Toast.LENGTH_LONG).show()
    }

    private fun renderFileItems(items: List<FileTransferUiItem>) {
        fileItems.clear()
        fileRowViews.clear()
        fileListContainer.removeAllViews()
        fileListTitle.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        fileListContainer.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        items.forEach { item ->
            val row = layoutInflaterInstance.inflate(R.layout.share_file_row, fileListContainer, false)
            val rowViews = FileRowViews(
                iconView = row.findViewById(R.id.shareFileStatusIcon),
                titleView = row.findViewById(R.id.shareFileRowTitle),
                statusView = row.findViewById(R.id.shareFileRowSubtitle),
                progressBar = row.findViewById(R.id.shareFileRowProgressBar),
                progressTextView = row.findViewById(R.id.shareFileRowProgressText),
            )
            fileItems[item.file] = item
            fileRowViews[item.file] = rowViews
            bindFileRow(rowViews, item)
            fileListContainer.addView(row)
        }
    }

    private fun pendingFileItem(file: SharedFileReader.SharedFile): FileTransferUiItem {
        return FileTransferUiItem(
            file = file,
            state = FileTransferState.PENDING,
            message = getString(R.string.share_file_status_pending),
            transferredBytes = 0L,
            totalBytes = file.sizeBytes,
        )
    }

    private fun updateFileItem(item: FileTransferUiItem) {
        fileItems[item.file] = item
        fileRowViews[item.file]?.let { rowViews ->
            bindFileRow(rowViews, item)
        }
    }

    private fun bindFileRow(rowViews: FileRowViews, item: FileTransferUiItem) {
        rowViews.titleView.text = item.file.displayName
        rowViews.statusView.text = item.message
        rowViews.progressBar.isIndeterminate = false
        rowViews.progressBar.max = MAX_PROGRESS
        rowViews.progressBar.progress = progressPercent(item.transferredBytes, item.totalBytes, item.state)
        rowViews.progressTextView.text = formatProgressText(item.transferredBytes, item.totalBytes)

        when (item.state) {
            FileTransferState.PENDING -> {
                val color = ContextCompat.getColor(this, R.color.share_pending)
                rowViews.iconView.setImageResource(android.R.drawable.presence_invisible)
                rowViews.iconView.imageTintList = ColorStateList.valueOf(color)
                rowViews.statusView.setTextColor(color)
                rowViews.progressTextView.setTextColor(color)
                rowViews.progressBar.progressTintList = ColorStateList.valueOf(color)
            }

            FileTransferState.UPLOADING -> {
                val color = ContextCompat.getColor(this, R.color.share_uploading)
                rowViews.iconView.setImageResource(android.R.drawable.stat_sys_upload)
                rowViews.iconView.imageTintList = ColorStateList.valueOf(color)
                rowViews.statusView.setTextColor(color)
                rowViews.progressTextView.setTextColor(color)
                rowViews.progressBar.progressTintList = ColorStateList.valueOf(color)
            }

            FileTransferState.SUCCESS -> {
                val color = ContextCompat.getColor(this, R.color.share_success)
                rowViews.iconView.setImageResource(android.R.drawable.checkbox_on_background)
                rowViews.iconView.imageTintList = ColorStateList.valueOf(color)
                rowViews.statusView.setTextColor(color)
                rowViews.progressTextView.setTextColor(color)
                rowViews.progressBar.progressTintList = ColorStateList.valueOf(color)
            }

            FileTransferState.FAILED -> {
                val color = ContextCompat.getColor(this, R.color.share_error)
                rowViews.iconView.setImageResource(android.R.drawable.ic_delete)
                rowViews.iconView.imageTintList = ColorStateList.valueOf(color)
                rowViews.statusView.setTextColor(color)
                rowViews.progressTextView.setTextColor(color)
                rowViews.progressBar.progressTintList = ColorStateList.valueOf(color)
            }

            FileTransferState.CANCELED -> {
                val color = ContextCompat.getColor(this, R.color.share_pending)
                rowViews.iconView.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                rowViews.iconView.imageTintList = ColorStateList.valueOf(color)
                rowViews.statusView.setTextColor(color)
                rowViews.progressTextView.setTextColor(color)
                rowViews.progressBar.progressTintList = ColorStateList.valueOf(color)
            }
        }
    }

    private fun renderOverallProgress(uploadedBytes: Long, totalBytes: Long) {
        progressSummaryText.visibility = View.VISIBLE
        progressBar.isIndeterminate = false
        progressBar.max = MAX_PROGRESS
        progressBar.progress = progressPercent(uploadedBytes, totalBytes, FileTransferState.UPLOADING)
        progressSummaryText.text = formatProgressText(uploadedBytes, totalBytes)
    }

    private fun progressPercent(
        uploadedBytes: Long,
        totalBytes: Long?,
        state: FileTransferState,
    ): Int {
        val normalizedUploaded = uploadedBytes.coerceAtLeast(0L)
        val normalizedTotal = totalBytes?.coerceAtLeast(0L) ?: 0L
        if (normalizedTotal == 0L) {
            return if (state == FileTransferState.SUCCESS) MAX_PROGRESS else 0
        }
        return ((normalizedUploaded.coerceAtMost(normalizedTotal) * MAX_PROGRESS) / normalizedTotal).toInt()
    }

    private fun formatProgressText(uploadedBytes: Long, totalBytes: Long?): String {
        val uploaded = Formatter.formatShortFileSize(this, uploadedBytes.coerceAtLeast(0L))
        val normalizedTotal = totalBytes?.coerceAtLeast(0L)
        if (normalizedTotal == null || normalizedTotal == 0L) {
            return getString(R.string.share_progress_bytes_only, uploaded)
        }
        val total = Formatter.formatShortFileSize(this, normalizedTotal)
        val percent = ((uploadedBytes.coerceAtLeast(0L).coerceAtMost(normalizedTotal) * 100L) / normalizedTotal).toInt()
        return getString(R.string.share_progress_percent_and_bytes, percent, uploaded, total)
    }

    private fun totalUploadBytes(): Long {
        return sharedFiles.sumOf { file -> file.sizeBytes?.coerceAtLeast(0L) ?: 0L }
    }

    private fun WindowsUploadClient.UploadResult.toUiItem(): FileTransferUiItem {
        val transferredBytes = when (status) {
            WindowsUploadClient.UploadStatus.SUCCESS -> file.sizeBytes?.coerceAtLeast(0L) ?: currentTransferredBytes(file)
            WindowsUploadClient.UploadStatus.FAILED,
            WindowsUploadClient.UploadStatus.CANCELED,
            -> currentTransferredBytes(file)
        }
        return when (status) {
            WindowsUploadClient.UploadStatus.SUCCESS -> FileTransferUiItem(
                file = file,
                state = FileTransferState.SUCCESS,
                message = getString(R.string.share_file_status_success),
                transferredBytes = transferredBytes,
                totalBytes = file.sizeBytes,
            )

            WindowsUploadClient.UploadStatus.FAILED -> FileTransferUiItem(
                file = file,
                state = FileTransferState.FAILED,
                message = getString(
                    R.string.share_file_status_failed,
                    errorMessage.orEmpty(),
                ),
                transferredBytes = transferredBytes,
                totalBytes = file.sizeBytes,
            )

            WindowsUploadClient.UploadStatus.CANCELED -> FileTransferUiItem(
                file = file,
                state = FileTransferState.CANCELED,
                message = getString(R.string.share_file_status_canceled),
                transferredBytes = transferredBytes,
                totalBytes = file.sizeBytes,
            )
        }
    }

    private fun currentTransferredBytes(file: SharedFileReader.SharedFile): Long {
        return fileItems[file]?.transferredBytes ?: 0L
    }

    private fun requestUploadCancellation() {
        if (!isUploadInProgress || cancelRequested) {
            return
        }
        cancelRequested = true
        detailText.text = getString(R.string.share_status_cancel_pending)
        closeButton.isEnabled = false
        closeButton.setText(R.string.share_action_stopping)
        Toast.makeText(this, getString(R.string.share_status_cancel_pending), Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val MAX_PROGRESS = 100
    }
}
