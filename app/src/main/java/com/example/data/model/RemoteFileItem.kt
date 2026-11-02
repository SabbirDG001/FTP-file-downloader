package com.example.data.model

import com.example.data.util.Formatters
import java.util.Locale

/** A file or folder listed by an FTP server or an HTTP directory page. */
data class RemoteFileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val lastModified: Long = 0L,
    val directUrl: String? = null
) {
    val extension: String
        get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    val isVideo: Boolean
        get() = extension in VIDEO_EXTENSIONS

    val isAudio: Boolean
        get() = extension in AUDIO_EXTENSIONS

    val isArchive: Boolean
        get() = extension in ARCHIVE_EXTENSIONS

    val isDocument: Boolean
        get() = extension in DOCUMENT_EXTENSIONS

    val formattedSize: String
        get() = Formatters.bytes(sizeBytes)

    /** Episode number from names like "S01E05", "E05" or "Episode 5"; used to sort seasons. */
    val episodeNumber: Int
        get() {
            val match = Regex("""S\d+E(\d+)""", RegexOption.IGNORE_CASE).find(name)
                ?: Regex("""E(\d+)""", RegexOption.IGNORE_CASE).find(name)
                ?: Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE).find(name)
            return match?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
        }

    companion object {
        val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "mov", "wmv", "webm", "flv", "ts", "m4v", "3gp")
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus")
        val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "iso")
        val DOCUMENT_EXTENSIONS = setOf("pdf", "epub", "doc", "docx", "txt", "srt", "sub")
    }
}
