package com.example.ui.screens.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.appContainer
import com.example.data.util.ErrorMessages
import com.example.data.model.DownloadSource
import com.example.data.model.DownloadTask
import com.example.data.model.FormatOption
import com.example.data.model.MediaInfo
import com.example.data.settings.AppSettings
import com.example.data.storage.StorageHelper
import com.example.data.util.LinkUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The "pick a quality" bottom sheet. */
sealed interface MediaSheetState {
    data object Hidden : MediaSheetState
    data class Loading(val url: String) : MediaSheetState
    data class Error(val url: String, val message: String) : MediaSheetState
    data class Ready(val info: MediaInfo, val selectedOptionId: String) : MediaSheetState {
        val selectedOption: FormatOption?
            get() = info.options.firstOrNull { it.id == selectedOptionId }
    }
}

data class HomeUiState(
    val linkInput: String = "",
    /** A link found on the clipboard that we offer to download. */
    val clipboardLink: String? = null,
    val sheet: MediaSheetState = MediaSheetState.Hidden
)

/**
 * Social media downloads: paste / share / clipboard link -> read the post with yt-dlp
 * -> let the user pick a quality -> add it to the download queue. Shared by the Home
 * and Browser screens.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.appContainer
    private val settings: StateFlow<AppSettings> = container.settings.settings

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Short messages for the snackbar. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var fetchJob: Job? = null
    private var lastOfferedClipboardLink: String? = null

    fun onLinkChange(text: String) {
        _state.update { it.copy(linkInput = text) }
    }

    /** Reads the post at [input] (or the text field) and opens the quality sheet. */
    fun fetch(input: String = _state.value.linkInput) {
        val url = LinkUtils.normalize(input)
        if (!LinkUtils.isWebUrl(url)) {
            _messages.tryEmit("Paste a link that starts with http:// or https://")
            return
        }
        _state.update { it.copy(linkInput = url, clipboardLink = null, sheet = MediaSheetState.Loading(url)) }

        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            val cookies = if (settings.value.useBrowserLogin) container.cookieExporter.export() else null
            val result = runCatching { container.ytDlp.fetchInfo(url, cookies) }
            // Ignore the result if the user closed the sheet meanwhile.
            if (_state.value.sheet !is MediaSheetState.Loading) return@launch
            _state.update {
                it.copy(
                    sheet = result.fold(
                        onSuccess = { info -> MediaSheetState.Ready(info, defaultOption(info).id) },
                        onFailure = { error -> MediaSheetState.Error(url, ErrorMessages.friendly(error)) }
                    )
                )
            }
        }
    }

    fun selectOption(optionId: String) {
        _state.update { state ->
            val sheet = state.sheet
            if (sheet is MediaSheetState.Ready) state.copy(sheet = sheet.copy(selectedOptionId = optionId)) else state
        }
    }

    fun dismissSheet() {
        fetchJob?.cancel()
        _state.update { it.copy(sheet = MediaSheetState.Hidden) }
    }

    /** Adds the selected quality to the download queue. */
    fun downloadSelected() {
        val sheet = _state.value.sheet as? MediaSheetState.Ready ?: return
        val option = sheet.selectedOption ?: return
        val info = sheet.info
        val currentSettings = settings.value

        val baseName = StorageHelper.cleanFileName(info.title).take(100).ifBlank { "video" }
        val task = DownloadTask(
            fileName = "$baseName.${option.extension}",
            sourceUrl = info.pageUrl,
            source = DownloadSource.SOCIAL,
            totalBytes = option.estimatedBytes,
            subFolder = if (currentSettings.platformSubfolders) info.platform.folderName else "Social Media",
            title = info.title,
            thumbnailUrl = info.thumbnailUrl,
            platform = info.platform,
            formatSelector = option.selector,
            mediaKind = option.kind,
            audioFormat = option.audioFormat,
            qualityLabel = option.label
        )
        container.downloadEngine.enqueue(listOf(task))
        _state.update { it.copy(sheet = MediaSheetState.Hidden, linkInput = "") }
        _messages.tryEmit("Download started: ${option.label}")
    }

    /** Called when the app gains focus with text on the clipboard. */
    fun onClipboardText(text: String?) {
        if (!settings.value.detectClipboardLinks) return
        val link = LinkUtils.extractUrl(text) ?: return
        if (link == lastOfferedClipboardLink || link == _state.value.linkInput) return
        lastOfferedClipboardLink = link
        _state.update { it.copy(clipboardLink = link) }
    }

    fun acceptClipboardLink() {
        val link = _state.value.clipboardLink ?: return
        fetch(link)
    }

    fun dismissClipboardLink() {
        _state.update { it.copy(clipboardLink = null) }
    }

    /** Text shared to the app from another app (e.g. YouTube's "Share" button). */
    fun handleSharedText(text: String?) {
        val link = LinkUtils.extractUrl(text)
        if (link == null) {
            _messages.tryEmit("No link found in the shared text")
            return
        }
        lastOfferedClipboardLink = link
        fetch(link)
    }

    private fun defaultOption(info: MediaInfo): FormatOption {
        val preferred = settings.value.preferredVideoHeight
        val videos = info.videoOptions
        val byQuality = when {
            videos.isEmpty() -> null
            preferred <= 0 -> videos.first()
            else -> videos.filter { it.height in 1..preferred }.maxByOrNull { it.height }
                ?: videos.minByOrNull { it.height }
        }
        return byQuality
            ?: info.audioOptions.firstOrNull { it.audioFormat == settings.value.preferredAudioFormat }
            ?: info.options.first()
    }
}
