package com.example

import android.app.Application
import android.content.Context
import android.os.Build
import com.example.data.download.DownloadEngine
import com.example.data.download.FtpDownloader
import com.example.data.download.HttpDownloader
import com.example.data.download.SocialDownloader
import com.example.data.local.AppDatabase
import com.example.data.model.DownloadSource
import com.example.data.remote.ftp.FtpClientManager
import com.example.data.remote.http.HttpDirectoryClient
import com.example.data.remote.social.BrowserCookieExporter
import com.example.data.remote.social.YtDlpManager
import com.example.data.settings.SettingsRepository
import com.example.data.storage.StorageHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Creates the app's shared objects once. Screens get them through [appContainer]. */
class AppContainer(context: Context) {
    val settings = SettingsRepository(context)
    val storage = StorageHelper(context)
    private val database = AppDatabase.create(context)
    val httpClient = HttpDirectoryClient()
    val ytDlp = YtDlpManager(context)
    val cookieExporter = BrowserCookieExporter(context)
    val quickPreview = com.example.data.remote.social.QuickPreview()

    val downloadEngine = DownloadEngine(
        appContext = context,
        dao = database.downloadDao(),
        storage = storage,
        settings = settings,
        downloaders = mapOf(
            DownloadSource.FTP to FtpDownloader(storage, FtpClientManager()),
            DownloadSource.HTTP to HttpDownloader(storage, httpClient),
            DownloadSource.SOCIAL to SocialDownloader(context, ytDlp, storage, cookieExporter, settings)
        )
    )
}

class MediaDownloaderApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        if (!isRunningInTests()) prepareSocialEngine()
    }

    /** Unpacks yt-dlp in the background and keeps it fresh (sites change their pages often). */
    private fun prepareSocialEngine() {
        appScope.launch {
            runCatching { container.ytDlp.ensureReady() }
            val settings = container.settings.current
            val weekMs = 7L * 24 * 60 * 60 * 1000
            if (settings.autoUpdateEngine && System.currentTimeMillis() - settings.lastEngineUpdate > weekMs) {
                container.ytDlp.update().onSuccess {
                    container.settings.update { it.copy(lastEngineUpdate = System.currentTimeMillis()) }
                }
            }
        }
    }

    private fun isRunningInTests() = Build.FINGERPRINT == "robolectric"
}

val Context.appContainer: AppContainer
    get() = (applicationContext as MediaDownloaderApplication).container
