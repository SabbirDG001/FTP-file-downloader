package com.example.data.util

object LinkUtils {

    private val urlRegex = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /**
     * Pulls the first link out of shared text. Apps often share
     * "Check this out! https://youtu.be/abc" rather than a bare link.
     */
    fun extractUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = urlRegex.find(text) ?: return null
        return match.value.trimEnd('.', ',', ')', ']', '!', '?')
    }

    /** Adds https:// when the user typed "youtube.com/..." without a scheme. */
    fun normalize(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return trimmed
        extractUrl(trimmed)?.let { return it }
        return if (trimmed.contains('.') && !trimmed.contains(' ')) "https://$trimmed" else trimmed
    }

    fun isWebUrl(text: String): Boolean =
        text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)

    /**
     * The built-in browser's user agent without the parts that mark it as an app's
     * WebView ("; wv", "Version/4.0"). Facebook and Google block logins when they see those.
     */
    fun browserUserAgent(webViewDefault: String): String =
        webViewDefault.replace("; wv)", ")").replace(Regex("""\s*Version/\d+(\.\d+)*"""), "")
}
