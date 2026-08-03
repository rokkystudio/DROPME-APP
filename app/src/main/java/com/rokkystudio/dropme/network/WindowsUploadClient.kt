package com.rokkystudio.dropme.network

import android.util.Log
import com.rokkystudio.dropme.AppError
import com.rokkystudio.dropme.asAppException
import com.rokkystudio.dropme.storage.SharedFileReader
import com.rokkystudio.dropme.toAppError
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Загружает файлы в Windows DROPME Server через upload endpoint.
 */
class WindowsUploadClient(
    private val sharedFileReader: SharedFileReader,
) {
    enum class UploadStatus {
        SUCCESS,
        FAILED,
        CANCELED,
    }

    data class UploadProgress(
        val file: SharedFileReader.SharedFile,
        val fileBytesUploaded: Long,
        val fileSizeBytes: Long,
        val totalBytesUploaded: Long,
        val totalBytesToUpload: Long,
    )

    data class UploadResult(
        val file: SharedFileReader.SharedFile,
        val status: UploadStatus,
        val errorMessage: String? = null,
    ) {
        val isSuccess: Boolean
            get() = status == UploadStatus.SUCCESS
    }

    /**
     * Отправляет набор файлов на выбранный Windows-сервер.
     */
    fun uploadFiles(
        wifiInfo: WifiNetworkProvider.WifiNetworkInfo,
        server: WindowsServer,
        files: List<SharedFileReader.SharedFile>,
        onProgress: ((UploadProgress) -> Unit)? = null,
        onFileCompleted: ((UploadResult) -> Unit)? = null,
        shouldCancel: (() -> Boolean)? = null,
    ): List<UploadResult> {
        val client = OkHttpClient.Builder()
            .socketFactory(WifiBoundSocketFactory(wifiInfo.network))
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val results = mutableListOf<UploadResult>()
        val totalBytesToUpload = files.sumOf { file -> file.sizeBytes?.coerceAtLeast(0L) ?: 0L }
        var completedBytesUploaded = 0L

        try {
            for ((index, file) in files.withIndex()) {
                if (shouldCancel?.invoke() == true) {
                    results += files.subList(index, files.size).map { pendingFile ->
                        UploadResult(
                            file = pendingFile,
                            status = UploadStatus.CANCELED,
                        )
                    }
                    break
                }

                val fileSizeBytes = file.sizeBytes?.coerceAtLeast(0L) ?: 0L
                var fileBytesUploaded = 0L
                val result = runCatching {
                    uploadSingleFile(
                        client = client,
                        server = server,
                        file = file,
                        onProgress = { uploadedBytes, totalBytes ->
                            fileBytesUploaded = uploadedBytes.coerceAtLeast(0L)
                            onProgress?.invoke(
                                UploadProgress(
                                    file = file,
                                    fileBytesUploaded = fileBytesUploaded,
                                    fileSizeBytes = totalBytes.coerceAtLeast(fileSizeBytes),
                                    totalBytesUploaded = completedBytesUploaded + fileBytesUploaded,
                                    totalBytesToUpload = totalBytesToUpload,
                                ),
                            )
                        },
                    )
                    UploadResult(file = file, status = UploadStatus.SUCCESS)
                }.getOrElse { throwable ->
                    val error = throwable.toAppError(
                        AppError.UploadFailed(file.displayName, "Не удалось отправить файл ${file.displayName}"),
                    )
                    UploadResult(
                        file = file,
                        status = UploadStatus.FAILED,
                        errorMessage = error.toUserMessage(sharedFileReader.appContext),
                    )
                }
                results += result
                completedBytesUploaded += fileBytesUploaded
                onFileCompleted?.invoke(result)
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }

        return results
    }

    /**
     * Отправляет один файл на upload endpoint Windows-сервера.
     */
    private fun uploadSingleFile(
        client: OkHttpClient,
        server: WindowsServer,
        file: SharedFileReader.SharedFile,
        onProgress: ((uploadedBytes: Long, totalBytes: Long) -> Unit)? = null,
    ) {
        Log.d(LOG_TAG, "Share upload: ${file.displayName} -> ${server.host}:${server.tcpPort}")
        val encodedName = URLEncoder.encode(file.displayName, StandardCharsets.UTF_8.name())
        try {
            for (basePath in WindowsServerApi.basePaths) {
                val request = Request.Builder()
                    .url(WindowsServerApi.buildUrl(server.host, server.tcpPort, basePath, "/upload?name=$encodedName"))
                    .put(SharedFileRequestBody(sharedFileReader, file, onProgress))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.code == 404) {
                        return@use
                    }
                    if (!response.isSuccessful) {
                        val reason = response.body?.string().orEmpty().trim()
                        val message = buildString {
                            append("Ошибка загрузки файла ${file.displayName}: HTTP ${response.code}")
                            if (reason.isNotBlank()) {
                                append(". ")
                                append(reason)
                            }
                        }
                        throw AppError.UploadFailed(file.displayName, message).asAppException()
                    }
                    return
                }
            }
            throw AppError.UploadFailed(
                file.displayName,
                "Ошибка загрузки файла ${file.displayName}: сервер не поддерживает upload endpoint",
            ).asAppException()
        } catch (throwable: Throwable) {
            val error = throwable.toAppError(
                AppError.UploadFailed(file.displayName, "Не удалось отправить файл ${file.displayName}"),
            )
            throw error.asAppException(throwable)
        }
    }

    /**
     * Формирует HTTP body, читающий содержимое файла через ContentResolver.
     */
    private class SharedFileRequestBody(
        private val sharedFileReader: SharedFileReader,
        private val file: SharedFileReader.SharedFile,
        private val onProgress: ((uploadedBytes: Long, totalBytes: Long) -> Unit)? = null,
    ) : RequestBody() {
        override fun contentType() = OCTET_STREAM

        override fun contentLength(): Long = file.sizeBytes ?: -1L

        override fun writeTo(sink: BufferedSink) {
            val totalBytes = contentLength().coerceAtLeast(0L)
            var uploadedBytes = 0L
            sharedFileReader.openInputStream(file).use { inputStream ->
                val buffer = ByteArray(UPLOAD_BUFFER_SIZE_BYTES)
                while (true) {
                    val readBytes = inputStream.read(buffer)
                    if (readBytes < 0) {
                        break
                    }
                    sink.write(buffer, 0, readBytes)
                    uploadedBytes += readBytes
                    onProgress?.invoke(uploadedBytes, totalBytes)
                }
                if (uploadedBytes == 0L) {
                    onProgress?.invoke(0L, totalBytes)
                }
            }
        }
    }

    private companion object {
        const val LOG_TAG = "DROPME"
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 60L
        const val WRITE_TIMEOUT_SECONDS = 60L
        const val UPLOAD_BUFFER_SIZE_BYTES = 64 * 1024
        val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}
