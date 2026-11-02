package com.example.data.model

/** Details about a social media post, read by yt-dlp before downloading. */
data class MediaInfo(
    val pageUrl: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String,
    val durationSeconds: Int,
    val platform: Platform,
    val options: List<FormatOption>
) {
    val videoOptions: List<FormatOption> get() = options.filter { it.kind == MediaKind.VIDEO }
    val audioOptions: List<FormatOption> get() = options.filter { it.kind == MediaKind.AUDIO }
}

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
