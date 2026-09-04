package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.download.DownloadEngine
import com.example.data.model.DownloadStatus
import com.example.data.model.DownloadTask
import com.example.data.model.ServerProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Media Downloader", appName)
  }

  @Test
  fun `download engine enqueues and cancels tasks`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val engine = DownloadEngine.getInstance(context)
    assertNotNull(engine)

    val task = DownloadTask(
        id = "test-task-1",
        fileName = "test_episode_01.mkv",
        remotePathOrUrl = "http://example.com/media.mkv",
        protocol = ServerProtocol.HTTP_WEB,
        totalBytes = 1048576L
    )

    engine.enqueueTasks(listOf(task))
    val found = engine.tasks.value.find { it.id == "test-task-1" }
    assertNotNull(found)

    engine.cancelTask("test-task-1")
    val canceled = engine.tasks.value.find { it.id == "test-task-1" }
    assertEquals(DownloadStatus.CANCELED, canceled?.status)

    engine.removeTask("test-task-1")
    val removed = engine.tasks.value.find { it.id == "test-task-1" }
    assertEquals(null, removed)
  }
}
