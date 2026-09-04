package com.example.data.ftp

import com.example.data.model.FtpServerConfig
import com.example.data.model.RemoteFileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import java.io.InputStream
import java.io.OutputStream

class FtpManager {

    private var browserClient: FTPClient? = null
    private var currentConfig: FtpServerConfig? = null

    suspend fun connect(config: FtpServerConfig): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            disconnect()
            val client = FTPClient().apply {
                connectTimeout = 15000
                defaultTimeout = 15000
                setDataTimeout(java.time.Duration.ofSeconds(30))
                controlEncoding = "UTF-8"
            }

            client.connect(config.host, config.port)
            val reply = client.replyCode
            if (!FTPReply.isPositiveCompletion(reply)) {
                client.disconnect()
                return@withContext Result.failure(Exception("FTP server refused connection. Code: $reply"))
            }

            val username = config.username.ifBlank { "anonymous" }
            val password = config.password
            val loggedIn = client.login(username, password)
            if (!loggedIn) {
                client.disconnect()
                return@withContext Result.failure(Exception("FTP Login failed for user '$username'"))
            }

            client.setFileType(FTP.BINARY_FILE_TYPE)
            if (config.isPassiveMode) {
                client.enterLocalPassiveMode()
            } else {
                client.enterLocalActiveMode()
            }

            browserClient = client
            currentConfig = config
            Result.success(true)
        } catch (e: Exception) {
            disconnect()
            Result.failure(e)
        }
    }

    suspend fun listFiles(directoryPath: String): Result<List<RemoteFileItem>> = withContext(Dispatchers.IO) {
        val client = browserClient ?: return@withContext Result.failure(Exception("FTP not connected"))
        try {
            if (!client.isConnected) {
                currentConfig?.let { connect(it) }
            }

            val targetPath = if (directoryPath.isBlank()) "/" else directoryPath
            val success = client.changeWorkingDirectory(targetPath)
            if (!success && targetPath != "/") {
                return@withContext Result.failure(Exception("Failed to navigate to: $targetPath"))
            }

            val currentWorkingDir = client.printWorkingDirectory() ?: targetPath
            val ftpFiles: Array<FTPFile> = client.listFiles(currentWorkingDir)

            val items = ftpFiles
                .filter { it.name != "." && it.name != ".." }
                .map { file ->
                    val fullPath = if (currentWorkingDir.endsWith("/")) {
                        currentWorkingDir + file.name
                    } else {
                        "$currentWorkingDir/${file.name}"
                    }
                    RemoteFileItem(
                        name = file.name,
                        path = fullPath,
                        isDirectory = file.isDirectory,
                        sizeBytes = if (file.isDirectory) 0L else file.size,
                        lastModified = file.timestamp?.timeInMillis ?: 0L
                    )
                }
                .sortedWith(
                    compareByDescending<RemoteFileItem> { it.isDirectory }
                        .thenBy { it.name.lowercase() }
                )

            Result.success(items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadFile(
        config: FtpServerConfig,
        remoteFilePath: String,
        outputStream: OutputStream,
        totalBytesHint: Long,
        onProgress: (downloaded: Long, total: Long, speedBytesSec: Long) -> Unit,
        isCancelled: () -> Boolean
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val transferClient = FTPClient().apply {
            connectTimeout = 15000
            defaultTimeout = 15000
            setDataTimeout(java.time.Duration.ofSeconds(60))
            controlEncoding = "UTF-8"
        }

        try {
            transferClient.connect(config.host, config.port)
            val reply = transferClient.replyCode
            if (!FTPReply.isPositiveCompletion(reply)) {
                return@withContext Result.failure(Exception("Transfer connection refused: $reply"))
            }

            val user = config.username.ifBlank { "anonymous" }
            if (!transferClient.login(user, config.password)) {
                return@withContext Result.failure(Exception("Transfer login failed"))
            }

            transferClient.setFileType(FTP.BINARY_FILE_TYPE)
            if (config.isPassiveMode) {
                transferClient.enterLocalPassiveMode()
            } else {
                transferClient.enterLocalActiveMode()
            }

            // Determine actual file size if not known
            var expectedSize = totalBytesHint
            if (expectedSize <= 0) {
                try {
                    val status = transferClient.getStatus(remoteFilePath)
                    // status might contain size or listFiles
                    val dir = remoteFilePath.substringBeforeLast('/', "/")
                    val fname = remoteFilePath.substringAfterLast('/')
                    val listed = transferClient.listFiles(if (dir.isEmpty()) "/" else dir)
                    val target = listed.find { it.name == fname }
                    if (target != null && target.size > 0) {
                        expectedSize = target.size
                    }
                } catch (_: Exception) {}
            }

            val inputStream: InputStream? = transferClient.retrieveFileStream(remoteFilePath)
            if (inputStream == null) {
                return@withContext Result.failure(Exception("Unable to open FTP file stream for $remoteFilePath. Reply: ${transferClient.replyString}"))
            }

            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            var downloaded = 0L
            var lastUpdate = System.currentTimeMillis()
            var bytesSinceUpdate = 0L

            inputStream.use { stream ->
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    if (isCancelled()) {
                        return@withContext Result.failure(Exception("Download cancelled by user"))
                    }

                    outputStream.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    bytesSinceUpdate += bytesRead

                    val now = System.currentTimeMillis()
                    val diff = now - lastUpdate
                    if (diff >= 400) {
                        val speed = if (diff > 0) (bytesSinceUpdate * 1000) / diff else 0L
                        onProgress(downloaded, if (expectedSize > 0) expectedSize else downloaded, speed)
                        lastUpdate = now
                        bytesSinceUpdate = 0L
                    }
                }
                outputStream.flush()
            }

            if (isCancelled()) {
                return@withContext Result.failure(Exception("Download cancelled by user"))
            }

            val completed = transferClient.completePendingCommand()
            if (!completed && !transferClient.isConnected) {
                // If EOF was reached and data was written, check bytes
                if (downloaded > 0) {
                    onProgress(downloaded, downloaded, 0L)
                    return@withContext Result.success(true)
                }
                return@withContext Result.failure(Exception("FTP completePendingCommand failed"))
            }

            onProgress(downloaded, downloaded, 0L)
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                if (transferClient.isConnected) {
                    transferClient.logout()
                    transferClient.disconnect()
                }
            } catch (_: Exception) {}
        }
    }

    fun disconnect() {
        try {
            browserClient?.let {
                if (it.isConnected) {
                    it.logout()
                    it.disconnect()
                }
            }
        } catch (_: Exception) {}
        browserClient = null
    }

    fun isConnected(): Boolean {
        return browserClient?.isConnected == true
    }
}
