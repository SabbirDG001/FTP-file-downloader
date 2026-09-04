package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.data.download.DownloadEngine
import com.example.data.model.DownloadStatus
import com.example.data.model.DownloadTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DownloadForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main)

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var notificationManager: NotificationManager

    private var isForeground = false
    private var lastNotificationTime = 0L

    companion object {
        const val CHANNEL_ID = "media_downloads_channel"
        const val NOTIFICATION_ID = 1001
        const val COMPLETION_NOTIFICATION_ID = 1002

        const val ACTION_START = "com.example.service.action.START_DOWNLOAD_SERVICE"
        const val ACTION_STOP = "com.example.service.action.STOP_DOWNLOAD_SERVICE"
        const val ACTION_CANCEL_ALL = "com.example.service.action.CANCEL_ALL_DOWNLOADS"

        fun start(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_START
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_CANCEL_ALL -> {
                DownloadEngine.getInstance(applicationContext).cancelAll()
                stopForegroundService()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopForegroundService()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                ensureForegroundStarted()
                observeDownloadTasks()
            }
        }

        return START_STICKY
    }

    private fun ensureForegroundStarted() {
        if (!isForeground) {
            val initialNotification = buildNotification(null, emptyList())
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        initialNotification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIFICATION_ID, initialNotification)
                }
                isForeground = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun observeDownloadTasks() {
        serviceScope.launch {
            DownloadEngine.getInstance(applicationContext).tasks.collect { tasks ->
                val activeTasks = tasks.filter { it.isActive }

                if (activeTasks.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    // Throttle notification updates to at most once per 350ms
                    if (now - lastNotificationTime >= 350L || activeTasks.any { it.status == DownloadStatus.CONNECTING }) {
                        lastNotificationTime = now
                        val currentTask = activeTasks.firstOrNull { it.status == DownloadStatus.DOWNLOADING }
                            ?: activeTasks.firstOrNull()
                        val notification = buildNotification(currentTask, activeTasks)
                        notificationManager.notify(NOTIFICATION_ID, notification)
                    }
                } else {
                    // No more active downloads
                    val completedCount = tasks.count { it.status == DownloadStatus.COMPLETED }
                    if (completedCount > 0) {
                        showCompletionNotification(completedCount)
                    }
                    stopForegroundService()
                }
            }
        }
    }

    private fun buildNotification(currentTask: DownloadTask?, activeTasks: List<DownloadTask>): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelAllIntent = Intent(this, DownloadForegroundService::class.java).apply {
            action = ACTION_CANCEL_ALL
        }
        val cancelAllPendingIntent = PendingIntent.getService(
            this,
            1,
            cancelAllIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val totalActive = activeTasks.size
        val title: String
        val contentText: String
        val isIndeterminate: Boolean
        val progressPercent: Int

        if (currentTask != null) {
            title = if (totalActive > 1) {
                "Downloading: ${currentTask.fileName} (+$totalActive more)"
            } else {
                "Downloading: ${currentTask.fileName}"
            }

            isIndeterminate = currentTask.totalBytes <= 0L || currentTask.status == DownloadStatus.CONNECTING
            progressPercent = if (isIndeterminate) 0 else currentTask.progressPercent

            contentText = if (currentTask.status == DownloadStatus.CONNECTING) {
                "Connecting to media source..."
            } else {
                "${currentTask.progressPercent}% • ${currentTask.formattedDownloaded} / ${currentTask.formattedTotal} • ${currentTask.formattedSpeed}"
            }
        } else {
            title = "Media Downloader"
            contentText = "Preparing downloads..."
            isIndeterminate = true
            progressPercent = 0
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setProgress(100, progressPercent, isIndeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel All",
                cancelAllPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun showCompletionNotification(count: Int) {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (count == 1) "Download Complete" else "$count Downloads Complete"
        val message = "All media files have been saved to your download directory."

        val completionNotif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(contentPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(COMPLETION_NOTIFICATION_ID, completionNotif)
    }

    private fun stopForegroundService() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            isForeground = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Media Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live progress for active media downloads in background"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MediaDownloader:DownloadWakeLock").apply {
                acquire(45 * 60 * 1000L /* 45 minutes max safety lock */)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            wakeLock = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        releaseWakeLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
