package com.rokkystudio.dropme.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.provider.Settings
import com.rokkystudio.dropme.R
import java.io.File
import java.security.MessageDigest
import java.util.Locale

enum class StorageRootType {
    INTERNAL,
    REMOVABLE,
    CUSTOM,
}

enum class StorageAccessState {
    READY,
    NEEDS_ALL_FILES_ACCESS,
    NEEDS_TREE_GRANT,
    UNAVAILABLE,
}

enum class StorageBackendType {
    DIRECT_FILE,
    SAF_TREE,
}

data class StorageRootEntry(
    val id: String,
    val displayName: String,
    val type: StorageRootType,
    val accessState: StorageAccessState,
    val isWritable: Boolean,
    val isEnabled: Boolean,
    val canRemove: Boolean,
    val volumeId: String? = null,
    val treeUri: Uri? = null,
    val directPath: String? = null,
)

data class PublishedStorageRoot(
    val id: String,
    val displayName: String,
    val backendType: StorageBackendType,
    val isWritable: Boolean,
    val directPath: String? = null,
    val treeUri: Uri? = null,
)

/**
 * Хранит список опубликованных путей Android.
 *
 * Системные корни внутреннего и съёмных хранилищ всегда присутствуют в списке,
 * но могут быть отключены. Пользовательские папки добавляются через SAF и могут
 * быть отключены или удалены.
 */
class StorageRootsRepository(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val storageManager = appContext.getSystemService(StorageManager::class.java)
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun listRoots(): List<StorageRootEntry> {
        val entries = mutableListOf<StorageRootEntry>()
        entries += buildInternalRoot()

        storageManager?.storageVolumes
            .orEmpty()
            .filter { !it.isPrimary && it.isRemovable }
            .forEach { volume ->
                entries += buildRemovableRoot(volume)
            }

        entries += loadCustomRoots()
        return makeDisplayNamesUnique(entries)
    }

    fun listPublishedRoots(): List<PublishedStorageRoot> {
        return listRoots()
            .filter { it.isEnabled && it.accessState == StorageAccessState.READY }
            .mapNotNull { entry ->
                when {
                    entry.directPath != null -> PublishedStorageRoot(
                        id = entry.id,
                        displayName = entry.displayName,
                        backendType = StorageBackendType.DIRECT_FILE,
                        isWritable = entry.isWritable,
                        directPath = entry.directPath,
                    )

                    entry.treeUri != null -> PublishedStorageRoot(
                        id = entry.id,
                        displayName = entry.displayName,
                        backendType = StorageBackendType.SAF_TREE,
                        isWritable = entry.isWritable,
                        treeUri = entry.treeUri,
                    )

                    else -> null
                }
            }
    }

    fun setEnabled(rootId: String, enabled: Boolean) {
        val disabled = preferences.getStringSet(KEY_DISABLED_ROOTS, emptySet()).orEmpty().toMutableSet()
        if (enabled) {
            disabled.remove(rootId)
        } else {
            disabled.add(rootId)
        }
        preferences.edit().putStringSet(KEY_DISABLED_ROOTS, disabled).apply()
    }

    fun addCustomRoot(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        appContext.contentResolver.takePersistableUriPermission(uri, flags)

        val existing = loadCustomRootRecords().toMutableList()
        if (existing.any { it.uri == uri.toString() }) {
            return
        }

        val name = queryTreeDisplayName(uri)
        existing += CustomRootRecord(
            id = "custom-${sha256(uri.toString()).take(16)}",
            displayName = name,
            uri = uri.toString(),
        )
        saveCustomRootRecords(existing)
    }

    fun removeCustomRoot(rootId: String) {
        val records = loadCustomRootRecords()
        val removed = records.firstOrNull { it.id == rootId } ?: return
        val uri = Uri.parse(removed.uri)
        runCatching {
            appContext.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        saveCustomRootRecords(records.filterNot { it.id == rootId })
        setEnabled(rootId, true)
    }

    fun hasAllFilesAccess(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }

    fun buildManageAllFilesAccessIntent(): Intent {
        val packageIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${appContext.packageName}"),
        )
        return if (packageIntent.resolveActivity(appContext.packageManager) != null) {
            packageIntent
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }

    private fun buildInternalRoot(): StorageRootEntry {
        val rootPath = Environment.getExternalStorageDirectory().absolutePath
        val accessState = if (hasAllFilesAccess()) {
            StorageAccessState.READY
        } else {
            StorageAccessState.NEEDS_ALL_FILES_ACCESS
        }
        return StorageRootEntry(
            id = "internal-primary",
            displayName = appContext.getString(R.string.storage_root_internal),
            type = StorageRootType.INTERNAL,
            accessState = accessState,
            isWritable = accessState == StorageAccessState.READY,
            isEnabled = isEnabled("internal-primary"),
            canRemove = false,
            directPath = rootPath,
        )
    }

    private fun buildRemovableRoot(volume: StorageVolume): StorageRootEntry {
        val volumeId = volume.uuid?.lowercase(Locale.US)
        val label = volume.getDescription(appContext)
            ?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.storage_root_external_fallback)
        val rootDirectory = resolveVolumeDirectory(volume)
        val allFiles = hasAllFilesAccess()
        val ready = allFiles && rootDirectory?.isDirectory == true
        val accessState = when {
            ready -> StorageAccessState.READY
            !allFiles -> StorageAccessState.NEEDS_ALL_FILES_ACCESS
            else -> StorageAccessState.UNAVAILABLE
        }
        val id = "removable-${volumeId ?: label.lowercase(Locale.US)}"
        return StorageRootEntry(
            id = id,
            displayName = label,
            type = StorageRootType.REMOVABLE,
            accessState = accessState,
            isWritable = ready && rootDirectory?.canWrite() == true,
            isEnabled = isEnabled(id),
            canRemove = false,
            volumeId = volumeId,
            directPath = rootDirectory?.absolutePath,
        )
    }

    private fun loadCustomRoots(): List<StorageRootEntry> {
        val persistedUris = appContext.contentResolver.persistedUriPermissions
            .associateBy { it.uri.toString() }
        return loadCustomRootRecords().map { record ->
            val permission = persistedUris[record.uri]
            val uri = Uri.parse(record.uri)
            val ready = permission?.isReadPermission == true
            StorageRootEntry(
                id = record.id,
                displayName = record.displayName,
                type = StorageRootType.CUSTOM,
                accessState = if (ready) StorageAccessState.READY else StorageAccessState.NEEDS_TREE_GRANT,
                isWritable = permission?.isWritePermission == true,
                isEnabled = isEnabled(record.id),
                canRemove = true,
                treeUri = uri,
            )
        }
    }

    private fun isEnabled(rootId: String): Boolean {
        return rootId !in preferences.getStringSet(KEY_DISABLED_ROOTS, emptySet()).orEmpty()
    }

    private fun loadCustomRootRecords(): List<CustomRootRecord> {
        return preferences.getStringSet(KEY_CUSTOM_ROOTS, emptySet())
            .orEmpty()
            .mapNotNull { value ->
                val parts = value.split('|', limit = 3)
                if (parts.size != 3) return@mapNotNull null
                CustomRootRecord(
                    id = Uri.decode(parts[0]),
                    displayName = Uri.decode(parts[1]),
                    uri = Uri.decode(parts[2]),
                )
            }
            .sortedBy { it.displayName.lowercase(Locale.US) }
    }

    private fun saveCustomRootRecords(records: List<CustomRootRecord>) {
        val encoded = records.mapTo(linkedSetOf()) { record ->
            "${Uri.encode(record.id)}|${Uri.encode(record.displayName)}|${Uri.encode(record.uri)}"
        }
        preferences.edit().putStringSet(KEY_CUSTOM_ROOTS, encoded).apply()
    }

    private fun queryTreeDisplayName(uri: Uri): String {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        return documentId
            ?.substringAfterLast(':')
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.storage_custom_folder)
    }

    private fun makeDisplayNamesUnique(entries: List<StorageRootEntry>): List<StorageRootEntry> {
        val seen = linkedMapOf<String, Int>()
        return entries.map { entry ->
            val baseName = entry.displayName
            val nextIndex = (seen[baseName] ?: 0) + 1
            seen[baseName] = nextIndex
            if (nextIndex == 1) entry else entry.copy(displayName = "$baseName ($nextIndex)")
        }
    }

    private fun resolveVolumeDirectory(volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume.directory?.let { directory ->
                if (directory.exists() && directory.isDirectory) {
                    return directory
                }
            }
        }

        val volumeId = volume.uuid?.lowercase(Locale.US)
        return appContext.getExternalFilesDirs(null)
            .orEmpty()
            .mapNotNull(::resolveVolumeRootFromAppExternalDir)
            .firstOrNull { candidate ->
                when {
                    !candidate.exists() || !candidate.isDirectory -> false
                    volumeId != null -> candidate.absolutePath.lowercase(Locale.US).contains(volumeId)
                    else -> Environment.isExternalStorageRemovable(candidate)
                }
            }
    }

    private fun resolveVolumeRootFromAppExternalDir(appExternalDir: File?): File? {
        var current = appExternalDir?.absoluteFile ?: return null
        while (true) {
            val parent = current.parentFile ?: return null
            if (current.name == "Android") {
                return parent.takeIf { it.exists() && it.isDirectory }
            }
            current = parent
        }
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class CustomRootRecord(
        val id: String,
        val displayName: String,
        val uri: String,
    )

    private companion object {
        const val PREFERENCES_NAME = "dropme_storage_roots"
        const val KEY_DISABLED_ROOTS = "disabled_roots"
        const val KEY_CUSTOM_ROOTS = "custom_roots"
    }
}
