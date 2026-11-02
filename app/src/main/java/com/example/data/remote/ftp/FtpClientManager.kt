package com.example.data.remote.ftp

import com.example.data.model.FtpServerConfig
import com.example.data.model.RemoteFileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.IOException
import java.io.OutputStream
import java.time.Duration

/**
 * FTP access. Browsing uses one long-lived connection; every download opens its own
 * connection so several files can download at once.
 */
class FtpClientManager {

    private val browseLock = Mutex()
    private var browserClient: FTPClient? = null
    private var browserConfig: FtpServerConfig? = null

    /** Connects the browsing session. */
    suspend fun connect(config: FtpServerConfig): Result<Unit> = withContext(Dispatchers.IO) {
        browseLock.withLock {
            runCatching {
                closeQuietly(browserClient)
                browserClient = null
                browserClient = openClient(config, dataTimeoutSeconds = 30)
                browserConfig = config
            }
        }
    }

    suspend fun listFiles(directoryPath: String): Result<List<RemoteFileItem>> = withContext(Dispatchers.IO) {
        browseLock.withLock {
            runCatching {
                val config = browserConfig ?: throw IOException("Not connected to an FTP server")
                var client = browserClient
                if (client == null || !client.isConnected) {
                    client = openClient(config, dataTimeoutSeconds = 30)
                    browserClient = client
                }

                val targetPath = directoryPath.ifBlank { "/" }
                if (!client.changeWorkingDirectory(targetPath) && targetPath != "/") {
                    throw IOException("Failed to open folder: $targetPath")
                }

                val workingDir = client.printWorkingDirectory() ?: targetPath
                client.listFiles(workingDir)
                    .filter { it != null && it.name != "." && it.name != ".." }
                    .map { file ->
                        RemoteFileItem(
                            name = file.name,
                            path = joinPath(workingDir, file.name),
                            isDirectory = file.isDirectory,
                            sizeBytes = if (file.isDirectory) 0L else file.size,
                            lastModified = file.timestamp?.timeInMillis ?: 0L
                        )
                    }
                    .sortedWith(
                        compareByDescending<RemoteFileItem> { it.isDirectory }
                            .thenBy { it.episodeNumber }
                            .thenBy { it.name.lowercase() }
                    )
            }
        }
    }

    /**
     * Downloads [remoteFilePath] into [openOutput]. When [resumeFrom] is above zero the
     * server is asked to continue from that byte (FTP "REST").
     */
    suspend fun download(
        config: FtpServerConfig,
        remoteFilePath: String,
        resumeFrom: Long,
        totalBytesHint: Long,
        openOutput: (append: Boolean) -> OutputStream,
        onProgress: (downloaded: Long, total: Long, speedBytesPerSec: Long) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        val client = openClient(config, dataTimeoutSeconds = 60)
        try {
            var expectedSize = totalBytesHint
            if (expectedSize <= 0) {
                expectedSize = runCatching { client.mlistFile(remoteFilePath)?.size ?: 0L }.getOrDefault(0L)
            }
            if (expectedSize <= 0) {
                expectedSize = runCatching { client.getSize(remoteFilePath)?.trim()?.toLong() ?: 0L }.getOrDefault(0L)
            }

            var offset = resumeFrom.coerceAtLeast(0L)
            if (expectedSize > 0 && offset >= expectedSize) offset = 0L
            client.restartOffset = offset

            val input = client.retrieveFileStream(remoteFilePath)
            if (input == null && offset > 0) {
                // Server does not support resuming; start over.
                offset = 0L
                client.restartOffset = 0L
            }
            val stream = input ?: client.retrieveFileStream(remoteFilePath)
                ?: throw IOException("Unable to open $remoteFilePath. Server said: ${client.replyString?.trim()}")

            var downloaded = offset
            openOutput(offset > 0).use { out ->
                stream.use { source ->
                    downloaded = copyWithProgress(source, out, offset, expectedSize, onProgress)
                }
            }

            // Tell the server the transfer is finished; some servers drop the connection here.
            runCatching { client.completePendingCommand() }
            onProgress(downloaded, maxOf(expectedSize, downloaded), 0L)
            downloaded
        } finally {
            closeQuietly(client)
        }
    }

    fun disconnect() {
        closeQuietly(browserClient)
        browserClient = null
    }

    private fun openClient(config: FtpServerConfig, dataTimeoutSeconds: Long): FTPClient {
        val client = FTPClient().apply {
            connectTimeout = 15_000
            defaultTimeout = 15_000
            setDataTimeout(Duration.ofSeconds(dataTimeoutSeconds))
            controlEncoding = "UTF-8"
            autodetectUTF8 = true
        }
        try {
            client.connect(config.host.trim(), config.port)
            if (!FTPReply.isPositiveCompletion(client.replyCode)) {
                throw IOException("FTP server refused the connection (code ${client.replyCode})")
            }
            val username = config.username.ifBlank { "anonymous" }
            if (!client.login(username, config.password)) {
                throw IOException("FTP login failed for user '$username'")
            }
            client.setFileType(FTP.BINARY_FILE_TYPE)
            if (config.isPassiveMode) client.enterLocalPassiveMode() else client.enterLocalActiveMode()
            return client
        } catch (e: Exception) {
            closeQuietly(client)
            throw e
        }
    }

    private fun closeQuietly(client: FTPClient?) {
        if (client == null) return
        try {
            if (client.isConnected) {
                runCatching { client.logout() }
                client.disconnect()
            }
        } catch (_: Exception) {
        }
    }

    private fun joinPath(dir: String, name: String): String =
        if (dir.endsWith("/")) dir + name else "$dir/$name"
}

/** Shared copy loop for FTP and HTTP: reports progress about every 400 ms and stops when cancelled. */
internal suspend fun copyWithProgress(
    input: java.io.InputStream,
    output: OutputStream,
    startOffset: Long,
    totalBytes: Long,
    onProgress: (downloaded: Long, total: Long, speedBytesPerSec: Long) -> Unit
): Long {
    val buffer = ByteArray(64 * 1024)
    var downloaded = startOffset
    var lastUpdate = System.currentTimeMillis()
    var bytesSinceUpdate = 0L
    val context = currentCoroutineContext()

    while (true) {
        context.ensureActive()
        val read = input.read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
        downloaded += read
        bytesSinceUpdate += read

        val now = System.currentTimeMillis()
        val elapsed = now - lastUpdate
        if (elapsed >= 400) {
            val speed = (bytesSinceUpdate * 1000) / elapsed
            onProgress(downloaded, if (totalBytes > 0) totalBytes else 0L, speed)
            lastUpdate = now
            bytesSinceUpdate = 0L
        }
    }
    output.flush()
    return downloaded
}
