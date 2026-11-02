package com.example.data.remote.social

import com.example.data.model.FetchResult
import com.example.data.model.FormatOption
import com.example.data.model.MediaInfo
import com.example.data.model.MediaKind
import com.example.data.model.Platform
import com.example.data.model.PlaylistEntry
import com.example.data.model.PlaylistInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the JSON that `yt-dlp -J --flat-playlist` prints. One run gives either a
 * single video with its formats, or a playlist with a quick list of its videos.
 */
object YtDlpJsonParser {

    fun parse(json: String, requestedUrl: String): FetchResult {
        val root = JSONObject(json.substring(json.indexOf('{')))
        val pageUrl = root.optStringOrNull("webpage_url") ?: requestedUrl
        val platform = Platform.fromUrl(pageUrl).takeIf { it != Platform.OTHER } ?: Platform.fromUrl(requestedUrl)

        val type = root.optString("_type")
        if (type == "playlist" || type == "multi_video") {
            return FetchResult.Playlist(parsePlaylist(root, pageUrl, platform))
        }

        return FetchResult.Video(
            MediaInfo(
                pageUrl = pageUrl,
                title = (root.optStringOrNull("title") ?: root.optStringOrNull("fulltitle"))
                    ?: "${platform.displayName} video",
                uploader = root.optStringOrNull("uploader") ?: root.optStringOrNull("channel") ?: "",
                thumbnailUrl = root.optStringOrNull("thumbnail") ?: bestThumbnail(root.optJSONArray("thumbnails")),
                durationSeconds = root.optDouble("duration", 0.0).toInt(),
                platform = platform,
                options = buildOptions(parseFormats(root.optJSONArray("formats"))),
                playlistUrl = playlistUrlFor(requestedUrl)
            )
        )
    }

    private fun parsePlaylist(root: JSONObject, pageUrl: String, platform: Platform): PlaylistInfo {
        val entries = mutableListOf<PlaylistEntry>()
        val array = root.optJSONArray("entries") ?: JSONArray()
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val id = entry.optStringOrNull("id") ?: continue
            val title = entry.optStringOrNull("title") ?: continue
            // Skip deleted / private videos, which YouTube lists with placeholder titles.
            if (title == "[Deleted video]" || title == "[Private video]") continue
            val url = entry.optStringOrNull("url")?.takeIf { it.startsWith("http") }
                ?: entry.optStringOrNull("webpage_url")
                ?: if (platform == Platform.YOUTUBE) "https://www.youtube.com/watch?v=$id" else continue
            entries += PlaylistEntry(
                id = id,
                url = url,
                title = title,
                durationSeconds = entry.optDouble("duration", 0.0).toInt(),
                thumbnailUrl = entry.optStringOrNull("thumbnail") ?: bestThumbnail(entry.optJSONArray("thumbnails"))
            )
        }
        return PlaylistInfo(
            pageUrl = pageUrl,
            title = root.optStringOrNull("title") ?: "${platform.displayName} playlist",
            uploader = root.optStringOrNull("uploader") ?: root.optStringOrNull("channel") ?: "",
            platform = platform,
            entries = entries
        )
    }

    /**
     * When a video link also names a playlist ("watch?v=abc&list=PL123"), returns the link
     * to read as a playlist. The original link is kept so YouTube "Mix" playlists work too.
     */
    fun playlistUrlFor(url: String): String? {
        if (Platform.fromUrl(url) != Platform.YOUTUBE) return null
        return if (Regex("""[?&]list=[A-Za-z0-9_-]+""").containsMatchIn(url)) url else null
    }

    /** True for links that are only a playlist (no single video in them). */
    fun isPlaylistOnlyUrl(url: String): Boolean {
        val lower = url.lowercase()
        return Platform.fromUrl(url) == Platform.YOUTUBE &&
            (lower.contains("/playlist?") || (lower.contains("list=") && !lower.contains("v=") && !lower.contains("youtu.be/")))
    }

    // region Formats

    internal data class RawFormat(
        val height: Int,
        val fps: Int,
        val hasVideo: Boolean,
        val hasAudio: Boolean,
        val sizeBytes: Long,
        val bitrate: Double
    )

    private fun parseFormats(array: JSONArray?): List<RawFormat> {
        if (array == null) return emptyList()
        val list = mutableListOf<RawFormat>()
        for (i in 0 until array.length()) {
            val f = array.optJSONObject(i) ?: continue
            val vcodec = f.optString("vcodec", "none")
            val acodec = f.optString("acodec", "none")
            val size = f.optLong("filesize", 0L).takeIf { it > 0 } ?: f.optLong("filesize_approx", 0L)
            list += RawFormat(
                height = f.optInt("height", 0),
                fps = f.optDouble("fps", 0.0).toInt(),
                hasVideo = vcodec != "none" && vcodec.isNotBlank(),
                hasAudio = acodec != "none" && acodec.isNotBlank(),
                sizeBytes = size,
                bitrate = maxOf(f.optDouble("tbr", 0.0), f.optDouble("abr", 0.0)).takeIf { !it.isNaN() } ?: 0.0
            )
        }
        return list
    }

    internal fun buildOptions(formats: List<RawFormat>): List<FormatOption> {
        val options = mutableListOf<FormatOption>()

        val bestAudio = formats.filter { it.hasAudio && !it.hasVideo }.maxByOrNull { it.bitrate }
        val audioSize = bestAudio?.sizeBytes ?: 0L

        val videoFormats = formats.filter { it.hasVideo && it.height > 0 }
        for (height in videoFormats.map { it.height }.distinct().sortedDescending()) {
            val best = videoFormats.filter { it.height == height }.maxByOrNull { it.bitrate } ?: continue
            val estimate = when {
                best.sizeBytes <= 0 -> 0L
                best.hasAudio -> best.sizeBytes
                else -> best.sizeBytes + audioSize
            }
            options += videoOption(height, best.fps, estimate)
        }

        if (options.isEmpty()) {
            // Some sites (e.g. many Instagram/Facebook posts) only report one combined format.
            options += FormatOption(
                id = "video_best", label = "Best quality", detail = "MP4", kind = MediaKind.VIDEO,
                selector = "bv*+ba/b", extension = "mp4"
            )
        }
        options += audioOptions(audioSize)
        return options
    }

    /** The same quality choices for every video in a playlist. */
    fun playlistOptions(): List<FormatOption> =
        listOf(
            FormatOption(
                id = "video_best", label = "Best", detail = "Highest quality each video has",
                kind = MediaKind.VIDEO, selector = "bv*+ba/b", extension = "mp4"
            )
        ) + listOf(1080, 720, 480, 360).map { videoOption(it, 0, 0L) } + audioOptions(0L)

    private fun videoOption(height: Int, fps: Int, estimate: Long) = FormatOption(
        id = "video_$height",
        label = when {
            height >= 4320 -> "8K"
            height >= 2160 -> "4K"
            height >= 1440 -> "1440p"
            else -> "${height}p"
        },
        detail = if (fps > 30) "MP4 • ${fps}fps" else "MP4",
        kind = MediaKind.VIDEO,
        // Falls back to the best available quality if a video doesn't have this height.
        selector = "bv*[height<=$height]+ba/b[height<=$height]/bv*+ba/b",
        extension = "mp4",
        height = height,
        estimatedBytes = estimate
    )

    private fun audioOptions(audioSize: Long) = listOf(
        FormatOption(
            id = "audio_mp3", label = "MP3", detail = "Audio only", kind = MediaKind.AUDIO,
            selector = "ba/b", extension = "mp3", estimatedBytes = audioSize, audioFormat = "mp3"
        ),
        FormatOption(
            id = "audio_m4a", label = "M4A", detail = "Audio only • best quality", kind = MediaKind.AUDIO,
            selector = "ba[ext=m4a]/ba/b", extension = "m4a", estimatedBytes = audioSize, audioFormat = "m4a"
        )
    )

    // endregion

    private fun bestThumbnail(array: JSONArray?): String {
        if (array == null || array.length() == 0) return ""
        var best = ""
        var bestWidth = -1
        for (i in 0 until array.length()) {
            val t = array.optJSONObject(i) ?: continue
            val url = t.optStringOrNull("url") ?: continue
            val width = t.optInt("width", 0)
            // Prefer a medium-size thumbnail; huge ones are slow to load in a list.
            val score = if (width in 1..800) width else if (width == 0) 1 else 0
            if (score > bestWidth) {
                best = url
                bestWidth = score
            }
        }
        return best
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
}
