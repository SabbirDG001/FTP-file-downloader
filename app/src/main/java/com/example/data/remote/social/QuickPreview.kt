package com.example.data.remote.social

import com.example.data.model.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Title + thumbnail shown straight away while yt-dlp reads the full details. */
data class PreviewInfo(val title: String, val thumbnailUrl: String, val author: String)

/**
 * Uses the sites' public "oEmbed" endpoints, which answer in well under a second.
 * yt-dlp needs several seconds on a phone, so this makes the sheet feel instant.
 */
class QuickPreview(private val client: OkHttpClient = defaultClient) {

    suspend fun fetch(url: String): PreviewInfo? = withContext(Dispatchers.IO) {
        val endpoint = endpointFor(url) ?: return@withContext null
        val oembedUrl = endpoint.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("url", url)
            ?.addQueryParameter("format", "json")
            ?.build() ?: return@withContext null
        runCatching {
            client.newCall(Request.Builder().url(oembedUrl).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string().orEmpty())
                PreviewInfo(
                    title = json.optString("title"),
                    thumbnailUrl = json.optString("thumbnail_url"),
                    author = json.optString("author_name")
                ).takeIf { it.title.isNotBlank() || it.thumbnailUrl.isNotBlank() }
            }
        }.getOrNull()
    }

    private fun endpointFor(url: String): String? = when (Platform.fromUrl(url)) {
        Platform.YOUTUBE -> "https://www.youtube.com/oembed"
        Platform.VIMEO -> "https://vimeo.com/api/oembed.json"
        Platform.TIKTOK -> "https://www.tiktok.com/oembed"
        Platform.DAILYMOTION -> "https://www.dailymotion.com/services/oembed"
        else -> null
    }

    private companion object {
        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}
