package com.example.data.model

import java.util.Locale

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
        get() = extension in setOf("mkv", "mp4", "avi", "mov", "wmv", "webm", "flv", "ts", "m4v")

    val isArchive: Boolean
        get() = extension in setOf("zip", "rar", "7z", "tar", "gz", "iso")

    val isDocument: Boolean
        get() = extension in setOf("pdf", "epub", "doc", "docx", "txt")

    val formattedSize: String
        get() = formatBytes(sizeBytes)

    val episodeNumber: Int
        get() {
            val match = Regex("""S\d+E(\d+)""", RegexOption.IGNORE_CASE).find(name)
                ?: Regex("""E(\d+)""", RegexOption.IGNORE_CASE).find(name)
                ?: Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE).find(name)
            return match?.groupValues?.get(1)?.toIntOrNull() ?: 999999
        }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
            val gb = mb / 1024.0
            return String.format(Locale.US, "%.2f GB", gb)
        }

        fun formatSpeed(bytesPerSec: Long): String {
            return "${formatBytes(bytesPerSec)}/s"
        }
    }
}
