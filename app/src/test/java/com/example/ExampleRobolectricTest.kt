package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.DownloadSource
import com.example.data.model.DownloadStatus
import com.example.data.model.DownloadTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    assertEquals("MediaDL", appName)
  }

  @Test
  fun `download engine enqueues cancels and removes tasks`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val engine = context.appContainer.downloadEngine

    val task = DownloadTask(
        id = "test-task-1",
        fileName = "test_episode_01.mkv",
        sourceUrl = "http://example.com/media.mkv",
        source = DownloadSource.HTTP,
        totalBytes = 1048576L
    )

    engine.enqueue(listOf(task))
    val found = engine.tasks.value.find { it.id == "test-task-1" }
    assertNotNull(found)
    // No download folder has been chosen in this test, so the task cannot start.
    assertEquals(DownloadStatus.FAILED, found?.status)

    engine.cancel("test-task-1")
    assertEquals(DownloadStatus.CANCELED, engine.tasks.value.find { it.id == "test-task-1" }?.status)

    engine.remove("test-task-1")
    assertNull(engine.tasks.value.find { it.id == "test-task-1" })
  }
}
