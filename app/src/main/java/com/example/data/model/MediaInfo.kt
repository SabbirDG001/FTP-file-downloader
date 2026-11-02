package com.example.data.model

/** What a link turned out to be: one video, or a playlist / channel with many. */
sealed interface FetchResult {
    data class Video(val info: MediaInfo) : FetchResult
    data class Playlist(val info: PlaylistInfo) : FetchResult
}

/** Details about a social media post, read by yt-dlp before downloading. */
data class MediaInfo(
    val pageUrl: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String,
    val durationSeconds: Int,
    val platform: Platform,
    val options: List<FormatOption>,
    /** Set when this video was opened from a playlist link, so the whole playlist can be offered. */
    val playlistUrl: String? = null
) {
    val videoOptions: List<FormatOption> get() = options.filter { it.kind == MediaKind.VIDEO }
    val audioOptions: List<FormatOption> get() = options.filter { it.kind == MediaKind.AUDIO }
}

/** A playlist (or channel / profile) with its videos, read quickly without opening each one. */
data class PlaylistInfo(
    val pageUrl: String,
    val title: String,
    val uploader: String,
    val platform: Platform,
    val entries: List<PlaylistEntry>
)

data class PlaylistEntry(
    val id: String,
    val url: String,
    val title: String,
    val durationSeconds: Int,
    val thumbnailUrl: String
)

/** One choice in the "pick a quality" sheet, e.g. "720p MP4" or "MP3 audio". */
data class FormatOption(
    val id: String,
    val label: String,
    val detail: String,
    val kind: MediaKind,
    /** yt-dlp `-f` selector. */
    val selector: String,
    /** File extension of the final file. */
    val extension: String,
    /** Video height in pixels, or 0 for audio / unknown. */
    val height: Int = 0,
    /** Best guess of the final size, or 0 when unknown. */
    val estimatedBytes: Long = 0L,
    /** For audio: the format yt-dlp converts to ("mp3" / "m4a"). */
    val audioFormat: String = ""
)
