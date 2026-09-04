package com.example.data.ftp

import com.example.data.model.RemoteFileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

class HttpMediaManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        val VIDEO_EXTENSIONS = setOf(
            "mkv", "mp4", "avi", "mov", "wmv", "webm", "flv", "ts", "m4v"
        )
    }

    suspend fun parseDirectoryOrSeason(url: String): Result<List<RemoteFileItem>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP Error: ${response.code} ${response.message}"))
            }

            val html = response.body?.string() ?: ""
            val doc = Jsoup.parse(html, url)
            val links = doc.select("a[href]")

            val items = mutableListOf<RemoteFileItem>()
            val seenUrls = mutableSetOf<String>()

            for (link in links) {
                val href = link.attr("href")
                if (href.isBlank() || href.startsWith("?") || href == "/" || href == "../") continue

                val absUrl = link.absUrl("href")
                if (absUrl.isBlank() || !seenUrls.add(absUrl)) continue

                val decoded = try {
                    URLDecoder.decode(href, "UTF-8")
                } catch (_: Exception) {
                    href
                }

                val cleanText = link.text().trim()
                val isDir = href.endsWith("/") || cleanText.endsWith("/")
                val extension = decoded.substringAfterLast('.', "").lowercase()

                val isVideo = extension in VIDEO_EXTENSIONS
                val isSupportedMedia = isDir || isVideo || extension in setOf("zip", "rar", "iso", "pdf")

                if (isSupportedMedia) {
                    val displayName = when {
                        cleanText.isNotBlank() && cleanText != "/" && !cleanText.contains("Parent Directory", true) -> cleanText.trimEnd('/')
                        else -> decoded.trimEnd('/').substringAfterLast('/')
                    }

                    items.add(
                        RemoteFileItem(
                            name = displayName,
                            path = absUrl,
                            isDirectory = isDir,
                            directUrl = absUrl
                        )
                    )
                }
            }

            // Sort episodes if they are videos
            val sorted = items.sortedWith(
                compareByDescending<RemoteFileItem> { it.isDirectory }
                    .thenBy { it.episodeNumber }
                    .thenBy { it.name.lowercase() }
            )

            Result.success(sorted)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadHttp(
        url: String,
        outputStream: OutputStream,
        offsetBytes: Long = 0L,
        onProgress: (downloaded: Long, total: Long, speedBytesSec: Long) -> Unit,
        isCancelled: () -> Boolean
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")

            if (offsetBytes > 0) {
                reqBuilder.header("Range", "bytes=$offsetBytes-")
            }

            val response = client.newCall(reqBuilder.build()).execute()
            if (!response.isSuccessful && response.code != 206) {
                return@withContext Result.failure(Exception("HTTP response failed: ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty HTTP body"))
            val contentLength = body.contentLength()
            val totalSize = if (contentLength > 0) {
                if (response.code == 206) offsetBytes + contentLength else contentLength
            } else {
                0L
            }

            var downloaded = offsetBytes
            var lastUpdate = System.currentTimeMillis()
            var bytesSinceUpdate = 0L
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int

            val inputStream: InputStream = body.byteStream()
            inputStream.use { stream ->
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    if (isCancelled()) {
                        return@withContext Result.failure(Exception("Download cancelled by user"))
                    }

                    outputStream.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    bytesSinceUpdate += bytesRead

                    val now = System.currentTimeMillis()
                    val diff = now - lastUpdate
                    if (diff >= 400) {
                        val speed = if (diff > 0) (bytesSinceUpdate * 1000) / diff else 0L
                        onProgress(downloaded, if (totalSize > 0) totalSize else downloaded, speed)
                        lastUpdate = now
                        bytesSinceUpdate = 0L
                    }
                }
                outputStream.flush()
            }

            if (isCancelled()) {
                return@withContext Result.failure(Exception("Download cancelled by user"))
            }

            onProgress(downloaded, downloaded, 0L)
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
