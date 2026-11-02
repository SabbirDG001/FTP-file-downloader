package com.example.data.remote.http

import com.example.data.download.SpeedLimiter
import com.example.data.model.RemoteFileItem
import com.example.data.remote.ftp.copyWithProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.io.OutputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Plain HTTP: reads "index of" directory pages (movie / season folders on media
 * servers) and downloads direct file links with resume support.
 */
class HttpDirectoryClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Lists the folders and media files linked from a directory page. */
    suspend fun listDirectory(url: String): Result<List<RemoteFileItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            val html = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP error ${response.code} ${response.message}")
                }
                response.body?.string().orEmpty()
            }

            val doc = Jsoup.parse(html, url)
            val items = mutableListOf<RemoteFileItem>()
            val seenUrls = mutableSetOf<String>()

            for (link in doc.select("a[href]")) {
                val href = link.attr("href")
                if (href.isBlank() || href.startsWith("?") || href.startsWith("#") || href == "/" || href == "../") continue

                val absUrl = link.absUrl("href")
                if (absUrl.isBlank() || !seenUrls.add(absUrl)) continue
                // Skip links that go "up" out of this folder.
                if (url.startsWith(absUrl) && absUrl.length < url.length) continue

                val decoded = runCatching { URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
                val text = link.text().trim()
                val isDir = href.endsWith("/") || text.endsWith("/")
                val extension = decoded.substringBefore('?').substringAfterLast('.', "").lowercase()

                val isSupported = isDir ||
                    extension in RemoteFileItem.VIDEO_EXTENSIONS ||
                    extension in RemoteFileItem.AUDIO_EXTENSIONS ||
                    extension in RemoteFileItem.ARCHIVE_EXTENSIONS ||
                    extension in RemoteFileItem.DOCUMENT_EXTENSIONS
                if (!isSupported) continue

                val displayName = when {
                    text.isNotBlank() && text != "/" && !text.contains("Parent Directory", true) -> text.trimEnd('/')
                    else -> decoded.trimEnd('/').substringAfterLast('/')
                }

                items += RemoteFileItem(
                    name = displayName,
                    path = absUrl,
                    isDirectory = isDir,
                    directUrl = absUrl
                )
            }

            items.sortedWith(
                compareByDescending<RemoteFileItem> { it.isDirectory }
                    .thenBy { it.episodeNumber }
                    .thenBy { it.name.lowercase() }
            )
        }
    }

    /**
     * Downloads [url]. When [resumeFrom] is above zero a Range request is sent; if the
     * server ignores it the file is written again from the start.
     * Returns the number of bytes in the finished file.
     */
    suspend fun download(
        url: String,
        resumeFrom: Long,
        openOutput: (append: Boolean) -> OutputStream,
        onProgress: (downloaded: Long, total: Long, speedBytesPerSec: Long) -> Unit,
        limiter: SpeedLimiter? = null
    ): Long = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
        if (resumeFrom > 0) requestBuilder.header("Range", "bytes=$resumeFrom-")

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.code == 416 && resumeFrom > 0) {
                // "Range not satisfiable": the file is already complete.
                onProgress(resumeFrom, resumeFrom, 0L)
                return@use resumeFrom
            }
            if (!response.isSuccessful) {
                throw IOException("Server returned HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Server sent an empty response")
            val resumed = response.code == 206 && resumeFrom > 0
            val offset = if (resumed) resumeFrom else 0L
            val contentLength = body.contentLength()
            val total = if (contentLength > 0) offset + contentLength else 0L

            val written = openOutput(resumed).use { out ->
                body.byteStream().use { input ->
                    copyWithProgress(input, out, offset, total, onProgress, limiter)
                }
            }
            onProgress(written, maxOf(total, written), 0L)
            written
        }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
