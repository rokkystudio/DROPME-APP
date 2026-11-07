package com.rokkystudio.dropme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import badfox.logic.neoui.NeoButtonView
import badfox.logic.neoui.NeoThemeMode
import badfox.logic.neoui.NeoTopBarView
import badfox.logic.neoui.NeoUiSettings
import badfox.logic.neoui.applyNeoSystemBarInsets
import com.rokkystudio.dropme.network.IncomingTransferCoordinator
import java.io.File

/**
 * Показывает состояние входящего файла, открытого из Android-уведомления.
 *
 * Во время приёма отображает прогресс и позволяет отменить HTTP-сессию.
 * После сохранения показывает результат и открывает папку с полученным файлом.
 */
class IncomingTransferActivity : AppCompatActivity() {
    private lateinit var nameText: TextView
    private lateinit var senderText: TextView
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var cancelButton: NeoButtonView
    private lateinit var filesButton: NeoButtonView

    private var transferId = -1

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(IncomingTransferCoordinator.EXTRA_TRANSFER_ID, -1) == transferId) {
                renderTransfer()
            }
        }
    }

    /**
     * Создаёт экран выбранной передачи и привязывает действия отмены и открытия папки.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_incoming_transfer)
        applyNeoSystemBarInsets(findViewById(R.id.incomingTransferRoot))

        val topBar = findViewById<NeoTopBarView>(R.id.neoTopBar)
        topBar.setAppInfo(
            title = getString(R.string.app_name),
            subtitle = getString(R.string.incoming_transfer_screen_title),
            version = BuildConfig.VERSION_NAME,
            iconResId = R.mipmap.ic_launcher,
        )
        NeoUiSettings(
            context = this,
            preferencesName = NEO_UI_PREFERENCES_NAME,
            defaultTheme = NeoThemeMode.DARK,
            defaultLanguageTag = null,
        ).bindTopBar(topBar)

        nameText = findViewById(R.id.incomingTransferName)
        senderText = findViewById(R.id.incomingTransferSender)
        statusText = findViewById(R.id.incomingTransferStatus)
        detailText = findViewById(R.id.incomingTransferDetail)
        progressBar = findViewById(R.id.incomingTransferProgress)
        cancelButton = findViewById(R.id.incomingTransferCancel)
        filesButton = findViewById(R.id.incomingTransferFiles)

        cancelButton.setOnClickListener {
            IncomingTransferCoordinator.cancel(this, transferId)
            renderTransfer()
        }
        filesButton.setOnClickListener {
            val path = IncomingTransferCoordinator.get(transferId)?.savedPath ?: return@setOnClickListener
            val folder = File(path).parentFile ?: return@setOnClickListener
            startActivity(
                Intent(this, LocalFilesActivity::class.java)
                    .putExtra(LocalFilesActivity.EXTRA_OPEN_DIRECTORY, folder.absolutePath),
            )
        }

        transferId = intent.getIntExtra(IncomingTransferCoordinator.EXTRA_TRANSFER_ID, -1)
        renderTransfer()
    }

    /**
     * Подписывает экран на события передачи и отображает актуальное состояние.
     */
    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            updateReceiver,
            IntentFilter(IncomingTransferCoordinator.ACTION_TRANSFER_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        renderTransfer()
    }

    /**
     * Завершает подписку на обновления при уходе экрана с переднего плана.
     */
    override fun onStop() {
        unregisterReceiver(updateReceiver)
        super.onStop()
    }

    /**
     * Переключает экран на передачу, выбранную в новом уведомлении.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        transferId = intent.getIntExtra(IncomingTransferCoordinator.EXTRA_TRANSFER_ID, -1)
        renderTransfer()
    }

    /**
     * Отображает прогресс, проверку целостности или результат выбранного файла.
     */
    private fun renderTransfer() {
        val snapshot = IncomingTransferCoordinator.get(transferId)
        if (snapshot == null) {
            nameText.text = getString(R.string.incoming_transfer_screen_title)
            senderText.text = ""
            statusText.text = getString(R.string.incoming_transfer_unavailable)
            detailText.text = ""
            progressBar.visibility = View.GONE
            cancelButton.visibility = View.GONE
            filesButton.visibility = View.GONE
            return
        }

        nameText.text = snapshot.fileName
        senderText.text = getString(R.string.incoming_transfer_sender, snapshot.senderName)
        filesButton.visibility = if (snapshot.status == IncomingTransferCoordinator.Status.COMPLETED) {
            View.VISIBLE
        } else {
            View.GONE
        }
        cancelButton.visibility = when (snapshot.status) {
            IncomingTransferCoordinator.Status.RECEIVING,
            IncomingTransferCoordinator.Status.VERIFYING -> View.VISIBLE
            else -> View.GONE
        }

        when (snapshot.status) {
            IncomingTransferCoordinator.Status.RECEIVING -> {
                val percent = if (snapshot.totalBytes <= 0L) {
                    0
                } else {
                    ((snapshot.receivedBytes * 100L) / snapshot.totalBytes).toInt().coerceIn(0, 99)
                }
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = false
                progressBar.progress = percent
                statusText.text = getString(R.string.incoming_transfer_percent, percent)
                detailText.text = getString(
                    R.string.incoming_transfer_bytes,
                    Formatter.formatShortFileSize(this, snapshot.receivedBytes),
                    Formatter.formatShortFileSize(this, snapshot.totalBytes),
                )
            }

            IncomingTransferCoordinator.Status.VERIFYING -> {
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                statusText.text = getString(R.string.incoming_transfer_verifying)
                detailText.text = getString(R.string.incoming_transfer_bytes,
                    Formatter.formatShortFileSize(this, snapshot.receivedBytes),
                    Formatter.formatShortFileSize(this, snapshot.totalBytes),
                )
            }

            IncomingTransferCoordinator.Status.COMPLETED -> {
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = false
                progressBar.progress = 100
                statusText.text = getString(R.string.incoming_transfer_saved, snapshot.fileName)
                detailText.text = snapshot.savedPath.orEmpty()
            }

            IncomingTransferCoordinator.Status.FAILED -> {
                progressBar.visibility = View.GONE
                statusText.text = getString(R.string.incoming_transfer_failed, snapshot.fileName)
                detailText.text = ""
            }

            IncomingTransferCoordinator.Status.CANCELED -> {
                progressBar.visibility = View.GONE
                statusText.text = getString(R.string.incoming_transfer_canceled)
                detailText.text = ""
            }
        }
    }

    private companion object {
        const val NEO_UI_PREFERENCES_NAME = "dropme_ui"
    }
}
