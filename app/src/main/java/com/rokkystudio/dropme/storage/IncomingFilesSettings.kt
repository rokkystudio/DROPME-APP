package com.rokkystudio.dropme.storage

import android.content.Context

/**
 * Хранит пользовательские настройки сохранения входящих файлов.
 */
class IncomingFilesSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    /**
     * Возвращает, нужно ли группировать входящие файлы по локальной дате.
     */
    fun createDateFolders(): Boolean =
        preferences.getBoolean(KEY_CREATE_DATE_FOLDERS, false)

    /**
     * Сохраняет режим группировки входящих файлов по локальной дате.
     */
    fun setCreateDateFolders(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_CREATE_DATE_FOLDERS, enabled).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "dropme_incoming_files"
        const val KEY_CREATE_DATE_FOLDERS = "create_date_folders"
    }
}
