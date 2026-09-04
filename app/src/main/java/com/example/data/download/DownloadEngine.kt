package com.example.data.download

import android.content.Context
import android.net.Uri
import com.example.data.ftp.FtpManager
import com.example.data.ftp.HttpMediaManager
import com.example.data.model.DownloadStatus
import com.example.data.model.DownloadTask
import com.example.data.model.FtpServerConfig
import com.example.data.model.ServerProtocol
import com.example.data.storage.StorageHelper
import com.example.service.DownloadForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class DownloadEngine private constructor(private val appContext: Context) {

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storageHelper = StorageHelper(appContext)
    private val ftpManager = FtpManager()
    private val httpMediaManager = HttpMediaManager()

    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    companion object {
        private const val MAX_CONCURRENT_DOWNLOADS = 2

        @Volatile
        private var INSTANCE: DownloadEngine? = null

        fun getInstance(context: Context): DownloadEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    fun enqueueTasks(newTasks: List<DownloadTask>) {
        if (newTasks.isEmpty()) return
        _tasks.update { it + newTasks }

        // Start background foreground service so downloads continue when minimized
        DownloadForegroundService.start(appContext)
        processNextInQueue()
    }

    fun cancelTask(taskId: String) {
        val job = activeJobs.remove(taskId)
        job?.cancel()

        _tasks.update { list ->
            list.map { task ->
                if (task.id == taskId) {
                    task.copy(status = DownloadStatus.CANCELED, speedBytesPerSec = 0L)
                } else task
            }
        }

        processNextInQueue()
        checkAndStopServiceIfIdle()
    }

    fun cancelAll() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()

        _tasks.update { list ->
            list.map { task ->
                if (task.isActive) {
                    task.copy(status = DownloadStatus.CANCELED, speedBytesPerSec = 0L)
                } else task
            }
        }

        DownloadForegroundService.stop(appContext)
    }

    fun retryTask(taskId: String) {
        _tasks.update { list ->
            list.map { task ->
                if (task.id == taskId) {
                    task.copy(
                        status = DownloadStatus.QUEUED,
                        errorMessage = null,
                        downloadedBytes = 0L,
                        speedBytesPerSec = 0L
                    )
                } else task
            }
        }

        DownloadForegroundService.start(appContext)
        processNextInQueue()
    }

    fun removeTask(taskId: String) {
        cancelTask(taskId)
        _tasks.update { list -> list.filter { it.id != taskId } }
    }

    fun clearCompletedTasks() {
        _tasks.update { list ->
            list.filter { it.status != DownloadStatus.COMPLETED && it.status != DownloadStatus.CANCELED }
        }
    }

    @Synchronized
    fun processNextInQueue() {
        val treeUri = storageHelper.getSavedFolderUri() ?: return

        val currentDownloading = _tasks.value.count { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.CONNECTING }
        if (currentDownloading >= MAX_CONCURRENT_DOWNLOADS) return

        val nextTask = _tasks.value.firstOrNull { it.status == DownloadStatus.QUEUED } ?: run {
            checkAndStopServiceIfIdle()
            return
        }

        startTaskExecution(nextTask, treeUri)
    }

    private fun startTaskExecution(task: DownloadTask, treeUri: Uri) {
        val job = engineScope.launch {
            updateTaskStatus(task.id, DownloadStatus.CONNECTING)

            val targetDoc = storageHelper.getOrCreateTargetFile(
                treeUri = treeUri,
                subFolderName = task.subFolder,
                fileName = task.fileName,
                overwrite = true
            )

            if (targetDoc == null) {
                updateTaskFailed(task.id, "Unable to create target file in chosen storage folder.")
                processNextInQueue()
                return@launch
            }

            val outStream = storageHelper.openOutputStream(targetDoc, append = false)
            if (outStream == null) {
                updateTaskFailed(task.id, "Unable to open write stream to file.")
                processNextInQueue()
                return@launch
            }

            updateTaskStatus(task.id, DownloadStatus.DOWNLOADING, outputUri = targetDoc.uri.toString())

            val downloadResult = if (task.protocol == ServerProtocol.FTP) {
                ftpManager.downloadFile(
                    config = FtpServerConfig.SAMONLINE_FTP,
                    remoteFilePath = task.remotePathOrUrl,
                    outputStream = outStream,
                    totalBytesHint = task.totalBytes,
                    onProgress = { downloaded, total, speed ->
                        updateTaskProgress(task.id, downloaded, total, speed)
                    },
                    isCancelled = {
                        val currentStatus = _tasks.value.find { it.id == task.id }?.status
                        currentStatus == DownloadStatus.CANCELED
                    }
                )
            } else {
                httpMediaManager.downloadHttp(
                    url = task.remotePathOrUrl,
                    outputStream = outStream,
                    offsetBytes = 0L,
                    onProgress = { downloaded, total, speed ->
                        updateTaskProgress(task.id, downloaded, total, speed)
                    },
                    isCancelled = {
                        val currentStatus = _tasks.value.find { it.id == task.id }?.status
                        currentStatus == DownloadStatus.CANCELED
                    }
                )
            }

            try {
                outStream.close()
            } catch (_: Exception) {}

            downloadResult.fold(
                onSuccess = {
                    updateTaskStatus(task.id, DownloadStatus.COMPLETED)
                },
                onFailure = { err ->
                    val isCancelled = err is CancellationException ||
                            _tasks.value.find { it.id == task.id }?.status == DownloadStatus.CANCELED
                    if (isCancelled) {
                        updateTaskStatus(task.id, DownloadStatus.CANCELED)
                    } else {
                        updateTaskFailed(task.id, err.localizedMessage ?: "Download failed")
                    }
                }
            )

            activeJobs.remove(task.id)
            processNextInQueue()
        }

        activeJobs[task.id] = job
    }

    private fun updateTaskStatus(taskId: String, status: DownloadStatus, outputUri: String? = null) {
        _tasks.update { list ->
            list.map { task ->
                if (task.id == taskId) {
                    task.copy(
                        status = status,
                        outputUri = outputUri ?: task.outputUri,
                        speedBytesPerSec = if (status == DownloadStatus.COMPLETED || status == DownloadStatus.CANCELED || status == DownloadStatus.FAILED) 0L else task.speedBytesPerSec
                    )
                } else task
            }
        }
    }

    private fun updateTaskProgress(taskId: String, downloaded: Long, total: Long, speed: Long) {
        _tasks.update { list ->
            list.map { task ->
                if (task.id == taskId) {
                    task.copy(
                        downloadedBytes = downloaded,
                        totalBytes = if (total > 0) total else task.totalBytes,
                        speedBytesPerSec = speed,
                        status = DownloadStatus.DOWNLOADING
                    )
                } else task
            }
        }
    }

    private fun updateTaskFailed(taskId: String, error: String) {
        _tasks.update { list ->
            list.map { task ->
                if (task.id == taskId) {
                    task.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = error,
                        speedBytesPerSec = 0L
                    )
                } else task
            }
        }
    }

    private fun checkAndStopServiceIfIdle() {
        val hasActive = _tasks.value.any { it.isActive }
        if (!hasActive) {
            DownloadForegroundService.stop(appContext)
        }
    }
}
