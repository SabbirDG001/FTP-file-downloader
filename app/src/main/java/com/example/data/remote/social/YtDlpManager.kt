package com.example.data.remote.social

import android.content.Context
import com.example.data.model.FetchResult
import com.example.data.model.MediaKind
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** State of the bundled yt-dlp engine, shown in Settings. */
sealed interface EngineState {
    data object NotReady : EngineState
    data object Preparing : EngineState
    data class Ready(val version: String) : EngineState
    data object Updating : EngineState
    data class Failed(val message: String) : EngineState
}

/**
 * Wrapper around yt-dlp (via youtubedl-android). yt-dlp is what actually understands
 * YouTube, Facebook, Instagram, TikTok and ~1,800 other sites.
 */
class YtDlpManager(private val context: Context) {

    companion object {
        /** Very long playlists / channels are cut here so reading them stays quick. */
        const val MAX_PLAYLIST_ITEMS = 1000
    }

    private val initLock = Mutex()
    @Volatile private var initialized = false

    private val _state = MutableStateFlow<EngineState>(EngineState.NotReady)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** Unpacks Python, yt-dlp and FFmpeg on first use (takes a few seconds the very first time). */
    suspend fun ensureReady() {
        if (initialized) return
        initLock.withLock {
            if (initialized) return
            _state.value = EngineState.Preparing
            withContext(Dispatchers.IO) {
                try {
                    YoutubeDL.getInstance().init(context)
                    FFmpeg.getInstance().init(context)
                    initialized = true
                    _state.value = EngineState.Ready(currentVersion())
                } catch (e: Throwable) {
                    _state.value = EngineState.Failed(e.message ?: "Could not start the download engine")
                    throw IOException("Could not start the download engine: ${e.message}", e)
                }
            }
        }
    }

    /** Downloads the newest yt-dlp. Sites change often, so this fixes most "unable to extract" errors. */
    suspend fun update(): Result<String> = runCatching {
        ensureReady()
        _state.value = EngineState.Updating
        withContext(Dispatchers.IO) {
            try {
                val status = YoutubeDL.getInstance().updateYoutubeDL(context)
                val version = currentVersion()
                _state.value = EngineState.Ready(version)
                if (status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) {
                    "Already up to date ($version)"
                } else {
                    "Updated to $version"
                }
            } catch (e: Throwable) {
                _state.value = EngineState.Ready(currentVersion())
                throw IOException("Update failed: ${e.message}", e)
            }
        }
    }

    private fun currentVersion(): String =
        runCatching { YoutubeDL.getInstance().version(context) }.getOrNull() ?: "unknown"

    /**
     * Reads a link. One yt-dlp run returns either a single video with its qualities, or
     * a playlist with a quick list of its videos (`--flat-playlist` skips opening each one).
     *
     * @param wholePlaylist read the playlist even when the link also points at one video.
     */
    suspend fun fetch(url: String, cookiesFile: File?, wholePlaylist: Boolean = false): FetchResult {
        ensureReady()
        val playlistMode = wholePlaylist || YtDlpJsonParser.isPlaylistOnlyUrl(url)
        val json = withContext(Dispatchers.IO) {
            val request = YoutubeDLRequest(url).apply {
                addOption("-J")
                addOption("--flat-playlist")
                addOption(if (playlistMode) "--yes-playlist" else "--no-playlist")
                addOption("--playlist-end", MAX_PLAYLIST_ITEMS)
                addOption("--no-warnings")
                addOption("--socket-timeout", 20)
                cookiesFile?.let { addOption("--cookies", it.absolutePath) }
            }
            try {
                runInterruptible { YoutubeDL.getInstance().execute(request).out }
            } catch (e: YoutubeDL.CanceledException) {
                throw CancellationException("Stopped")
            }
        }
        if (json.isNullOrBlank() || !json.contains('{')) throw IOException("yt-dlp returned no information for this link")
        return YtDlpJsonParser.parse(json, url)
    }

    /**
     * Runs the download into [workDir] and returns the finished file. Cancelling the
     * coroutine stops yt-dlp; the partial file stays so the download can resume later.
     */
    suspend fun download(
        pageUrl: String,
        processId: String,
        workDir: File,
        baseFileName: String,
        option: DownloadRequestOptions,
        cookiesFile: File?,
        onProgress: (YtDlpProgress) -> Unit
    ): File {
        ensureReady()
        workDir.mkdirs()

        val request = YoutubeDLRequest(pageUrl).apply {
            addOption("-o", File(workDir, "$baseFileName.%(ext)s").absolutePath)
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--continue")
            addOption("--concurrent-fragments", 4)
            if (option.rateLimitBytesPerSec > 0) addOption("--limit-rate", option.rateLimitBytesPerSec)
            addOption("-f", option.formatSelector.ifBlank { "bv*+ba/b" })
            if (option.kind == MediaKind.AUDIO) {
                addOption("-x")
                addOption("--audio-format", option.audioFormat.ifBlank { "mp3" })
                addOption("--audio-quality", 0)
            } else {
                addOption("--merge-output-format", "mp4")
            }
            cookiesFile?.let { addOption("--cookies", it.absolutePath) }
        }

        try {
            // runInterruptible interrupts the waiting thread when the coroutine is cancelled
            // (pause / cancel); we then also kill the yt-dlp process itself.
            runInterruptible(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(request, processId) { percent, eta, line ->
                    onProgress(YtDlpProgress.parse(percent, eta, line))
                }
            }
        } catch (e: CancellationException) {
            cancel(processId)
            throw e
        } catch (e: YoutubeDL.CanceledException) {
            throw CancellationException("Stopped")
        }

        return findOutputFile(workDir)
            ?: throw IOException("yt-dlp finished but no file was produced")
    }

    fun cancel(processId: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }

    private fun findOutputFile(workDir: File): File? =
        workDir.listFiles()
            ?.filter { file ->
                file.isFile &&
                    !file.name.endsWith(".part") &&
                    !file.name.endsWith(".ytdl") &&
                    !file.name.contains(".part-Frag") &&
                    !file.name.endsWith(".temp")
            }
            ?.maxByOrNull { it.length() }
}

/** What to download, taken from the chosen [FormatOption]. */
data class DownloadRequestOptions(
    val formatSelector: String,
    val kind: MediaKind,
    val audioFormat: String,
    /** Max speed for this download in bytes/second; 0 = no limit. */
    val rateLimitBytesPerSec: Long = 0L
)
