package com.rokkystudio.dropme.network

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.rokkystudio.dropme.AppError
import com.rokkystudio.dropme.asAppException
import com.rokkystudio.dropme.device.DeviceIdentityStore
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Читает файловый backend удалённого DROPME-устройства для встроенного Android file browser.
 */
class RemoteFileClient(
    context: Context,
) {
    data class Entry(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
        val writable: Boolean,
    )

    private val appContext = context.applicationContext
    private val identityStore = DeviceIdentityStore(appContext)

    /**
     * Запрашивает у удалённого устройства разрешение на файловую сессию.
     */
    fun authorize(
        wifiInfo: WifiNetworkProvider.WifiNetworkInfo,
        device: WindowsServer,
    ) {
        val client = createClient(wifiInfo)
        try {
            val identity = identityStore.getIdentity()
            val url = HttpUrl.Builder()
                .scheme("http")
                .host(device.host)
                .port(device.tcpPort)
                .addPathSegment("dropme")
                .addPathSegment("authorize")
                .addQueryParameter("purpose", "files")
                .build()
            val request = Request.Builder()
                .url(url)
                .header(HEADER_DEVICE_ID, identity.id)
                .header(HEADER_DEVICE_NAME, identity.name)
                .post(ByteArray(0).toRequestBody(null))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw AppError.UnknownError(
                        "Удалённое устройство не разрешило доступ: HTTP ${response.code}",
                    ).asAppException()
                }
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    fun list(
        wifiInfo: WifiNetworkProvider.WifiNetworkInfo,
        device: WindowsServer,
        path: String,
    ): List<Entry> {
        val filePort = device.filePort
            ?: throw AppError.UnknownError("Удалённое устройство не предоставляет файловый доступ").asAppException()
        val client = createClient(wifiInfo)
        try {
            val url = HttpUrl.Builder()
                .scheme("http")
                .host(device.host)
                .port(filePort)
                .addPathSegment(".dropmefs")
                .addPathSegment("list")
                .addQueryParameter("path", path)
                .build()
            val identity = identityStore.getIdentity()
            val request = Request.Builder()
                .url(url)
                .header(HEADER_DEVICE_ID, identity.id)
                .header(HEADER_DEVICE_NAME, identity.name)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw AppError.UnknownError("Ошибка чтения файлов: HTTP ${response.code}").asAppException()
                }
                return parseList(response.body?.string().orEmpty())
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    /**
     * Возвращает локальный кэш небольшого preview. Структура каталогов при этом не кэшируется.
     */
    fun preview(
        wifiInfo: WifiNetworkProvider.WifiNetworkInfo,
        device: WindowsServer,
        remotePath: String,
        lastModified: Long,
        requestedSize: Int = PREVIEW_SIZE,
    ): File? {
        val filePort = device.filePort ?: return null
        val normalizedSize = requestedSize.coerceIn(64, 512)
        val cacheDirectory = File(
            appContext.cacheDir,
            "dropme-previews/${device.deviceId}",
        ).apply { mkdirs() }
        val cacheKey = sha256("$remotePath\n$lastModified\n$normalizedSize")
        val target = File(cacheDirectory, "$cacheKey.jpg")
        if (target.isFile && target.length() > 0L) {
            return target
        }

        val client = createClient(wifiInfo)
        return try {
            val url = HttpUrl.Builder()
                .scheme("http")
                .host(device.host)
                .port(filePort)
                .addPathSegment(".dropmefs")
                .addPathSegment("preview")
                .addQueryParameter("path", remotePath)
                .addQueryParameter("size", normalizedSize.toString())
                .build()
            val identity = identityStore.getIdentity()
            val request = Request.Builder()
                .url(url)
                .header(HEADER_DEVICE_ID, identity.id)
                .header(HEADER_DEVICE_NAME, identity.name)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return null
                }
                val temporary = File(cacheDirectory, "$cacheKey.tmp")
                response.body?.byteStream()?.use { input ->
                    temporary.outputStream().buffered().use(input::copyTo)
                } ?: return null
                if (temporary.length() == 0L) {
                    temporary.delete()
                    return null
                }
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
                prunePreviewCache(cacheDirectory)
                target
            }
        } catch (_: Throwable) {
            null
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
    fun download(
        wifiInfo: WifiNetworkProvider.WifiNetworkInfo,
        device: WindowsServer,
        remotePath: String,
        fileName: String,
    ): File {
        val filePort = device.filePort
            ?: throw AppError.UnknownError("Удалённое устройство не предоставляет файловый доступ").asAppException()
        val client = createClient(wifiInfo)
        try {
            val builder = HttpUrl.Builder()
                .scheme("http")
                .host(device.host)
                .port(filePort)
            remotePath.trim('/').split('/').filter { it.isNotBlank() }.forEach(builder::addPathSegment)
            val identity = identityStore.getIdentity()
            val request = Request.Builder()
                .url(builder.build())
                .header(HEADER_DEVICE_ID, identity.id)
                .header(HEADER_DEVICE_NAME, identity.name)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw AppError.UnknownError("Ошибка загрузки файла: HTTP ${response.code}").asAppException()
                }
                val targetDirectory = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    DOWNLOAD_DIRECTORY,
                )
                targetDirectory.mkdirs()
                val target = uniqueTarget(targetDirectory, fileName)
                response.body?.byteStream()?.use { input ->
                    target.outputStream().buffered().use { output ->
                        input.copyTo(output)
                    }
                } ?: throw AppError.UnknownError("Удалённое устройство вернуло пустой файл").asAppException()
                return target
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun createClient(wifiInfo: WifiNetworkProvider.WifiNetworkInfo): OkHttpClient {
        return OkHttpClient.Builder()
            .socketFactory(WifiBoundSocketFactory(wifiInfo.network))
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private fun parseList(body: String): List<Entry> {
        if (!body.lineSequence().any { it == "ok=1" }) {
            throw AppError.UnknownError("Удалённое устройство вернуло некорректный список файлов").asAppException()
        }
        return body.lineSequence()
            .filter { it.startsWith("entry=") }
            .mapNotNull { line ->
                val columns = line.removePrefix("entry=").split('\t')
                if (columns.size < 5) {
                    return@mapNotNull null
                }
                Entry(
                    name = Uri.decode(columns[0]),
                    isDirectory = columns[1] == "1",
                    size = columns[2].toLongOrNull() ?: 0L,
                    lastModified = columns[3].toLongOrNull() ?: 0L,
                    writable = columns[4] == "1",
                )
            }
            .sortedWith(compareBy<Entry>({ !it.isDirectory }, { it.name.lowercase() }))
            .toList()
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun prunePreviewCache(directory: File) {
        val files = directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "jpg" }
            .sortedByDescending { it.lastModified() }
        var totalBytes = files.sumOf { it.length() }
        if (totalBytes <= MAX_PREVIEW_CACHE_BYTES) {
            return
        }
        files.asReversed().forEach { file ->
            if (totalBytes <= MAX_PREVIEW_CACHE_BYTES) {
                return
            }
            val length = file.length()
            if (file.delete()) {
                totalBytes -= length
            }
        }
    }
    private fun uniqueTarget(directory: File, fileName: String): File {
        val safeName = fileName.replace('/', '_').replace('\\', '_').ifBlank { "download" }
        var target = File(directory, safeName)
        if (!target.exists()) {
            return target
        }

        val dot = safeName.lastIndexOf('.')
        val stem = if (dot > 0) safeName.substring(0, dot) else safeName
        val extension = if (dot > 0) safeName.substring(dot) else ""
        var index = 2
        while (target.exists()) {
            target = File(directory, "$stem ($index)$extension")
            index++
        }
        return target
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 60L
        const val DOWNLOAD_DIRECTORY = "DROPME"
        const val PREVIEW_SIZE = 256
        const val MAX_PREVIEW_CACHE_BYTES = 128L * 1024L * 1024L
        const val HEADER_DEVICE_ID = "X-DROPME-Device-ID"
        const val HEADER_DEVICE_NAME = "X-DROPME-Device-Name"
    }
}
