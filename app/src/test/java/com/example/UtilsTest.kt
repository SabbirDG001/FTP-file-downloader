package com.example

import com.example.data.util.ErrorMessages
import com.example.data.model.Platform
import com.example.data.model.RemoteFileItem
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
}
