package com.example.ui.screens.downloads

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.appContainer
import com.example.data.model.DownloadStatus
import com.example.data.model.DownloadTask
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DownloadFilter(val label: String) {
    ALL("All"),
    ACTIVE("In progress"),
    COMPLETED("Completed"),
    FAILED("Failed")
}

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.appContainer
    private val engine = container.downloadEngine
    private val storage = container.storage

    val tasks: StateFlow<List<DownloadTask>> = engine.tasks

    private val _filter = MutableStateFlow(DownloadFilter.ALL)
    val filter: StateFlow<DownloadFilter> = _filter.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun setFilter(filter: DownloadFilter) {
        _filter.value = filter
    }

    fun pause(id: String) = engine.pause(id)
    fun resume(id: String) = engine.resume(id)
    fun retry(id: String) = engine.retry(id)
    fun cancel(id: String) = engine.cancel(id)
    fun remove(id: String, deleteFile: Boolean) = engine.remove(id, deleteFile)
    fun clearFinished() = engine.clearFinished()
    fun pauseAll() = engine.pauseAll()
    fun resumeAll() = engine.resumeAll()

    fun open(task: DownloadTask) {
        val uri = task.outputUri
        if (task.status != DownloadStatus.COMPLETED || uri == null || !storage.openFile(uri, task.fileName)) {
            _messages.tryEmit("No app found to open this file")
        }
    }

    fun share(task: DownloadTask) {
        val uri = task.outputUri ?: return
        if (!storage.shareFile(uri, task.fileName)) _messages.tryEmit("Unable to share this file")
    }

    /** A titled part of the downloads list. */
    data class Group(val title: String, val tasks: List<DownloadTask>)

    companion object {
        /**
         * Downloading (running first, then waiting, then paused, in queue order) at the top,
         * finished downloads (newest first) below, failed / cancelled at the bottom.
         */
        fun groupForDisplay(tasks: List<DownloadTask>): List<Group> {
            val inProgressOrder = listOf(
                DownloadStatus.DOWNLOADING, DownloadStatus.CONNECTING, DownloadStatus.QUEUED, DownloadStatus.PAUSED
            )
            val downloading = tasks
                .filter { it.status in inProgressOrder }
                .sortedWith(compareBy<DownloadTask> { inProgressOrder.indexOf(it.status) }.thenBy { it.createdAt })
            val downloaded = tasks
                .filter { it.status == DownloadStatus.COMPLETED }
                .sortedByDescending { maxOf(it.completedAt, it.createdAt) }
            val failed = tasks
                .filter { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELED }
                .sortedByDescending { it.createdAt }
            return listOf(
                Group("Downloading", downloading),
                Group("Downloaded", downloaded),
                Group("Failed / cancelled", failed)
            ).filter { it.tasks.isNotEmpty() }
        }

        fun matches(task: DownloadTask, filter: DownloadFilter): Boolean = when (filter) {
            DownloadFilter.ALL -> true
            DownloadFilter.ACTIVE -> task.status in setOf(
                DownloadStatus.QUEUED, DownloadStatus.CONNECTING, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED
            )
            DownloadFilter.COMPLETED -> task.status == DownloadStatus.COMPLETED
            DownloadFilter.FAILED -> task.status == DownloadStatus.FAILED || task.status == DownloadStatus.CANCELED
        }
    }
}
