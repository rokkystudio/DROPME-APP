package com.rokkystudio.dropme

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.format.Formatter
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import badfox.logic.neoui.NeoIconActionView
import badfox.logic.neoui.NeoThemeMode
import badfox.logic.neoui.NeoTopBarView
import badfox.logic.neoui.NeoUiSettings
import com.rokkystudio.dropme.storage.StorageRootEntry
import com.rokkystudio.dropme.storage.StorageRootsRepository
import java.io.File

/**
 * Показывает файловую систему текущего Android-устройства напрямую, без сетевой DROPME-сессии.
 *
 * Доступ к локальным файлам не зависит от списка опубликованных хранилищ. На Android 11 и новее
 * экран использует системное разрешение на управление всеми файлами.
 */
class LocalFilesActivity : AppCompatActivity() {
    private lateinit var neoTopBar: NeoTopBarView
    private lateinit var deviceInfoText: TextView
    private lateinit var pathText: TextView
    private lateinit var backButton: NeoIconActionView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var filesContainer: LinearLayout
    private lateinit var storageRootsRepository: StorageRootsRepository

    private var currentStorageRoot: StorageRootEntry? = null
    private var currentDirectory: File? = null

    private val manageAllFilesAccessLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (hasLocalFileAccess()) {
                openRoot()
            } else {
                showStoragePermissionRequiredDialog()
            }
        }

    private val readStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                openRoot()
            } else {
                showStoragePermissionRequiredDialog()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_files)

        storageRootsRepository = StorageRootsRepository(applicationContext)
        bindViews()
        bindTopBar()
        deviceInfoText.text = getString(R.string.local_files_device_info)

        backButton.setOnClickListener {
            navigateBack()
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!navigateBack()) {
                        finish()
                    }
                }
            },
        )

        ensureLocalFileAccess()
    }

    private fun bindViews() {
        neoTopBar = findViewById(R.id.neoTopBar)
        deviceInfoText = findViewById(R.id.remoteDeviceInfoText)
        pathText = findViewById(R.id.remotePathText)
        backButton = findViewById(R.id.remoteUpButton)
        progressBar = findViewById(R.id.remoteProgressBar)
        emptyText = findViewById(R.id.remoteEmptyText)
        filesContainer = findViewById(R.id.remoteFilesContainer)
    }

    private fun bindTopBar() {
        neoTopBar.setAppInfo(
            title = getString(R.string.app_name),
            subtitle = getString(R.string.main_header_subtitle),
            version = BuildConfig.VERSION_NAME,
            iconResId = R.mipmap.ic_launcher,
        )
        NeoUiSettings(
            context = this,
            preferencesName = NEO_UI_PREFERENCES_NAME,
            defaultTheme = NeoThemeMode.DARK,
            defaultLanguageTag = null,
        ).bindTopBar(neoTopBar)
    }

    /**
     * Проверяет системное разрешение на чтение локальной файловой системы и запускает запрос права.
     */
    private fun ensureLocalFileAccess() {
        if (hasLocalFileAccess()) {
            openRoot()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.local_files_permission_title)
            .setMessage(R.string.local_files_permission_message)
            .setPositiveButton(R.string.local_files_permission_grant) { _, _ ->
                requestLocalFileAccess()
            }
            .setNegativeButton(R.string.remote_connection_close) { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Возвращает true, когда Android разрешает приложению просматривать локальные пользовательские файлы.
     */
    private fun hasLocalFileAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Открывает соответствующий версии Android системный запрос файлового доступа.
     */
    private fun requestLocalFileAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manageAllFilesAccessLauncher.launch(storageRootsRepository.buildManageAllFilesAccessIntent())
        } else {
            readStoragePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /**
     * Показывает, что без системного файлового разрешения локальный браузер не может открыть файлы.
     */
    private fun showStoragePermissionRequiredDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.local_files_permission_title)
            .setMessage(R.string.local_files_permission_required)
            .setPositiveButton(R.string.local_files_permission_grant) { _, _ ->
                requestLocalFileAccess()
            }
            .setNegativeButton(R.string.remote_connection_close) { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun openRoot() {
        showStorageRoots()
    }

    /**
     * Показывает все физически доступные локальные хранилища независимо от их публикации через DROPME.
     */
    private fun showStorageRoots() {
        currentStorageRoot = null
        currentDirectory = null
        progressBar.visibility = View.GONE
        filesContainer.removeAllViews()
        pathText.text = getString(R.string.local_files_storages)
        backButton.isEnabled = false

        val roots = storageRootsRepository.listRoots()
            .mapNotNull { root ->
                val path = root.directPath ?: return@mapNotNull null
                val directory = File(path)
                if (!directory.isDirectory) {
                    return@mapNotNull null
                }
                root to directory
            }

        emptyText.visibility = if (roots.isEmpty()) View.VISIBLE else View.GONE
        roots.forEach { (root, directory) ->
            filesContainer.addView(createStorageRootRow(root, directory))
        }
    }

    private fun createStorageRootRow(root: StorageRootEntry, directory: File): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = getDrawable(R.drawable.bg_button)
            isClickable = true
            isFocusable = true

            addView(TextView(this@LocalFilesActivity).apply {
                text = root.displayName
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
            })
            addView(TextView(this@LocalFilesActivity).apply {
                text = directory.absolutePath
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11f
            })

            setOnClickListener {
                currentStorageRoot = root
                openDirectory(directory)
            }
        }
    }

    /**
     * Отображает содержимое указанного каталога локальной файловой системы.
     */
    private fun openDirectory(directory: File) {
        currentDirectory = directory
        progressBar.visibility = View.GONE
        filesContainer.removeAllViews()

        val rootDirectory = currentStorageRoot?.directPath?.let(::File)
            ?: throw IllegalStateException("Local storage root is not selected")
        val relativePath = directory.relativeToOrNull(rootDirectory)?.path.orEmpty()
        pathText.text = if (relativePath.isBlank()) {
            currentStorageRoot?.displayName.orEmpty()
        } else {
            "${currentStorageRoot?.displayName.orEmpty()}/$relativePath"
        }
        backButton.isEnabled = true

        val entries = directory.listFiles()
            .orEmpty()
            .filter { it.exists() }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))

        emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        entries.forEach { entry ->
            filesContainer.addView(createEntryRow(entry))
        }
    }

    private fun createEntryRow(entry: File): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = getDrawable(R.drawable.bg_button)
            isClickable = entry.isDirectory
            isFocusable = entry.isDirectory

            addView(TextView(this@LocalFilesActivity).apply {
                text = if (entry.isDirectory) "📁 ${entry.name}" else entry.name
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
            })
            addView(TextView(this@LocalFilesActivity).apply {
                text = if (entry.isDirectory) {
                    getString(R.string.remote_files_folder)
                } else {
                    Formatter.formatShortFileSize(this@LocalFilesActivity, entry.length())
                }
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11f
            })

            if (entry.isDirectory) {
                setOnClickListener {
                    openDirectory(entry)
                }
            }
        }
    }

    /**
     * Переходит в родительский каталог, а из корня выбранного хранилища возвращается к списку хранилищ.
     */
    private fun navigateBack(): Boolean {
        val directory = currentDirectory ?: return false
        val rootDirectory = currentStorageRoot?.directPath?.let(::File) ?: return false
        if (directory == rootDirectory) {
            showStorageRoots()
            return true
        }
        val parent = directory.parentFile ?: return false
        if (parent.absolutePath.length < rootDirectory.absolutePath.length) {
            showStorageRoots()
            return true
        }
        openDirectory(parent)
        return true
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val NEO_UI_PREFERENCES_NAME = "dropme_ui"

        fun createIntent(context: Context): Intent {
            return Intent(context, LocalFilesActivity::class.java)
        }
    }
}
