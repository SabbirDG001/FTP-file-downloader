package com.example.ui.screens.servers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.appContainer
import com.example.data.model.DownloadSource
import com.example.data.model.DownloadTask
import com.example.data.model.FtpServerConfig
import com.example.data.model.RemoteFileItem
import com.example.data.model.ServerProtocol
import com.example.data.remote.ftp.FtpClientManager
import com.example.data.util.LinkUtils
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowserUiState(
    val currentPath: String = "/",
    val items: List<RemoteFileItem> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val selectedItems: Set<RemoteFileItem> = emptySet(),
    val searchQuery: String = ""
)

data class ConnectionState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val error: String? = null
)

data class DirectUrlState(
    val url: String = "",
    val folderName: String = "",
    val isSeriesMode: Boolean = false,
    val isParsing: Boolean = false,
    val detectedFiles: List<RemoteFileItem> = emptyList(),
    val errorMessage: String? = null
)

/**
 * The "Servers" tab: browse an FTP server or an HTTP directory listing, and the
 * direct link / whole season downloader.
 */
class ServersViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.appContainer
    private val ftp = FtpClientManager()
    private val http = container.httpClient
    private val engine = container.downloadEngine

    val serverConfig: StateFlow<FtpServerConfig> = container.settings.settings
        .map { it.server }
        .stateIn(viewModelScope, SharingStarted.Eagerly, container.settings.current.server)

    private val _connection = MutableStateFlow(ConnectionState())
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _browser = MutableStateFlow(BrowserUiState())
    val browser: StateFlow<BrowserUiState> = _browser.asStateFlow()

    private val _directUrl = MutableStateFlow(DirectUrlState())
    val directUrl: StateFlow<DirectUrlState> = _directUrl.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // region Server browser

    fun saveServerAndConnect(config: FtpServerConfig) {
        container.settings.update { it.copy(server = config) }
        connect(config)
    }

    fun connect(config: FtpServerConfig = serverConfig.value) {
        viewModelScope.launch {
            _connection.value = ConnectionState(isConnecting = true)
            if (config.protocol == ServerProtocol.FTP) {
                ftp.connect(config).fold(
                    onSuccess = {
                        _connection.value = ConnectionState(isConnected = true)
                        navigateTo(config.initialPath)
                    },
                    onFailure = { err ->
                        _connection.value = ConnectionState(error = err.localizedMessage ?: "Failed to connect to FTP")
                    }
                )
            } else {
                _connection.value = ConnectionState(isConnected = true)
                navigateTo(LinkUtils.normalize(config.host).let { if (it.endsWith("/")) it else "$it/" })
            }
        }
    }

    /** Opens a folder: an FTP path or an HTTP folder URL depending on the server type. */
    fun navigateTo(path: String) {
        viewModelScope.launch {
            _browser.update { it.copy(isLoading = true, errorMessage = null) }
            val target = path.ifBlank { "/" }
            val result = if (isHttpServer()) http.listDirectory(target) else ftp.listFiles(target)
            result.fold(
                onSuccess = { items ->
                    _browser.update {
                        it.copy(
                            currentPath = target,
                            items = items,
                            isLoading = false,
                            selectedItems = emptySet(),
                            searchQuery = "",
                            errorMessage = null
                        )
                    }
                },
                onFailure = { err ->
                    _browser.update {
                        it.copy(isLoading = false, errorMessage = err.localizedMessage ?: "Failed to open folder")
                    }
                }
            )
        }
    }

    fun canNavigateUp(): Boolean {
        val current = _browser.value.currentPath
        if (!_connection.value.isConnected) return false
        return if (isHttpServer()) parentHttpUrl(current) != null else current != "/" && current.isNotEmpty()
    }

    fun navigateUp() {
        val current = _browser.value.currentPath
        if (isHttpServer()) {
            parentHttpUrl(current)?.let { navigateTo(it) }
        } else if (current != "/" && current.isNotEmpty()) {
            navigateTo(current.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" })
        }
    }

    fun setSearchQuery(query: String) = _browser.update { it.copy(searchQuery = query) }

    fun toggleSelect(item: RemoteFileItem) = _browser.update { state ->
        val selected = state.selectedItems.toMutableSet()
        if (!selected.add(item)) selected.remove(item)
        state.copy(selectedItems = selected)
    }

    fun selectAllVideos() = _browser.update { state ->
        state.copy(selectedItems = state.items.filter { !it.isDirectory && (it.isVideo || it.isArchive) }.toSet())
    }

    fun clearSelection() = _browser.update { it.copy(selectedItems = emptySet()) }

    fun downloadItem(item: RemoteFileItem) = queueFromBrowser(listOf(item))

    fun downloadSelected() {
        queueFromBrowser(_browser.value.selectedItems.filter { !it.isDirectory })
        clearSelection()
    }

    private fun queueFromBrowser(items: List<RemoteFileItem>) {
        if (items.isEmpty()) return
        val config = serverConfig.value
        // Save into a folder named after the current server folder, e.g. "Season 1".
        val folder = currentFolderName()
        val tasks = items.map { item ->
            if (isHttpServer()) {
                DownloadTask(
                    fileName = item.name,
                    sourceUrl = item.directUrl ?: item.path,
                    source = DownloadSource.HTTP,
                    totalBytes = item.sizeBytes,
                    subFolder = folder
                )
            } else {
                DownloadTask(
                    fileName = item.name,
                    sourceUrl = item.path,
                    source = DownloadSource.FTP,
                    totalBytes = item.sizeBytes,
                    subFolder = folder,
                    ftpHost = config.host,
                    ftpPort = config.port,
                    ftpUsername = config.username,
                    ftpPassword = config.password,
                    ftpPassive = config.isPassiveMode
                )
            }
        }
        engine.enqueue(tasks)
        _messages.tryEmit(if (tasks.size == 1) "Added to downloads" else "${tasks.size} files added to downloads")
    }

    private fun currentFolderName(): String {
        val path = _browser.value.currentPath
        val raw = if (isHttpServer()) {
            path.trimEnd('/').substringAfterLast('/').let { java.net.URLDecoder.decode(it, "UTF-8") }
        } else {
            path.trim('/').substringAfterLast('/')
        }
        return if (raw.contains(':')) "" else raw
    }

    private fun isHttpServer() = serverConfig.value.protocol == ServerProtocol.HTTP_WEB

    private fun parentHttpUrl(url: String): String? {
        val trimmed = url.trimEnd('/')
        val afterScheme = trimmed.substringAfter("://", "")
        if (!afterScheme.contains('/')) return null // Already at the site root.
        return trimmed.substringBeforeLast('/') + "/"
    }

    // endregion

    // region Direct link / season

    fun updateDirectUrl(url: String) = _directUrl.update { it.copy(url = url, errorMessage = null) }

    fun updateDirectFolder(folder: String) = _directUrl.update { it.copy(folderName = folder) }

    fun setSeriesMode(isSeries: Boolean) = _directUrl.update {
        it.copy(isSeriesMode = isSeries, detectedFiles = emptyList(), errorMessage = null)
    }

    fun parseDirectUrl() {
        val url = LinkUtils.normalize(_directUrl.value.url)
        if (url.isEmpty()) return

        if (!_directUrl.value.isSeriesMode) {
            val fileName = java.net.URLDecoder.decode(
                url.substringBefore('?').substringAfterLast('/'), "UTF-8"
            ).ifEmpty { "download.mkv" }
            _directUrl.update {
                it.copy(detectedFiles = listOf(RemoteFileItem(fileName, url, false, directUrl = url)), errorMessage = null)
            }
            return
        }

        viewModelScope.launch {
            _directUrl.update { it.copy(isParsing = true, errorMessage = null) }
            http.listDirectory(url).fold(
                onSuccess = { items ->
                    val videos = items.filter { !it.isDirectory && it.isVideo }
                    _directUrl.update {
                        it.copy(
                            isParsing = false,
                            detectedFiles = videos,
                            errorMessage = if (videos.isEmpty()) "No video files found at this season URL" else null
                        )
                    }
                },
                onFailure = { err ->
                    _directUrl.update {
                        it.copy(isParsing = false, errorMessage = "Failed to open season URL: ${err.localizedMessage}")
                    }
                }
            )
        }
    }

    fun startDirectDownload() {
        val state = _directUrl.value
        if (state.detectedFiles.isEmpty()) return
        val folder = state.folderName.ifBlank { if (state.isSeriesMode) "Web Series" else "Movies" }
        val tasks = state.detectedFiles.map { item ->
            DownloadTask(
                fileName = item.name,
                sourceUrl = item.directUrl ?: item.path,
                source = DownloadSource.HTTP,
                totalBytes = item.sizeBytes,
                subFolder = folder
            )
        }
        engine.enqueue(tasks)
        _directUrl.update { it.copy(detectedFiles = emptyList(), url = "") }
        _messages.tryEmit(if (tasks.size == 1) "Added to downloads" else "${tasks.size} episodes added to downloads")
    }

    // endregion

    override fun onCleared() {
        ftp.disconnect()
    }
}
