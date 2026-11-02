package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.util.Formatters
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

/** Where a download comes from. Each source has its own downloader in `data/download`. */
enum class DownloadSource {
    /** A file on an FTP server. */
    FTP,

    /** A direct HTTP(S) file link, e.g. a movie or an episode from a season folder. */
    HTTP,

    /** A YouTube / Facebook / Instagram / TikTok / ... page, downloaded with yt-dlp. */
    SOCIAL
}

/** What the user asked for when downloading from a social media page. */
enum class MediaKind { VIDEO, AUDIO }

/**
 * One download in the queue or history. Saved in the Room database so history,
 * paused and failed downloads survive app restarts.
 */
@Entity(tableName = "downloads")
data class DownloadTask(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    /** FTP path, HTTP URL, or the social media page URL. */
    val sourceUrl: String,
    val source: DownloadSource,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = -1L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val errorMessage: String? = null,
    /** Sub folder inside the chosen download folder ("Movies", "YouTube", ...). */
    val subFolder: String = "",
    /**
     * A different folder picked for this download (e.g. a playlist saved somewhere else
     * on the phone). Empty means the main download folder.
     */
    val targetTreeUri: String = "",
    /** Storage Access Framework URI of the saved file. */
    val outputUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0L,

    // FTP connection used for this file (so custom servers work, not just the default one).
    val ftpHost: String = "",
    val ftpPort: Int = 21,
    val ftpUsername: String = "",
    val ftpPassword: String = "",
    val ftpPassive: Boolean = true,

    // Social media details.
    val title: String = "",
    val thumbnailUrl: String = "",
    val platform: Platform = Platform.OTHER,
    /** yt-dlp format selector, e.g. "bv*[height<=720]+ba/b[height<=720]/b". */
    val formatSelector: String = "",
    val mediaKind: MediaKind = MediaKind.VIDEO,
    /** "mp3" or "m4a" for audio downloads. */
    val audioFormat: String = "",
    /** Short label of the chosen quality, e.g. "720p" or "MP3". */
    val qualityLabel: String = ""
)

// Derived values live in extensions so Room does not try to store them.

val DownloadTask.displayTitle: String
    get() = title.ifBlank { fileName }

val DownloadTask.progress: Float
    get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

val DownloadTask.progressPercent: Int
    get() = (progress * 100).toInt()

val DownloadTask.formattedDownloaded: String
    get() = Formatters.bytes(downloadedBytes)

val DownloadTask.formattedTotal: String
    get() = if (totalBytes > 0) Formatters.bytes(totalBytes) else "Unknown size"

val DownloadTask.formattedSpeed: String
    get() = Formatters.speed(speedBytesPerSec)

/** Still waiting or running. */
val DownloadTask.isActive: Boolean
    get() = status == DownloadStatus.QUEUED ||
        status == DownloadStatus.CONNECTING ||
        status == DownloadStatus.DOWNLOADING

val DownloadTask.isRunning: Boolean
    get() = status == DownloadStatus.CONNECTING || status == DownloadStatus.DOWNLOADING

val DownloadTask.isFinished: Boolean
    get() = status == DownloadStatus.COMPLETED ||
        status == DownloadStatus.CANCELED ||
        status == DownloadStatus.FAILED

fun DownloadTask.ftpConfig(): FtpServerConfig = FtpServerConfig(
    name = ftpHost,
    host = ftpHost,
    port = ftpPort,
    username = ftpUsername,
    password = ftpPassword,
    isPassiveMode = ftpPassive,
    protocol = ServerProtocol.FTP
)
