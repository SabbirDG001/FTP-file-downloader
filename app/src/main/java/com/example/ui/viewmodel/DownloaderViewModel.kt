package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.download.DownloadEngine
import com.example.data.ftp.FtpManager
import com.example.data.ftp.HttpMediaManager
import com.example.data.model.DownloadStatus
import com.example.data.model.DownloadTask
import com.example.data.model.FtpServerConfig
import com.example.data.model.RemoteFileItem
import com.example.data.model.ServerProtocol
import com.example.data.storage.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

data class DirectUrlState(
    val url: String = "",
    val folderName: String = "",
    val isSeriesMode: Boolean = false,
    val isParsing: Boolean = false,
    val detectedFiles: List<RemoteFileItem> = emptyList(),
    val errorMessage: String? = null
)

class DownloaderViewModel(application: Application) : AndroidViewModel(application) {

    private val storageHelper = StorageHelper(application)
    private val ftpManager = FtpManager()
    private val httpMediaManager = HttpMediaManager()

    // Storage folder state
    private val _folderUri = MutableStateFlow<Uri?>(storageHelper.getSavedFolderUri())
    val folderUri: StateFlow<Uri?> = _folderUri.asStateFlow()

    private val _folderDisplayName = MutableStateFlow(storageHelper.getSavedFolderName())
    val folderDisplayName: StateFlow<String> = _folderDisplayName.asStateFlow()

    // FTP Server connection state
    private val _serverConfig = MutableStateFlow(FtpServerConfig.SAMONLINE_FTP)
    val serverConfig: StateFlow<FtpServerConfig> = _serverConfig.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    // Browser state
    private val _browserState = MutableStateFlow(BrowserUiState())
    val browserState: StateFlow<BrowserUiState> = _browserState.asStateFlow()

    // Direct URL state
    private val _directUrlState = MutableStateFlow(DirectUrlState())
    val directUrlState: StateFlow<DirectUrlState> = _directUrlState.asStateFlow()

    // Background download engine
    private val downloadEngine = DownloadEngine.getInstance(application)
    val tasks: StateFlow<List<DownloadTask>> = downloadEngine.tasks

    // Navigation and dialogs
    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _showServerDialog = MutableStateFlow(false)
    val showServerDialog: StateFlow<Boolean> = _showServerDialog.asStateFlow()

    fun setTab(index: Int) {
        _selectedTab.value = index
    }

    fun setShowServerDialog(show: Boolean) {
        _showServerDialog.value = show
    }

    fun onFolderSelected(uri: Uri) {
        val success = storageHelper.saveFolderUri(uri)
        if (success) {
            _folderUri.value = uri
            _folderDisplayName.value = storageHelper.getSavedFolderName()
            if (!_isConnected.value && !_isConnecting.value) {
                connectServer(_serverConfig.value)
            }
        }
    }

    fun changeFolder() {
        _folderUri.value = null
    }

    fun updateServerConfig(config: FtpServerConfig) {
        _serverConfig.value = config
        connectServer(config)
    }

    fun connectServer(config: FtpServerConfig = _serverConfig.value) {
        viewModelScope.launch {
            _isConnecting.value = true
            _connectionError.value = null
            _serverConfig.value = config

            if (config.protocol == ServerProtocol.FTP) {
                val result = ftpManager.connect(config)
                result.fold(
                    onSuccess = {
                        _isConnected.value = true
                        _isConnecting.value = false
                        navigateTo(config.initialPath)
                    },
                    onFailure = { err ->
                        _isConnected.value = false
                        _isConnecting.value = false
                        _connectionError.value = err.localizedMessage ?: "Failed to connect to FTP"
                    }
                )
            } else {
                // HTTP Web Server
                _isConnected.value = true
                _isConnecting.value = false
                navigateHttp(config.host)
            }
        }
    }

    fun navigateTo(path: String) {
        viewModelScope.launch {
            _browserState.update { it.copy(isLoading = true, errorMessage = null) }
            val cleanPath = if (path.isBlank()) "/" else path
            val result = ftpManager.listFiles(cleanPath)
            result.fold(
                onSuccess = { items ->
                    _browserState.update {
                        it.copy(
                            currentPath = cleanPath,
                            items = items,
                            isLoading = false,
                            selectedItems = emptySet(),
                            errorMessage = null
                        )
                    }
                },
                onFailure = { err ->
                    _browserState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = err.localizedMessage ?: "Failed to list folder contents"
                        )
                    }
                }
            )
        }
    }

    private fun navigateHttp(url: String) {
        viewModelScope.launch {
            _browserState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = httpMediaManager.parseDirectoryOrSeason(url)
            result.fold(
                onSuccess = { items ->
                    _browserState.update {
                        it.copy(
                            currentPath = url,
                            items = items,
                            isLoading = false,
                            selectedItems = emptySet(),
                            errorMessage = null
                        )
                    }
                },
                onFailure = { err ->
                    _browserState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = err.localizedMessage ?: "Failed to load HTTP directory"
                        )
                    }
                }
            )
        }
    }

    fun navigateUp() {
        val current = _browserState.value.currentPath
        if (current == "/" || current.isEmpty()) return

        if (_serverConfig.value.protocol == ServerProtocol.FTP) {
            val parent = current.substringBeforeLast('/', "").ifEmpty { "/" }
            navigateTo(parent)
        } else {
            val trimmed = current.trimEnd('/')
            val parent = trimmed.substringBeforeLast('/', "")
            if (parent.startsWith("http")) {
                navigateHttp("$parent/")
            }
        }
    }

    fun setSearchQuery(query: String) {
        _browserState.update { it.copy(searchQuery = query) }
    }

    fun toggleSelectItem(item: RemoteFileItem) {
        _browserState.update { state ->
            val newSelected = state.selectedItems.toMutableSet()
            if (newSelected.contains(item)) {
                newSelected.remove(item)
            } else {
                newSelected.add(item)
            }
            state.copy(selectedItems = newSelected)
        }
    }

    fun selectAllVideos() {
        _browserState.update { state ->
            val videos = state.items.filter { !it.isDirectory && (it.isVideo || it.isArchive) }
            state.copy(selectedItems = videos.toSet())
        }
    }

    fun clearSelection() {
        _browserState.update { it.copy(selectedItems = emptySet()) }
    }

    fun downloadItem(item: RemoteFileItem, subFolder: String = "") {
        queueDownload(item, subFolder)
        _selectedTab.value = 1 // Switch to Transfers tab
    }

    fun downloadSelected(subFolder: String = "") {
        val selected = _browserState.value.selectedItems.filter { !it.isDirectory }
        if (selected.isEmpty()) return
        val currentPath = _browserState.value.currentPath.trim('/')
        val defaultSub = if (currentPath.isNotEmpty()) currentPath.substringAfterLast('/') else ""
        val targetFolder = subFolder.ifBlank { defaultSub }

        val newTasks = selected.map { item ->
            DownloadTask(
                fileName = item.name,
                remotePathOrUrl = item.path,
                protocol = _serverConfig.value.protocol,
                totalBytes = item.sizeBytes,
                subFolder = targetFolder
            )
        }
        downloadEngine.enqueueTasks(newTasks)
        clearSelection()
        _selectedTab.value = 1 // Switch to Transfers tab
    }

    private fun queueDownload(item: RemoteFileItem, subFolder: String) {
        val currentPath = _browserState.value.currentPath.trim('/')
        val defaultSub = if (currentPath.isNotEmpty()) currentPath.substringAfterLast('/') else ""
        val targetFolder = subFolder.ifBlank { defaultSub }

        val task = DownloadTask(
            fileName = item.name,
            remotePathOrUrl = item.path,
            protocol = _serverConfig.value.protocol,
            totalBytes = item.sizeBytes,
            subFolder = targetFolder
        )
        downloadEngine.enqueueTasks(listOf(task))
    }

    fun cancelTask(taskId: String) {
        downloadEngine.cancelTask(taskId)
    }

    fun retryTask(taskId: String) {
        downloadEngine.retryTask(taskId)
    }

    fun removeTask(taskId: String) {
        downloadEngine.removeTask(taskId)
    }

    fun clearCompletedTasks() {
        downloadEngine.clearCompletedTasks()
    }

    // Direct URL / Season mode
    fun updateDirectUrl(url: String) {
        _directUrlState.update { it.copy(url = url, errorMessage = null) }
    }

    fun updateDirectFolder(folder: String) {
        _directUrlState.update { it.copy(folderName = folder) }
    }

    fun setSeriesMode(isSeries: Boolean) {
        _directUrlState.update { it.copy(isSeriesMode = isSeries) }
    }

    fun parseDirectUrl() {
        val url = _directUrlState.value.url.trim()
        if (url.isEmpty()) return

        viewModelScope.launch {
            _directUrlState.update { it.copy(isParsing = true, errorMessage = null) }

            if (_directUrlState.value.isSeriesMode) {
                // Parse HTML season folder
                val result = httpMediaManager.parseDirectoryOrSeason(url)
                result.fold(
                    onSuccess = { items ->
                        val videos = items.filter { !it.isDirectory && it.isVideo }
                        _directUrlState.update {
                            it.copy(
                                isParsing = false,
                                detectedFiles = videos,
                                errorMessage = if (videos.isEmpty()) "No video files found in season URL" else null
                            )
                        }
                    },
                    onFailure = { err ->
                        _directUrlState.update {
                            it.copy(
                                isParsing = false,
                                errorMessage = "Failed to access season URL: ${err.localizedMessage}"
                            )
                        }
                    }
                )
            } else {
                // Single movie/tutorial file
                val fileName = url.substringAfterLast('/').substringBefore('?').ifEmpty { "download.mkv" }
                val item = RemoteFileItem(
                    name = fileName,
                    path = url,
                    isDirectory = false,
                    directUrl = url
                )
                _directUrlState.update {
                    it.copy(
                        isParsing = false,
                        detectedFiles = listOf(item)
                    )
                }
            }
        }
    }

    fun startDirectDownload() {
        val files = _directUrlState.value.detectedFiles
        val folder = _directUrlState.value.folderName.ifBlank {
            if (_directUrlState.value.isSeriesMode) "Web Series" else "Movies"
        }

        val newTasks = files.map { item ->
            DownloadTask(
                fileName = item.name,
                remotePathOrUrl = item.path,
                protocol = ServerProtocol.HTTP_WEB,
                totalBytes = item.sizeBytes,
                subFolder = folder
            )
        }

        _directUrlState.update { it.copy(detectedFiles = emptyList(), url = "") }
        _selectedTab.value = 1 // Switch to Transfers
        downloadEngine.enqueueTasks(newTasks)
    }

    override fun onCleared() {
        super.onCleared()
        ftpManager.disconnect()
    }
}
