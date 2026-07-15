package com.rokkystudio.dropme

import android.content.Context
import java.io.IOException

/**
 * Описывает ошибки сценариев DROPME, которые показываются пользователю.
 */
sealed class AppError {
    data object NoWifiNetwork : AppError()

    data object LocalNetworkBlocked : AppError()

    data object ServerNotFound : AppError()

    data object MultipleServersNeedSelection : AppError()

    data class UploadFailed(
        val fileName: String? = null,
        val reason: String? = null,
    ) : AppError()

    data class WebDavStartFailed(
        val reason: String? = null,
    ) : AppError()

    data object StoragePermissionMissing : AppError()

    data class UnsupportedStorageOperation(
        val reason: String? = null,
    ) : AppError()

    data class WindowsRejectedConnection(
        val reason: String? = null,
    ) : AppError()

    data class UnknownError(
        val reason: String? = null,
    ) : AppError()

    /**
     * Возвращает текст ошибки для отображения в UI.
     */
    fun toUserMessage(context: Context): String {
        return when (this) {
            NoWifiNetwork -> context.getString(R.string.share_error_wifi_only)
            LocalNetworkBlocked -> context.getString(R.string.share_error_local_network_blocked)
            ServerNotFound -> context.getString(R.string.share_error_server_not_found)
            MultipleServersNeedSelection -> context.getString(R.string.share_status_select_server)
            is UploadFailed -> reason ?: context.getString(R.string.share_status_error_title)
            is WebDavStartFailed -> reason ?: context.getString(R.string.share_status_error_title)
            StoragePermissionMissing -> context.getString(R.string.share_status_error_title)
            is UnsupportedStorageOperation -> reason ?: context.getString(R.string.share_status_error_title)
            is WindowsRejectedConnection -> reason ?: context.getString(R.string.share_status_error_title)
            is UnknownError -> reason ?: context.getString(R.string.share_status_error_title)
        }
    }
}

/**
 * Переносит типизированную ошибку DROPME через исключения.
 */
class AppException(
    val error: AppError,
    cause: Throwable? = null,
) : IOException(error.toString(), cause)

/**
 * Создаёт исключение из ошибки DROPME.
 */
fun AppError.asAppException(cause: Throwable? = null): AppException {
    return AppException(this, cause)
}

/**
 * Ищет типизированную ошибку DROPME в цепочке причин.
 */
fun Throwable.findAppError(): AppError? {
    if (this is AppException) {
        return error
    }
    return cause?.findAppError()
}

/**
 * Преобразует системную ошибку в пользовательскую ошибку DROPME.
 */
fun Throwable.toAppError(default: AppError): AppError {
    return findAppError()
        ?: if (isLocalNetworkBlockedFailure()) {
            AppError.LocalNetworkBlocked
        } else {
            default
        }
}

/**
 * Определяет блокировку локального подключения со стороны VPN или сетевой политики.
 */
fun Throwable.isLocalNetworkBlockedFailure(): Boolean {
    if (this is SecurityException) {
        return true
    }
    val messageText = buildString {
        append(message.orEmpty())
        val causeMessage = cause?.message.orEmpty()
        if (causeMessage.isNotBlank()) {
            append(' ')
            append(causeMessage)
        }
    }.lowercase()
    return "eacces" in messageText ||
        "eperm" in messageText ||
        "permission denied" in messageText ||
        "operation not permitted" in messageText ||
        "socket access denied" in messageText
}


