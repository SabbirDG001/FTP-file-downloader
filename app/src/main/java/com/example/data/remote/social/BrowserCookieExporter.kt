package com.example.data.remote.social

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Some Instagram and Facebook posts can only be downloaded when logged in. If the
 * user logs in inside the app's built-in browser, we hand those cookies to yt-dlp
 * as a Netscape "cookies.txt" file. Cookies never leave the device.
 */
class BrowserCookieExporter(private val context: Context) {

    private val cookieFile: File get() = File(context.noBackupFilesDir, "browser_cookies.txt")

    /** Writes the current browser cookies and returns the file, or null when there are none. */
    suspend fun export(): File? {
        val cookiesByDomain = withContext(Dispatchers.Main) {
            runCatching {
                val manager = CookieManager.getInstance()
                DOMAINS.associateWith { domain -> manager.getCookie("https://www.$domain").orEmpty() }
            }.getOrDefault(emptyMap())
        }

        val expiry = System.currentTimeMillis() / 1000 + 30L * 24 * 3600
        val lines = buildList {
            add("# Netscape HTTP Cookie File")
            for ((domain, header) in cookiesByDomain) {
                header.split(";")
                    .map { it.trim() }
                    .filter { it.contains('=') }
                    .forEach { pair ->
                        val name = pair.substringBefore('=').trim()
                        val value = pair.substringAfter('=').trim()
                        if (name.isNotEmpty()) add(".$domain\tTRUE\t/\tTRUE\t$expiry\t$name\t$value")
                    }
            }
        }
        if (lines.size <= 1) return null

        return withContext(Dispatchers.IO) {
            cookieFile.writeText(lines.joinToString("\n", postfix = "\n"))
            cookieFile
        }
    }

    /** Logs out of every site in the built-in browser and deletes the exported file. */
    suspend fun clear() {
        withContext(Dispatchers.Main) {
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
        }
        withContext(Dispatchers.IO) { cookieFile.delete() }
    }

    private companion object {
        val DOMAINS = listOf(
            "youtube.com", "google.com", "facebook.com", "instagram.com",
            "tiktok.com", "x.com", "twitter.com", "vimeo.com"
        )
    }
}
