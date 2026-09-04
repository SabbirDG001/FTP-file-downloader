package com.example.data.model

import java.util.UUID

enum class DownloadStatus {
    QUEUED,
    CONNECTING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    CANCELED,
    FAILED
}

data class DownloadTask(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val remotePathOrUrl: String,
    val protocol: ServerProtocol,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val errorMessage: String? = null,
    val subFolder: String = "",
    val outputUri: String? = null,
    val attemptCount: Int = 1
) {
    val progress: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    val progressPercent: Int
        get() = (progress * 100).toInt()

    val formattedDownloaded: String
        get() = RemoteFileItem.formatBytes(downloadedBytes)

    val formattedTotal: String
        get() = if (totalBytes > 0) RemoteFileItem.formatBytes(totalBytes) else "Unknown size"

    val formattedSpeed: String
        get() = RemoteFileItem.formatSpeed(speedBytesPerSec)

    val isFinished: Boolean
        get() = status == DownloadStatus.COMPLETED || status == DownloadStatus.CANCELED || status == DownloadStatus.FAILED

    val isActive: Boolean
        get() = status == DownloadStatus.DOWNLOADING || status == DownloadStatus.CONNECTING || status == DownloadStatus.QUEUED
}
