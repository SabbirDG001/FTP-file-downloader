package com.example.data.settings

import android.content.Context
import androidx.core.content.edit
import com.example.data.model.FtpServerConfig
import com.example.data.model.ServerProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    /** How many files download at the same time. */
    val maxConcurrentDownloads: Int = 2,
    /** Quality picked by default in the format sheet (0 = best available). */
    val preferredVideoHeight: Int = 720,
    /** "mp3" or "m4a". */
    val preferredAudioFormat: String = "mp3",
    /** Save social media downloads into "YouTube", "Facebook", ... sub folders. */
    val platformSubfolders: Boolean = true,
    /** Pass the in-app browser's login cookies to yt-dlp (needed for some Instagram/Facebook posts). */
    val useBrowserLogin: Boolean = true,
    /** Offer to download a link copied to the clipboard. */
    val detectClipboardLinks: Boolean = true,
    /** Update yt-dlp in the background about once a week. */
    val autoUpdateEngine: Boolean = true,
    val lastEngineUpdate: Long = 0L,
    /** Server shown in the "Servers" tab. */
    val server: FtpServerConfig = FtpServerConfig.SAMONLINE_FTP
)

/** Small key/value settings stored in SharedPreferences and exposed as a flow. */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        save(updated)
        _settings.value = updated
    }

    private fun load(): AppSettings {
        val defaults = AppSettings()
        val defaultServer = defaults.server
        return AppSettings(
            maxConcurrentDownloads = prefs.getInt(KEY_MAX_CONCURRENT, defaults.maxConcurrentDownloads),
            preferredVideoHeight = prefs.getInt(KEY_VIDEO_HEIGHT, defaults.preferredVideoHeight),
            preferredAudioFormat = prefs.getString(KEY_AUDIO_FORMAT, null) ?: defaults.preferredAudioFormat,
            platformSubfolders = prefs.getBoolean(KEY_PLATFORM_FOLDERS, defaults.platformSubfolders),
            useBrowserLogin = prefs.getBoolean(KEY_BROWSER_LOGIN, defaults.useBrowserLogin),
            detectClipboardLinks = prefs.getBoolean(KEY_CLIPBOARD, defaults.detectClipboardLinks),
            autoUpdateEngine = prefs.getBoolean(KEY_AUTO_UPDATE, defaults.autoUpdateEngine),
            lastEngineUpdate = prefs.getLong(KEY_LAST_UPDATE, 0L),
            server = FtpServerConfig(
                name = prefs.getString(KEY_SERVER_NAME, null) ?: defaultServer.name,
                host = prefs.getString(KEY_SERVER_HOST, null) ?: defaultServer.host,
                port = prefs.getInt(KEY_SERVER_PORT, defaultServer.port),
                username = prefs.getString(KEY_SERVER_USER, null) ?: defaultServer.username,
                password = prefs.getString(KEY_SERVER_PASSWORD, null) ?: defaultServer.password,
                isPassiveMode = prefs.getBoolean(KEY_SERVER_PASSIVE, defaultServer.isPassiveMode),
                protocol = prefs.getString(KEY_SERVER_PROTOCOL, null)
                    ?.let { name -> ServerProtocol.entries.firstOrNull { it.name == name } }
                    ?: defaultServer.protocol,
                initialPath = prefs.getString(KEY_SERVER_PATH, null) ?: defaultServer.initialPath
            )
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit {
            putInt(KEY_MAX_CONCURRENT, s.maxConcurrentDownloads)
            putInt(KEY_VIDEO_HEIGHT, s.preferredVideoHeight)
            putString(KEY_AUDIO_FORMAT, s.preferredAudioFormat)
            putBoolean(KEY_PLATFORM_FOLDERS, s.platformSubfolders)
            putBoolean(KEY_BROWSER_LOGIN, s.useBrowserLogin)
            putBoolean(KEY_CLIPBOARD, s.detectClipboardLinks)
            putBoolean(KEY_AUTO_UPDATE, s.autoUpdateEngine)
            putLong(KEY_LAST_UPDATE, s.lastEngineUpdate)
            putString(KEY_SERVER_NAME, s.server.name)
            putString(KEY_SERVER_HOST, s.server.host)
            putInt(KEY_SERVER_PORT, s.server.port)
            putString(KEY_SERVER_USER, s.server.username)
            putString(KEY_SERVER_PASSWORD, s.server.password)
            putBoolean(KEY_SERVER_PASSIVE, s.server.isPassiveMode)
            putString(KEY_SERVER_PROTOCOL, s.server.protocol.name)
            putString(KEY_SERVER_PATH, s.server.initialPath)
        }
    }

    private companion object {
        const val KEY_MAX_CONCURRENT = "max_concurrent"
        const val KEY_VIDEO_HEIGHT = "video_height"
        const val KEY_AUDIO_FORMAT = "audio_format"
        const val KEY_PLATFORM_FOLDERS = "platform_folders"
        const val KEY_BROWSER_LOGIN = "browser_login"
        const val KEY_CLIPBOARD = "clipboard_links"
        const val KEY_AUTO_UPDATE = "auto_update_engine"
        const val KEY_LAST_UPDATE = "last_engine_update"
        const val KEY_SERVER_NAME = "server_name"
        const val KEY_SERVER_HOST = "server_host"
        const val KEY_SERVER_PORT = "server_port"
        const val KEY_SERVER_USER = "server_user"
        const val KEY_SERVER_PASSWORD = "server_password"
        const val KEY_SERVER_PASSIVE = "server_passive"
        const val KEY_SERVER_PROTOCOL = "server_protocol"
        const val KEY_SERVER_PATH = "server_path"
    }
}
