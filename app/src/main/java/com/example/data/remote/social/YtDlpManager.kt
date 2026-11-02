package com.example.data.remote.social

import android.content.Context
import com.example.data.model.FormatOption
import com.example.data.model.MediaInfo
import com.example.data.model.MediaKind
import com.example.data.model.Platform
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
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

    /** Reads title, thumbnail and available qualities for a post. */
    suspend fun fetchInfo(url: String, cookiesFile: File?): MediaInfo {
        ensureReady()
        val info = withContext(Dispatchers.IO) {
            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                cookiesFile?.let { addOption("--cookies", it.absolutePath) }
            }
            runInterruptible { YoutubeDL.getInstance().getInfo(request) }
        }

        val platform = Platform.fromUrl(info.webpageUrl ?: url)
        return MediaInfo(
            pageUrl = info.webpageUrl ?: url,
            title = (info.title ?: info.fulltitle).orEmpty().ifBlank { "${platform.displayName} video" },
            uploader = info.uploader.orEmpty(),
            thumbnailUrl = info.thumbnail.orEmpty(),
            durationSeconds = info.duration,
            platform = platform,
            options = buildOptions(info.formats.orEmpty())
        )
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

    // region Format options

    private fun buildOptions(formats: List<VideoFormat>): List<FormatOption> {
        val options = mutableListOf<FormatOption>()

        val bestAudio = formats
            .filter { it.hasAudio() && !it.hasVideo() }
            .maxByOrNull { maxOf(it.abr, it.tbr) }
        val audioSize = bestAudio?.sizeBytes() ?: 0L

        val videoFormats = formats.filter { it.hasVideo() && it.height > 0 }
        val heights = videoFormats.map { it.height }.distinct().sortedDescending()

        for (height in heights) {
            val best = videoFormats.filter { it.height == height }.maxByOrNull { it.tbr }
            val videoSize = best?.sizeBytes() ?: 0L
            val needsAudio = best != null && !best.hasAudio()
            val estimate = when {
                videoSize <= 0 -> 0L
                needsAudio -> videoSize + audioSize
                else -> videoSize
            }
            val fps = best?.fps ?: 0
            options += FormatOption(
                id = "video_$height",
                label = qualityLabel(height),
                detail = buildString {
                    append("MP4")
                    if (fps > 30) append(" • ${fps}fps")
                },
                kind = MediaKind.VIDEO,
                selector = "bv*[height<=$height]+ba/b[height<=$height]/bv*+ba/b",
                extension = "mp4",
                height = height,
                estimatedBytes = estimate
            )
        }

        if (options.isEmpty()) {
            // Some sites (e.g. many Instagram/Facebook posts) only report one combined format.
            options += FormatOption(
                id = "video_best",
                label = "Best quality",
                detail = "MP4",
                kind = MediaKind.VIDEO,
                selector = "bv*+ba/b",
                extension = "mp4"
            )
        }

        options += FormatOption(
            id = "audio_mp3",
            label = "MP3",
            detail = "Audio only",
            kind = MediaKind.AUDIO,
            selector = "ba/b",
            extension = "mp3",
            estimatedBytes = audioSize,
            audioFormat = "mp3"
        )
        options += FormatOption(
            id = "audio_m4a",
            label = "M4A",
            detail = "Audio only • best quality",
            kind = MediaKind.AUDIO,
            selector = "ba[ext=m4a]/ba/b",
            extension = "m4a",
            estimatedBytes = audioSize,
            audioFormat = "m4a"
        )
        return options
    }

    private fun VideoFormat.hasVideo() = !vcodec.isNullOrBlank() && vcodec != "none"
    private fun VideoFormat.hasAudio() = !acodec.isNullOrBlank() && acodec != "none"
    private fun VideoFormat.sizeBytes() = if (fileSize > 0) fileSize else fileSizeApproximate

    private fun qualityLabel(height: Int): String = when {
        height >= 4320 -> "8K"
        height >= 2160 -> "4K"
        height >= 1440 -> "1440p"
        else -> "${height}p"
    }

    // endregion
}

/** What to download, taken from the chosen [FormatOption]. */
data class DownloadRequestOptions(
    val formatSelector: String,
    val kind: MediaKind,
    val audioFormat: String
)
