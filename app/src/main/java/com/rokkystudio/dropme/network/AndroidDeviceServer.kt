package com.rokkystudio.dropme.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.rokkystudio.dropme.R
import com.rokkystudio.dropme.device.DeviceIdentityStore
import com.rokkystudio.dropme.device.DeviceTrustStore
import com.rokkystudio.dropme.device.DeviceSessionRegistry
import com.rokkystudio.dropme.device.IncomingApprovalCoordinator
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Обслуживает стабильный DROPME control endpoint Android-устройства.
 *
 * Сервер публикует identity, обрабатывает пользовательское разрешение входящих сессий
 * и принимает короткие Share-сессии в локальную папку DROPME/Incoming.
 */
class AndroidDeviceServer {
    private val running = AtomicBoolean(false)
    private val sessionAuthorizations = ConcurrentHashMap<String, Long>()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    private var clientExecutor = Executors.newCachedThreadPool()

    @Volatile
    private var identity: DeviceIdentityStore.DeviceIdentity? = null

    @Volatile
    private var filePort: Int = 0

    fun start(
        wifiInfo: WifiNetworkProvider.WifiNetworkInfo,
        identity: DeviceIdentityStore.DeviceIdentity,
        filePort: Int,
    ) {
        stop()

        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(wifiInfo.ipv4Address as Inet4Address, CONTROL_PORT))

        this.identity = identity
        this.filePort = filePort
        serverSocket = socket
        clientExecutor = Executors.newCachedThreadPool()
        sessionAuthorizations.clear()
        running.set(true)
        acceptThread = Thread(::acceptLoop, "DROPME-DeviceAccept").also { it.start() }

        Log.d(
            LOG_TAG,
            "Device server started on ${wifiInfo.ipv4Address.hostAddress}:$CONTROL_PORT id=${identity.id} filePort=$filePort",
        )
    }

    fun stop() {
        if (!running.getAndSet(false)) {
            return
        }

        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.join(1_000L)
        acceptThread = null
        clientExecutor.shutdownNow()
        sessionAuthorizations.clear()
        identity = null
        filePort = 0
    }

    private fun acceptLoop() {
        while (running.get()) {
            val client = try {
                serverSocket?.accept() ?: break
            } catch (_: SocketException) {
                if (!running.get()) break
                continue
            }
            clientExecutor.execute {
                handleClient(client)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val request = parseRequest(input) ?: run {
                writeResponse(output, 400, "Bad Request", "text/plain; charset=utf-8", ByteArray(0))
                return
            }
            val remoteIp = client.inetAddress?.hostAddress.orEmpty()

            when {
                request.method == "GET" &&
                    (request.path == "/dropme/info" || request.path == "/wifidrop/info") -> {
                    handleInfo(output)
                }

                request.method == "POST" &&
                    (request.path == "/dropme/authorize" || request.path == "/wifidrop/authorize") -> {
                    handleAuthorize(output, request, remoteIp)
                }

                request.method == "PUT" &&
                    (request.path == "/dropme/upload" || request.path == "/wifidrop/upload") -> {
                    handleUpload(output, input, request, remoteIp)
                }

                else -> {
                    writeResponse(output, 404, "Not Found", "text/plain; charset=utf-8", ByteArray(0))
                }
            }
        }
    }

    private fun handleInfo(output: BufferedOutputStream) {
        val current = identity ?: run {
            writeResponse(output, 503, "Service Unavailable", "text/plain; charset=utf-8", ByteArray(0))
            return
        }
        val body = JSONObject()
            .put("app", "DROPME")
            .put("role", "device")
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("deviceId", current.id)
            .put("deviceName", current.name)
            .put("deviceType", current.type)
            .put("tcpPort", CONTROL_PORT)
            .put("filePort", filePort.takeIf { it > 0 } ?: JSONObject.NULL)
            .put("fileAccessAvailable", filePort > 0)
            .put("shareAvailable", true)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        writeResponse(output, 200, "OK", "application/json; charset=utf-8", body)
    }

    private fun handleAuthorize(
        output: BufferedOutputStream,
        request: Request,
        remoteIp: String,
    ) {
        val requester = requester(request) ?: run {
            writeResponse(output, 400, "Bad Request", "text/plain; charset=utf-8", ByteArray(0))
            return
        }
        val purpose = request.query["purpose"]
            ?.takeIf { it == PURPOSE_SHARE || it == PURPOSE_FILES }
            ?: PURPOSE_FILES
        val allowed = IncomingApprovalCoordinator.authorize(
            context = AppContextHolder.context,
            deviceId = requester.id,
            deviceName = requester.name,
            remoteIp = remoteIp,
            purpose = purpose,
            itemName = request.query["name"],
        )
        if (!allowed) {
            writeJson(
                output,
                403,
                "Forbidden",
                JSONObject().put("accepted", false),
            )
            return
        }

        sessionAuthorizations[authorizationKey(requester.id, remoteIp, purpose)] =
            System.currentTimeMillis() + SESSION_AUTHORIZATION_MS
        if (purpose == PURPOSE_FILES) {
            DeviceSessionRegistry.grant(remoteIp, purpose)
        }
        writeJson(
            output,
            200,
            "OK",
            JSONObject()
                .put("accepted", true)
                .put("deviceId", identity?.id.orEmpty()),
        )
    }

    private fun handleUpload(
        output: BufferedOutputStream,
        input: BufferedInputStream,
        request: Request,
        remoteIp: String,
    ) {
        if (request.contentLength < 0L) {
            writeResponse(output, 411, "Length Required", "text/plain; charset=utf-8", ByteArray(0))
            return
        }

        val requester = requester(request) ?: run {
            writeResponse(output, 400, "Bad Request", "text/plain; charset=utf-8", ByteArray(0))
            return
        }
        if (!isAuthorized(requester, remoteIp, PURPOSE_SHARE, request.query["name"])) {
            writeResponse(output, 403, "Forbidden", "text/plain; charset=utf-8", ByteArray(0))
            return
        }

        val rawName = request.query["name"].orEmpty()
        val fileName = sanitizeFileName(rawName)
        if (fileName.isBlank()) {
            writeResponse(output, 400, "Bad Request", "text/plain; charset=utf-8", ByteArray(0))
            return
        }

        val incomingDirectory = File(
            Environment.getExternalStorageDirectory(),
            INCOMING_RELATIVE_PATH,
        )
        if ((!incomingDirectory.exists() && !incomingDirectory.mkdirs()) || !incomingDirectory.isDirectory) {
            writeResponse(output, 500, "Internal Server Error", "text/plain; charset=utf-8", ByteArray(0))
            return
        }

        val target = uniqueTarget(incomingDirectory, fileName)
        val temporary = File(incomingDirectory, ".${target.name}.${System.nanoTime()}.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        var remaining = request.contentLength
        var receivedBytes = 0L
        var lastProgressPercent = -1

        try {
            reportIncomingProgress(
                fileName = fileName,
                senderName = requester.name,
                receivedBytes = 0L,
                totalBytes = request.contentLength,
            )
            FileOutputStream(temporary).buffered().use { fileOutput ->
                val buffer = ByteArray(64 * 1024)
                while (remaining > 0L) {
                    val read = input.read(
                        buffer,
                        0,
                        minOf(buffer.size.toLong(), remaining).toInt(),
                    )
                    if (read < 0) {
                        throw IllegalStateException("Upload body ended early")
                    }
                    if (read == 0) {
                        continue
                    }
                    fileOutput.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    remaining -= read.toLong()
                    receivedBytes += read.toLong()
                    val progressPercent = if (request.contentLength <= 0L) {
                        100
                    } else {
                        ((receivedBytes * 100L) / request.contentLength).toInt().coerceIn(0, 100)
                    }
                    if (progressPercent != lastProgressPercent) {
                        lastProgressPercent = progressPercent
                        reportIncomingProgress(
                            fileName = fileName,
                            senderName = requester.name,
                            receivedBytes = receivedBytes,
                            totalBytes = request.contentLength,
                        )
                    }
                }
                fileOutput.flush()
            }

            val sha256 = digest.digest().joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
            val expectedSha256 = request.headers[HEADER_SHA256]
                ?.trim()
                ?.lowercase(Locale.US)
            if (expectedSha256 == null || !SHA256_PATTERN.matches(expectedSha256)) {
                temporary.delete()
                reportIncomingFailed(fileName)
                writeResponse(output, 400, "Bad Request", "text/plain; charset=utf-8", ByteArray(0))
                return
            }
            if (expectedSha256 != sha256) {
                temporary.delete()
                reportIncomingFailed(fileName)
                writeResponse(output, 422, "Unprocessable Content", "text/plain; charset=utf-8", ByteArray(0))
                return
            }

            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = false)
                temporary.delete()
            }

            val storedSha256 = computeFileSha256(target)
            if (storedSha256 != expectedSha256 || target.length() != request.contentLength) {
                target.delete()
                reportIncomingFailed(fileName)
                writeResponse(output, 500, "Internal Server Error", "text/plain; charset=utf-8", ByteArray(0))
                return
            }

            val headers = linkedMapOf(
                HEADER_LENGTH_RESPONSE to target.length().toString(),
                HEADER_SHA256_RESPONSE to storedSha256,
            )
            writeResponse(output, 201, "Created", "application/json; charset=utf-8", ByteArray(0), headers)
            reportIncomingCompleted(target)
            Log.d(
                LOG_TAG,
                "Incoming share saved: ${target.absolutePath} from ${requester.name} (${requester.id})",
            )
        } catch (throwable: Throwable) {
            temporary.delete()
            reportIncomingFailed(fileName)
            Log.w(LOG_TAG, "Incoming share failed", throwable)
            writeResponse(output, 500, "Internal Server Error", "text/plain; charset=utf-8", ByteArray(0))
        }
    }

    private fun isAuthorized(
        requester: Requester,
        remoteIp: String,
        purpose: String,
        itemName: String?,
    ): Boolean {
        val trustStore = DeviceTrustStore(AppContextHolder.context)
        when (trustStore.getDecision(requester.id, purpose)) {
            DeviceTrustStore.Decision.ALLOWED -> {
                trustStore.touch(requester.id, requester.name, remoteIp)
                return true
            }

            DeviceTrustStore.Decision.BLOCKED -> {
                trustStore.touch(requester.id, requester.name, remoteIp)
                return false
            }

            DeviceTrustStore.Decision.UNKNOWN -> Unit
        }

        val key = authorizationKey(requester.id, remoteIp, purpose)
        val expiry = sessionAuthorizations[key] ?: 0L
        if (expiry >= System.currentTimeMillis()) {
            return true
        }
        sessionAuthorizations.remove(key)

        val allowed = IncomingApprovalCoordinator.authorize(
            context = AppContextHolder.context,
            deviceId = requester.id,
            deviceName = requester.name,
            remoteIp = remoteIp,
            purpose = purpose,
            itemName = itemName,
        )
        if (allowed) {
            sessionAuthorizations[key] = System.currentTimeMillis() + SESSION_AUTHORIZATION_MS
        }
        return allowed
    }

    private fun requester(request: Request): Requester? {
        val id = request.headers[HEADER_DEVICE_ID].orEmpty().trim().lowercase(Locale.US)
        val name = request.headers[HEADER_DEVICE_NAME].orEmpty().trim()
        if (!id.matches(DEVICE_ID_PATTERN) || name.isBlank()) {
            return null
        }
        return Requester(id = id, name = name)
    }

    private fun parseRequest(input: BufferedInputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) {
            return null
        }

        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            headers[line.substring(0, separator).trim().lowercase(Locale.US)] =
                line.substring(separator + 1).trim()
        }

        val target = parts[1]
        val encodedQuery = target.substringAfter('?', "")
        val query = if (encodedQuery.isBlank()) {
            emptyMap()
        } else {
            encodedQuery.split('&')
                .mapNotNull { pair ->
                    val separator = pair.indexOf('=')
                    if (separator < 0) {
                        Uri.decode(pair) to ""
                    } else {
                        Uri.decode(pair.substring(0, separator)) to Uri.decode(pair.substring(separator + 1))
                    }
                }
                .toMap()
        }

        return Request(
            method = parts[0].uppercase(Locale.US),
            path = target.substringBefore('?'),
            query = query,
            headers = headers,
            contentLength = headers["content-length"]?.toLongOrNull() ?: -1L,
        )
    }

    private fun writeJson(
        output: BufferedOutputStream,
        statusCode: Int,
        reason: String,
        json: JSONObject,
    ) {
        writeResponse(
            output,
            statusCode,
            reason,
            "application/json; charset=utf-8",
            json.toString().toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun writeResponse(
        output: BufferedOutputStream,
        statusCode: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val headers = buildString {
            append("HTTP/1.1 ").append(statusCode).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            extraHeaders.forEach { (key, value) ->
                append(key).append(": ").append(value).append("\r\n")
            }
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (true) {
            val value = input.read()
            if (value < 0) {
                return if (bytes.isEmpty()) null else ByteArray(bytes.size) { bytes[it] }.toString(StandardCharsets.UTF_8)
            }
            if (value == '\n'.code) {
                break
            }
            if (value != '\r'.code) {
                bytes += value.toByte()
            }
        }
        return ByteArray(bytes.size) { bytes[it] }.toString(StandardCharsets.UTF_8)
    }

    private fun sanitizeFileName(value: String): String {
        return Uri.decode(value)
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
            .replace(Regex("[\\u0000-\\u001f]"), "_")
    }

    /**
     * Вычисляет SHA-256 уже сохранённого файла для финальной проверки целостности.
     */
    /** Показывает прогресс входящего файла в системном уведомлении. */
    private fun reportIncomingProgress(
        fileName: String,
        senderName: String,
        receivedBytes: Long,
        totalBytes: Long,
    ) {
        val context = AppContextHolder.context
        createIncomingTransferChannel(context)
        val percent = if (totalBytes <= 0L) {
            0
        } else {
            ((receivedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
        }
        val notification = NotificationCompat.Builder(context, INCOMING_TRANSFER_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.incoming_transfer_title, fileName))
            .setContentText(
                context.getString(
                    R.string.incoming_transfer_progress,
                    senderName,
                    percent,
                    Formatter.formatShortFileSize(context, receivedBytes.coerceAtLeast(0L)),
                    Formatter.formatShortFileSize(context, totalBytes.coerceAtLeast(0L)),
                ),
            )
            .setProgress(100, percent, totalBytes <= 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(INCOMING_TRANSFER_NOTIFICATION_ID, notification)
    }

    /**
     * Сообщает об успешном сохранении уже проверенного SHA-256 файла и публикует его
     * в системном индексе общего хранилища, чтобы файловые менеджеры увидели файл сразу.
     */
    private fun reportIncomingCompleted(file: File) {
        val context = AppContextHolder.context
        createIncomingTransferChannel(context)
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            null,
            null,
        )
        val title = context.getString(R.string.incoming_transfer_saved, file.name)
        val folder = file.parentFile?.absolutePath.orEmpty()
        val detail = context.getString(R.string.incoming_transfer_saved_detail, folder)
        val notification = NotificationCompat.Builder(context, INCOMING_TRANSFER_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(INCOMING_TRANSFER_NOTIFICATION_ID, notification)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "$title\n$detail", Toast.LENGTH_LONG).show()
        }
    }

    private fun reportIncomingFailed(fileName: String) {
        val context = AppContextHolder.context
        createIncomingTransferChannel(context)
        val text = context.getString(R.string.incoming_transfer_failed, fileName)
        val notification = NotificationCompat.Builder(context, INCOMING_TRANSFER_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(INCOMING_TRANSFER_NOTIFICATION_ID, notification)
    }

    private fun createIncomingTransferChannel(context: android.content.Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(INCOMING_TRANSFER_CHANNEL_ID) != null) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(
                INCOMING_TRANSFER_CHANNEL_ID,
                context.getString(R.string.incoming_transfer_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.incoming_transfer_channel_description)
            },
        )
    }

    private fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                if (read > 0) {
                    digest.update(buffer, 0, read)
                }
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun uniqueTarget(directory: File, fileName: String): File {
        var target = File(directory, fileName)
        if (!target.exists()) {
            return target
        }

        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val extension = if (dot > 0) fileName.substring(dot) else ""
        var index = 2
        while (target.exists()) {
            target = File(directory, "$stem ($index)$extension")
            index++
        }
        return target
    }

    private fun authorizationKey(deviceId: String, remoteIp: String, purpose: String): String =
        "$deviceId:$remoteIp:$purpose"

    private data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val contentLength: Long,
    )

    private data class Requester(
        val id: String,
        val name: String,
    )

    companion object {
        const val CONTROL_PORT = 49231
        const val PROTOCOL_VERSION = 3
        private const val SOCKET_TIMEOUT_MS = 65_000
        private const val SESSION_AUTHORIZATION_MS = 10 * 60 * 1000L
        private const val PURPOSE_SHARE = "share"
        private const val PURPOSE_FILES = "files"
        private const val INCOMING_RELATIVE_PATH = "DROPME/Incoming"
        private const val HEADER_DEVICE_ID = "x-dropme-device-id"
        private const val HEADER_DEVICE_NAME = "x-dropme-device-name"
        private const val HEADER_SHA256 = "x-dropme-sha256"
        private const val HEADER_LENGTH_RESPONSE = "X-DROPME-Length"
        private const val HEADER_SHA256_RESPONSE = "X-DROPME-SHA256"
        private const val INCOMING_TRANSFER_CHANNEL_ID = "dropme_incoming_transfers"
        private const val INCOMING_TRANSFER_NOTIFICATION_ID = 5100
        private const val LOG_TAG = "DROPME"
        private val DEVICE_ID_PATTERN = Regex("^[0-9a-f]{32}$")
        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Даёт сетевому обработчику application context без удержания Activity.
 */
internal object AppContextHolder {
    lateinit var context: android.content.Context
}
