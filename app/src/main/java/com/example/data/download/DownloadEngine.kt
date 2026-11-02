package com.example.data.download

import android.content.Context
import com.example.data.local.DownloadDao
import com.example.data.model.DownloadSource
import com.example.data.model.DownloadStatus
import com.example.data.model.DownloadTask
import com.example.data.model.isActive
import com.example.data.model.isRunning
import com.example.data.settings.SettingsRepository
import com.example.data.storage.StorageHelper
import com.example.data.util.ErrorMessages
import com.example.service.DownloadForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The download queue. Runs up to N downloads at once (Settings), supports
 * pause / resume / cancel / retry, and saves every change to the database so the
 * list survives app restarts. Downloads keep going in the background through
 * [DownloadForegroundService].
 */
class DownloadEngine(
    private val appContext: Context,
    private val dao: DownloadDao,
    private val storage: StorageHelper,
    private val settings: SettingsRepository,
    private val downloaders: Map<DownloadSource, MediaDownloader>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dbDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val jobs = ConcurrentHashMap<String, Job>()

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    init {
        scope.launch(dbDispatcher) { restoreSavedTasks() }
        // When "downloads at the same time" is raised, start waiting downloads right away.
        scope.launch {
            settings.settings.map { it.maxConcurrentDownloads }.distinctUntilChanged().collect { schedule() }
        }
    }

    // region Public actions

    fun enqueue(newTasks: List<DownloadTask>) {
        if (newTasks.isEmpty()) return
        _tasks.update { it + newTasks }
        newTasks.forEach { persist(it.id) }
        DownloadForegroundService.start(appContext)
        schedule()
    }

    fun pause(taskId: String) {
        val task = find(taskId) ?: return
        if (!task.isActive) return
        jobs.remove(taskId)?.cancel()
        update(taskId, persist = true) {
            it.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L, etaSeconds = -1L)
        }
        schedule()
    }

    /** Resumes a paused download, or retries a failed / cancelled one. */
    fun resume(taskId: String) {
        val task = find(taskId) ?: return
        if (task.status != DownloadStatus.PAUSED &&
            task.status != DownloadStatus.FAILED &&
            task.status != DownloadStatus.CANCELED
        ) return
        update(taskId, persist = true) {
            it.copy(status = DownloadStatus.QUEUED, errorMessage = null, speedBytesPerSec = 0L)
        }
        DownloadForegroundService.start(appContext)
        schedule()
    }

    fun retry(taskId: String) = resume(taskId)

    fun cancel(taskId: String) {
        val task = find(taskId) ?: return
        if (task.status == DownloadStatus.COMPLETED || task.status == DownloadStatus.CANCELED) return
        val job = jobs.remove(taskId)
        update(taskId, persist = true) {
            it.copy(status = DownloadStatus.CANCELED, speedBytesPerSec = 0L, etaSeconds = -1L)
        }
        scope.launch {
            job?.cancelAndJoin()
            runCatching { downloaders[task.source]?.discardPartial(find(taskId) ?: task) }
            update(taskId, persist = true) { it.copy(downloadedBytes = 0L, outputUri = null) }
        }
        schedule()
    }

    /** Removes a download from the list. Finished files are only deleted when [deleteFile] is true. */
    fun remove(taskId: String, deleteFile: Boolean = false) {
        val task = find(taskId) ?: return
        val job = jobs.remove(taskId)
        _tasks.update { list -> list.filterNot { it.id == taskId } }
        scope.launch {
            job?.cancelAndJoin()
            if (task.status == DownloadStatus.COMPLETED) {
                if (deleteFile) runCatching { storage.deleteFile(task.outputUri) }
            } else {
                runCatching { downloaders[task.source]?.discardPartial(task) }
            }
            launch(dbDispatcher) { runCatching { dao.delete(listOf(taskId)) } }
        }
        schedule()
    }

    /** Clears completed and cancelled entries from the list (files stay on the device). */
    fun clearFinished() {
        val finished = _tasks.value.filter {
            it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.CANCELED
        }.map { it.id }
        if (finished.isEmpty()) return
        _tasks.update { list -> list.filterNot { it.id in finished } }
        scope.launch(dbDispatcher) { runCatching { dao.delete(finished) } }
    }

    fun pauseAll() {
        _tasks.value.filter { it.isActive }.forEach { pause(it.id) }
    }

    fun resumeAll() {
        _tasks.value.filter { it.status == DownloadStatus.PAUSED }.forEach { resume(it.id) }
    }

    fun cancelAll() {
        _tasks.value.filter { it.isActive || it.status == DownloadStatus.PAUSED }.forEach { cancel(it.id) }
    }

    // endregion

    // region Queue

    private fun schedule() = synchronized(this) {
        val queued = _tasks.value.filter { it.status == DownloadStatus.QUEUED && !jobs.containsKey(it.id) }
        if (queued.isEmpty()) return@synchronized

        if (storage.getSavedFolderUri() == null) {
            queued.forEach { task ->
                update(task.id, persist = true) {
                    it.copy(status = DownloadStatus.FAILED, errorMessage = "Choose a download folder first")
                }
            }
            return@synchronized
        }

        val maxRunning = settings.current.maxConcurrentDownloads.coerceIn(1, 5)
        for (task in queued) {
            if (jobs.size >= maxRunning) break
            update(task.id) { it.copy(status = DownloadStatus.CONNECTING, errorMessage = null) }
            launchJob(task.id)
        }
    }

    private fun launchJob(taskId: String) {
        val job = scope.launch(start = CoroutineStart.LAZY) { runTask(taskId) }
        jobs[taskId] = job
        job.invokeOnCompletion {
            jobs.remove(taskId, job)
            schedule()
        }
        job.start()
    }

    private suspend fun runTask(taskId: String) {
        val task = find(taskId) ?: return
        val downloader = downloaders[task.source]
        if (downloader == null) {
            markFailed(taskId, "Unsupported download type")
            return
        }

        val reporter = object : ProgressReporter {
            override fun onFileCreated(outputUri: String, fileName: String) {
                update(taskId, persist = true) {
                    if (it.isActive) it.copy(outputUri = outputUri, fileName = fileName, status = DownloadStatus.DOWNLOADING) else it
                }
            }

            override fun onProgress(downloaded: Long, total: Long, speedBytesPerSec: Long, etaSeconds: Long) {
                update(taskId) {
                    if (it.isRunning) {
                        it.copy(
                            status = DownloadStatus.DOWNLOADING,
                            downloadedBytes = downloaded,
                            totalBytes = if (total > 0) total else it.totalBytes,
                            speedBytesPerSec = speedBytesPerSec,
                            etaSeconds = etaSeconds
                        )
                    } else {
                        it
                    }
                }
            }
        }

        try {
            val outcome = downloader.download(task, reporter)
            update(taskId, persist = true) {
                if (!it.isRunning) return@update it
                it.copy(
                    status = DownloadStatus.COMPLETED,
                    outputUri = outcome.outputUri,
                    fileName = outcome.fileName,
                    downloadedBytes = outcome.sizeBytes,
                    totalBytes = outcome.sizeBytes,
                    speedBytesPerSec = 0L,
                    etaSeconds = -1L,
                    errorMessage = null,
                    completedAt = System.currentTimeMillis()
                )
            }
        } catch (e: CancellationException) {
            // Paused or cancelled by the user; the status was already set.
            throw e
        } catch (e: Throwable) {
            markFailed(taskId, ErrorMessages.friendly(e))
        }
    }

    // endregion

    // region State + persistence

    private suspend fun restoreSavedTasks() {
        val saved = runCatching { dao.getAll() }.getOrDefault(emptyList())
        // Anything that was running when the app closed becomes "paused" so the user can resume it.
        val restored = saved.map {
            if (it.isActive) it.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L, etaSeconds = -1L) else it
        }
        _tasks.update { current -> restored.filter { s -> current.none { it.id == s.id } } + current }
        val changed = restored.filterIndexed { index, task -> task != saved[index] }
        if (changed.isNotEmpty()) runCatching { dao.upsertAll(changed) }
    }

    private fun find(taskId: String): DownloadTask? = _tasks.value.firstOrNull { it.id == taskId }

    private fun update(taskId: String, persist: Boolean = false, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == taskId) transform(it) else it } }
        if (persist) persist(taskId)
    }

    /** Saves the latest version of a task. Writes run one at a time, in order. */
    private fun persist(taskId: String) {
        scope.launch(dbDispatcher) {
            find(taskId)?.let { runCatching { dao.upsert(it) } }
        }
    }

    private fun markFailed(taskId: String, message: String) {
        update(taskId, persist = true) {
            // Don't overwrite a pause/cancel the user made while the download was stopping.
            if (it.isRunning) {
                it.copy(status = DownloadStatus.FAILED, errorMessage = message, speedBytesPerSec = 0L, etaSeconds = -1L)
            } else {
                it
            }
        }
    }

    // endregion
}
