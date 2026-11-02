package com.example

import com.example.data.util.ErrorMessages
import com.example.data.model.FetchResult
import com.example.data.model.Platform
import com.example.data.model.RemoteFileItem
import com.example.data.remote.social.YtDlpJsonParser
import com.example.data.remote.social.YtDlpProgress
import com.example.data.util.Formatters
import com.example.data.util.LinkUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

/** Plain JVM tests for the parsing helpers (no Android needed). */
class UtilsTest {

  @Test
  fun `extracts link from shared text`() {
    assertEquals(
        "https://youtu.be/dQw4w9WgXcQ",
        LinkUtils.extractUrl("Watch this! https://youtu.be/dQw4w9WgXcQ")
    )
    assertEquals(
        "https://www.instagram.com/reel/abc123/",
        LinkUtils.extractUrl("https://www.instagram.com/reel/abc123/.")
    )
    assertNull(LinkUtils.extractUrl("no link here"))
    assertNull(LinkUtils.extractUrl(null))
  }

  @Test
  fun `normalizes links without a scheme`() {
    assertEquals("https://youtube.com/watch?v=1", LinkUtils.normalize("youtube.com/watch?v=1"))
    assertEquals("http://172.16.50.4/Movies/", LinkUtils.normalize("  http://172.16.50.4/Movies/ "))
    assertTrue(LinkUtils.isWebUrl("HTTPS://example.com"))
    assertFalse(LinkUtils.isWebUrl("ftp://example.com"))
  }

  @Test
  fun `detects platforms from links`() {
    assertEquals(Platform.YOUTUBE, Platform.fromUrl("https://www.youtube.com/watch?v=abc"))
    assertEquals(Platform.YOUTUBE, Platform.fromUrl("https://youtu.be/abc"))
    assertEquals(Platform.YOUTUBE, Platform.fromUrl("https://m.youtube.com/shorts/abc"))
    assertEquals(Platform.FACEBOOK, Platform.fromUrl("https://fb.watch/xyz/"))
    assertEquals(Platform.FACEBOOK, Platform.fromUrl("https://m.facebook.com/watch/?v=1"))
    assertEquals(Platform.INSTAGRAM, Platform.fromUrl("https://www.instagram.com/p/abc/"))
    assertEquals(Platform.TIKTOK, Platform.fromUrl("https://vm.tiktok.com/abc"))
    assertEquals(Platform.TWITTER, Platform.fromUrl("https://x.com/user/status/1"))
    assertEquals(Platform.OTHER, Platform.fromUrl("https://notyoutube.com/video"))
    assertEquals(Platform.OTHER, Platform.fromUrl("not a url"))
  }

  @Test
  fun `parses yt-dlp progress lines`() {
    val progress = YtDlpProgress.parse(
        45.3f, 31L, "[download]  45.3% of ~ 120.50MiB at    2.10MiB/s ETA 00:31 (frag 3/10)"
    )
    assertEquals(45.3f, progress.percent, 0.01f)
    assertEquals((120.50 * 1024 * 1024).toLong(), progress.totalBytes)
    assertEquals((2.10 * 1024 * 1024).toLong(), progress.speedBytesPerSec)
    assertFalse(progress.isPostProcessing)

    val merging = YtDlpProgress.parse(100f, 0L, "[Merger] Merging formats into \"video.mp4\"")
    assertTrue(merging.isPostProcessing)
    assertEquals(0L, merging.totalBytes)
  }

  @Test
  fun `formats sizes and durations`() {
    assertEquals("0 B", Formatters.bytes(0))
    assertEquals("1.5 KB", Formatters.bytes(1536))
    assertEquals("1.00 GB", Formatters.bytes(1024L * 1024 * 1024))
    assertEquals("1:15", Formatters.duration(75))
    assertEquals("1:02:05", Formatters.duration(3725))
  }

  @Test
  fun `sorts episodes by number`() {
    val names = listOf("Show.S01E10.mkv", "Show.S01E02.mkv", "Show.S01E01.mkv")
    val sorted = names.map { RemoteFileItem(it, it, false) }.sortedBy { it.episodeNumber }.map { it.name }
    assertEquals(listOf("Show.S01E01.mkv", "Show.S01E02.mkv", "Show.S01E10.mkv"), sorted)
  }

  @Test
  fun `turns errors into readable messages`() {
    val ytError = Exception("some output\nERROR: [instagram] abc: This content is private\n")
    assertEquals(
        "[instagram] abc: This content is private — log in to the site in the Browser tab, then retry",
        ErrorMessages.friendly(ytError)
    )
    assertEquals(
        "No internet connection, or the server could not be found",
        ErrorMessages.friendly(UnknownHostException("x"))
    )
  }

  @Test
  fun `reads a playlist from yt-dlp json`() {
    val json = """
      {"_type":"playlist","title":"My Mix","uploader":"Someone","webpage_url":"https://www.youtube.com/playlist?list=PL1",
       "entries":[
         {"id":"a1","title":"First","url":"https://www.youtube.com/watch?v=a1","duration":61,
          "thumbnails":[{"url":"https://i.ytimg.com/a1/big.jpg","width":1280},{"url":"https://i.ytimg.com/a1/mid.jpg","width":480}]},
         {"id":"b2","title":"[Deleted video]","url":"https://www.youtube.com/watch?v=b2"},
         {"id":"c3","title":"Third","duration":null}
       ]}
    """.trimIndent()
    val result = YtDlpJsonParser.parse(json, "https://www.youtube.com/playlist?list=PL1")
    assertTrue(result is FetchResult.Playlist)
    val info = (result as FetchResult.Playlist).info
    assertEquals("My Mix", info.title)
    assertEquals(Platform.YOUTUBE, info.platform)
    assertEquals(listOf("a1", "c3"), info.entries.map { it.id })
    assertEquals("https://i.ytimg.com/a1/mid.jpg", info.entries[0].thumbnailUrl)
    assertEquals(61, info.entries[0].durationSeconds)
    assertEquals("https://www.youtube.com/watch?v=c3", info.entries[1].url)
  }

  @Test
  fun `reads a single video with qualities from yt-dlp json`() {
    val json = """
      [debug] noise before json
      {"id":"x","title":"Clip","uploader":"Me","duration":90.5,"thumbnail":"https://t/x.jpg",
       "webpage_url":"https://www.youtube.com/watch?v=x",
       "formats":[
         {"format_id":"140","vcodec":"none","acodec":"mp4a","abr":128,"filesize":1000},
         {"format_id":"137","vcodec":"avc1","acodec":"none","height":1080,"tbr":4000,"filesize":50000,"fps":30},
         {"format_id":"18","vcodec":"avc1","acodec":"mp4a","height":360,"tbr":500,"filesize_approx":7000}
       ]}
    """.trimIndent()
    val result = YtDlpJsonParser.parse(json, "https://youtu.be/x?list=PL9")
    val info = (result as FetchResult.Video).info
    assertEquals("Clip", info.title)
    assertEquals(90, info.durationSeconds)
    assertEquals(listOf("1080p", "360p", "MP3", "M4A"), info.options.map { it.label })
    assertEquals(51000L, info.options[0].estimatedBytes) // video + separate audio
    assertEquals(7000L, info.options[1].estimatedBytes)  // already has audio
    assertEquals("https://youtu.be/x?list=PL9", info.playlistUrl)
  }

  @Test
  fun `recognises playlist-only links`() {
    assertTrue(YtDlpJsonParser.isPlaylistOnlyUrl("https://www.youtube.com/playlist?list=PL1"))
    assertTrue(YtDlpJsonParser.isPlaylistOnlyUrl("https://m.youtube.com/playlist?list=PL1&si=z"))
    assertFalse(YtDlpJsonParser.isPlaylistOnlyUrl("https://www.youtube.com/watch?v=a&list=PL1"))
    assertFalse(YtDlpJsonParser.isPlaylistOnlyUrl("https://youtu.be/a?list=PL1"))
    assertNull(YtDlpJsonParser.playlistUrlFor("https://www.youtube.com/watch?v=a"))
    assertEquals(listOf("Best", "1080p", "720p", "480p", "360p", "MP3", "M4A"), YtDlpJsonParser.playlistOptions().map { it.label })
  }

  @Test
  fun `browser user agent hides the in-app WebView markers`() {
    val webView = "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/UQ1A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/124.0.6367.82 Mobile Safari/537.36"
    assertEquals(
        "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/UQ1A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36",
        LinkUtils.browserUserAgent(webView)
    )
  }
}
