package com.example.data.model

import java.net.URI
import java.util.Locale

/**
 * Social platforms the app highlights. yt-dlp supports many more sites; anything
 * else is still downloadable and is shown as [OTHER].
 */
enum class Platform(
    val displayName: String,
    /** Brand-ish color as ARGB, used for badges. */
    val color: Long,
    /** Short label shown inside the badge. */
    val badge: String,
    /** Page opened from the home screen shortcuts. */
    val homeUrl: String,
    private val hosts: List<String>
) {
    YOUTUBE(
        "YouTube", 0xFFFF0000, "YT", "https://m.youtube.com",
        listOf("youtube.com", "youtu.be", "youtube-nocookie.com")
    ),
    FACEBOOK(
        "Facebook", 0xFF1877F2, "f", "https://m.facebook.com",
        listOf("facebook.com", "fb.watch", "fb.com")
    ),
    INSTAGRAM(
        "Instagram", 0xFFE1306C, "IG", "https://www.instagram.com",
        listOf("instagram.com", "instagr.am")
    ),
    TIKTOK(
        "TikTok", 0xFF010101, "TT", "https://www.tiktok.com",
        listOf("tiktok.com")
    ),
    TWITTER(
        "X / Twitter", 0xFF14171A, "X", "https://x.com",
        listOf("twitter.com", "x.com")
    ),
    VIMEO(
        "Vimeo", 0xFF1AB7EA, "V", "https://vimeo.com",
        listOf("vimeo.com")
    ),
    DAILYMOTION(
        "Dailymotion", 0xFF0066DC, "D", "https://www.dailymotion.com",
        listOf("dailymotion.com", "dai.ly")
    ),
    OTHER("Web", 0xFF6750A4, "W", "https://www.google.com", emptyList());

    val folderName: String
        get() = when (this) {
            TWITTER -> "Twitter"
            OTHER -> "Web Videos"
            else -> displayName
        }

    companion object {
        /** Platforms shown as shortcuts on the home and browser screens. */
        val shortcuts = listOf(YOUTUBE, FACEBOOK, INSTAGRAM, TIKTOK, TWITTER, VIMEO)

        fun fromUrl(url: String): Platform {
            val host = hostOf(url) ?: return OTHER
            return entries.firstOrNull { platform ->
                platform.hosts.any { host == it || host.endsWith(".$it") }
            } ?: OTHER
        }

        /** True when the link points at one of the known social platforms. */
        fun isSocialLink(url: String): Boolean = fromUrl(url) != OTHER

        private fun hostOf(url: String): String? = try {
            val withScheme = if (url.contains("://")) url else "https://$url"
            URI(withScheme.trim()).host?.lowercase(Locale.ROOT)?.removePrefix("www.")
        } catch (_: Exception) {
            null
        }
    }
}
