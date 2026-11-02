package com.example.data.download

import android.content.Context
import com.example.data.model.DownloadTask
import com.example.data.model.ftpConfig
import com.example.data.remote.ftp.FtpClientManager
import com.example.data.remote.http.HttpDirectoryClient
import com.example.data.remote.social.BrowserCookieExporter
import com.example.data.remote.social.DownloadRequestOptions
import com.example.data.remote.social.YtDlpManager
import com.example.data.settings.SettingsRepository
import com.example.data.storage.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Lets a downloader report back to the [DownloadEngine]. */
interface ProgressReporter {
    /** The target file now exists (used to resume later). */
    fun onFileCreated(outputUri: String, fileName: String)

    fun onProgress(downloaded: Long, total: Long, speedBytesPerSec: Long, etaSeconds: Long = -1L)
}

data class DownloadOutcome(val outputUri: String, val fileName: String, val sizeBytes: Long)

/** One implementation per [com.example.data.model.DownloadSource]. */
interface MediaDownloader {
    /** Downloads the file, resuming from earlier progress when possible. */
    suspend fun download(task: DownloadTask, reporter: ProgressReporter): DownloadOutcome

    /** Deletes half-finished data after the user cancels. */
    suspend fun discardPartial(task: DownloadTask)
}

private const val NO_FOLDER_MESSAGE =
    "Unable to create the file in your download folder. Choose the folder again in Settings."

/** Direct HTTP(S) links: movies, season episodes, any file URL. */
class HttpDownloader(
    private val storage: StorageHelper,
    private val http: HttpDirectoryClient,
    private val limiter: SpeedLimiter? = null
) : MediaDownloader {

    override suspend fun download(task: DownloadTask, reporter: ProgressReporter): DownloadOutcome {
        val existing = storage.existingFile(task.outputUri)
        val resumeFrom = if (task.downloadedBytes > 0) existing?.length() ?: 0L else 0L
        val file = existing ?: storage.createFile(task.subFolder, task.fileName, task.targetTreeUri)
            ?: throw IOException(NO_FOLDER_MESSAGE)
        val name = file.name ?: task.fileName
        reporter.onFileCreated(file.uri.toString(), name)

        val size = http.download(
            url = task.sourceUrl,
            resumeFrom = resumeFrom,
            openOutput = { append -> storage.openOutputStream(file, append) },
            onProgress = { downloaded, total, speed -> reporter.onProgress(downloaded, total, speed) },
            limiter = limiter
        )
        return DownloadOutcome(file.uri.toString(), name, size)
    }

    override suspend fun discardPartial(task: DownloadTask) {
        withContext(Dispatchers.IO) { storage.deleteFile(task.outputUri) }
    }
}

/** Files from an FTP server. Uses the server saved in the task, not a hard-coded one. */
class FtpDownloader(
    private val storage: StorageHelper,
    private val ftp: FtpClientManager,
    private val limiter: SpeedLimiter? = null
) : MediaDownloader {

    override suspend fun download(task: DownloadTask, reporter: ProgressReporter): DownloadOutcome {
        val existing = storage.existingFile(task.outputUri)
        val resumeFrom = if (task.downloadedBytes > 0) existing?.length() ?: 0L else 0L
        val file = existing ?: storage.createFile(task.subFolder, task.fileName, task.targetTreeUri)
            ?: throw IOException(NO_FOLDER_MESSAGE)
        val name = file.name ?: task.fileName
        reporter.onFileCreated(file.uri.toString(), name)

        val size = ftp.download(
            config = task.ftpConfig(),
            remoteFilePath = task.sourceUrl,
            resumeFrom = resumeFrom,
            totalBytesHint = task.totalBytes,
            openOutput = { append -> storage.openOutputStream(file, append) },
            onProgress = { downloaded, total, speed -> reporter.onProgress(downloaded, total, speed) },
            limiter = limiter
        )
        return DownloadOutcome(file.uri.toString(), name, size)
    }

    override suspend fun discardPartial(task: DownloadTask) {
        withContext(Dispatchers.IO) { storage.deleteFile(task.outputUri) }
    }
}

/**
 * YouTube / Facebook / Instagram / TikTok / ... via yt-dlp. yt-dlp needs a normal
 * file path, so it downloads into the app's own folder first and the finished file is
 * then copied into the user's download folder.
 */
class SocialDownloader(
    private val context: Context,
    private val ytDlp: YtDlpManager,
    private val storage: StorageHelper,
    private val cookies: BrowserCookieExporter,
    private val settings: SettingsRepository
) : MediaDownloader {

    override suspend fun download(task: DownloadTask, reporter: ProgressReporter): DownloadOutcome {
        val workDir = workDirFor(task)
        val cookiesFile = if (settings.current.useBrowserLogin) cookies.export() else null
        val baseName = StorageHelper.cleanFileName(
            task.title.ifBlank { task.fileName.substringBeforeLast('.') }
        ).take(100).ifBlank { "video_${task.id.take(8)}" }

        var knownTotal = task.totalBytes
        val downloaded = ytDlp.download(
            pageUrl = task.sourceUrl,
            processId = task.id,
            workDir = workDir,
            baseFileName = baseName,
            option = DownloadRequestOptions(
                formatSelector = task.formatSelector,
                kind = task.mediaKind,
                audioFormat = task.audioFormat,
                rateLimitBytesPerSec = perDownloadLimit()
            ),
            cookiesFile = cookiesFile
        ) { progress ->
            if (progress.totalBytes > 0) knownTotal = progress.totalBytes
            val bytes = (knownTotal * (progress.percent / 100f)).toLong()
            reporter.onProgress(bytes, knownTotal, progress.speedBytesPerSec, progress.etaSeconds)
        }

        val size = downloaded.length()
        val saved = withContext(Dispatchers.IO) {
            storage.copyIntoFolder(downloaded, task.subFolder, task.targetTreeUri) { copied, total ->
                reporter.onProgress(copied, total, 0L)
            }
        }
        withContext(Dispatchers.IO) { workDir.deleteRecursively() }
        return DownloadOutcome(saved.uri.toString(), saved.name ?: downloaded.name, size)
    }

    override suspend fun discardPartial(task: DownloadTask) {
        ytDlp.cancel(task.id)
        withContext(Dispatchers.IO) {
            workDirFor(task).deleteRecursively()
            storage.deleteFile(task.outputUri)
        }
    }

    /**
     * yt-dlp runs as its own program, so it can't share the app's speed limiter. Give each
     * running download an equal share of the total limit instead.
     */
    private fun perDownloadLimit(): Long {
        val total = settings.current.speedLimitKbps * 1024L
        if (total <= 0) return 0L
        return (total / settings.current.maxConcurrentDownloads.coerceAtLeast(1)).coerceAtLeast(32 * 1024L)
    }

    private fun workDirFor(task: DownloadTask): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "ytdlp/${task.id}")
}
