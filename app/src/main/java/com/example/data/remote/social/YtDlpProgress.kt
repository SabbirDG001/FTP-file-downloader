package com.example.data.remote.social

/** Progress reported by yt-dlp, parsed from its output line. */
data class YtDlpProgress(
    val percent: Float,
    val etaSeconds: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long,
    /** True while FFmpeg merges/converts after the download. */
    val isPostProcessing: Boolean
) {
    companion object {
        private val sizeRegex = Regex("""of\s+~?\s*([\d.]+)\s*([KMGT]?i?B)""")
        private val speedRegex = Regex("""at\s+([\d.]+)\s*([KMGT]?i?B)/s""")

        /** Parses lines like "[download]  45.3% of ~120.50MiB at 2.10MiB/s ETA 00:31". */
        fun parse(percent: Float, etaSeconds: Long, line: String): YtDlpProgress {
            val total = sizeRegex.find(line)?.let { toBytes(it.groupValues[1], it.groupValues[2]) } ?: 0L
            val speed = speedRegex.find(line)?.let { toBytes(it.groupValues[1], it.groupValues[2]) } ?: 0L
            val postProcessing = line.startsWith("[Merger]") ||
                line.startsWith("[ExtractAudio]") ||
                line.startsWith("[VideoConvertor]") ||
                line.startsWith("[FixupM3u8]") ||
                line.startsWith("[ffmpeg]")
            return YtDlpProgress(
                percent = percent.coerceIn(0f, 100f),
                etaSeconds = etaSeconds,
                totalBytes = total,
                speedBytesPerSec = speed,
                isPostProcessing = postProcessing
            )
        }

        fun toBytes(number: String, unit: String): Long {
            val value = number.toDoubleOrNull() ?: return 0L
            val multiplier = when (unit.uppercase().replace("I", "")) {
                "KB" -> 1024.0
                "MB" -> 1024.0 * 1024
                "GB" -> 1024.0 * 1024 * 1024
                "TB" -> 1024.0 * 1024 * 1024 * 1024
                else -> 1.0
            }
            return (value * multiplier).toLong()
        }
    }
}
