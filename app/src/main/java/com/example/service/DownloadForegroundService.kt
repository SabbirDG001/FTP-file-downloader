package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.appContainer
import com.example.data.model.DownloadStatus
import com.example.data.model.DownloadTask
import com.example.data.model.displayTitle
import com.example.data.model.formattedDownloaded
import com.example.data.model.formattedSpeed
import com.example.data.model.formattedTotal
import com.example.data.model.isActive
import com.example.data.model.progressPercent
import com.example.data.storage.StorageHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps downloads running while the app is in the background and shows their
 * progress in a notification.
 */
class DownloadForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var notificationManager: NotificationManager

    private var lastNotificationTime = 0L
    /** Last status we saw per task, to notice when a download finishes. */
    private val knownStatuses = mutableMapOf<String, DownloadStatus>()

    companion object {
        const val PROGRESS_CHANNEL_ID = "media_downloads_channel"
        const val DONE_CHANNEL_ID = "media_downloads_done_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.service.action.START_DOWNLOAD_SERVICE"
        const val ACTION_PAUSE_ALL = "com.example.service.action.PAUSE_ALL_DOWNLOADS"
        const val ACTION_CANCEL_ALL = "com.example.service.action.CANCEL_ALL_DOWNLOADS"

        fun start(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // Android 12+ does not allow starting from the background; downloads still
                // continue while the app process is alive.
                e.printStackTrace()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground quickly after startForegroundService, whatever the action.
        startInForeground()
        val engine = appContainer.downloadEngine
        when (intent?.action) {
            ACTION_PAUSE_ALL -> engine.pauseAll()
            ACTION_CANCEL_ALL -> engine.cancelAll()
        }
        observeDownloads()
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val notification = buildProgressNotification(null, 0)
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun observeDownloads() {
        if (observeJob?.isActive == true) return
        observeJob = serviceScope.launch {
            appContainer.downloadEngine.tasks.collect { tasks ->
                notifyFinishedDownloads(tasks)

                val active = tasks.filter { it.isActive }
                if (active.isEmpty()) {
                    stopSelfCleanly()
                    return@collect
                }
                val now = System.currentTimeMillis()
                if (now - lastNotificationTime >= 500L) {
                    lastNotificationTime = now
                    val current = active.firstOrNull { it.status == DownloadStatus.DOWNLOADING } ?: active.first()
                    notificationManager.notify(NOTIFICATION_ID, buildProgressNotification(current, active.size))
                }
            }
        }
    }

    private fun notifyFinishedDownloads(tasks: List<DownloadTask>) {
        for (task in tasks) {
            val previous = knownStatuses[task.id]
            knownStatuses[task.id] = task.status
            if (previous == null || previous == task.status) continue
            when (task.status) {
                DownloadStatus.COMPLETED -> showFinishedNotification(task, success = true)
                DownloadStatus.FAILED -> showFinishedNotification(task, success = false)
                else -> Unit
            }
        }
    }

    private fun buildProgressNotification(current: DownloadTask?, activeCount: Int): Notification {
        val builder = NotificationCompat.Builder(this, PROGRESS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(android.R.drawable.ic_media_pause, "Pause all", serviceAction(ACTION_PAUSE_ALL, 1))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel all", serviceAction(ACTION_CANCEL_ALL, 2))

        if (current == null) {
            return builder
                .setContentTitle("Media Downloader")
                .setContentText("Preparing downloads…")
                .setProgress(100, 0, true)
                .build()
        }

        val title = if (activeCount > 1) {
            "${current.displayTitle} (+${activeCount - 1} more)"
        } else {
            current.displayTitle
        }
        val indeterminate = current.totalBytes <= 0L || current.status != DownloadStatus.DOWNLOADING
        val text = when (current.status) {
            DownloadStatus.QUEUED -> "Waiting in queue…"
            DownloadStatus.CONNECTING -> "Connecting…"
            else -> "${current.progressPercent}% • ${current.formattedDownloaded} / ${current.formattedTotal} • ${current.formattedSpeed}"
        }
        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, if (indeterminate) 0 else current.progressPercent, indeterminate)
            .build()
    }

    private fun showFinishedNotification(task: DownloadTask, success: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val tapIntent = if (success && task.outputUri != null) {
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(task.outputUri), StorageHelper.getMimeType(task.fileName))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            PendingIntent.getActivity(
                this, task.id.hashCode(), view,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            openAppIntent()
        }

        val notification = NotificationCompat.Builder(this, DONE_CHANNEL_ID)
            .setSmallIcon(if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (success) "Download complete" else "Download failed")
            .setContentText(if (success) task.displayTitle else "${task.displayTitle}: ${task.errorMessage.orEmpty()}")
            .setAutoCancel(true)
            .setContentIntent(tapIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notificationManager.notify(task.id.hashCode(), notification)
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, DownloadForegroundService::class.java).setAction(action)
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stopSelfCleanly() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(PROGRESS_CHANNEL_ID, "Download progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Live progress of downloads running in the background"
                setShowBadge(false)
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(DONE_CHANNEL_ID, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when a download finishes or fails"
            }
        )
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MediaDownloader:Downloads").apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L) // Safety limit: 4 hours.
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
