package com.example.ui.screens.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.appContainer
import com.example.data.model.DownloadSource
import com.example.data.model.DownloadTask
import com.example.data.model.FetchResult
import com.example.data.model.FormatOption
import com.example.data.model.MediaInfo
import com.example.data.model.PlaylistInfo
import com.example.data.remote.social.PreviewInfo
import com.example.data.remote.social.YtDlpJsonParser
import com.example.data.settings.AppSettings
import com.example.data.storage.StorageHelper
import com.example.data.util.ErrorMessages
import com.example.data.util.LinkUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The bottom sheet for a link: loading, error, one video, or a playlist. */
sealed interface MediaSheetState {
    data object Hidden : MediaSheetState

    /** [preview] appears after about a second, before the full details are ready. */
    data class Loading(val url: String, val isPlaylist: Boolean, val preview: PreviewInfo? = null) : MediaSheetState

    data class Error(val url: String, val message: String, val wholePlaylist: Boolean) : MediaSheetState

    /**
     * @param provisional true while showing the standard quality list because reading the
     * link is taking long; the real qualities replace it when they arrive.
     */
    data class Ready(
        val info: MediaInfo,
        val selectedOptionId: String,
        val provisional: Boolean = false
    ) : MediaSheetState {
        val selectedOption: FormatOption?
            get() = info.options.firstOrNull { it.id == selectedOptionId }
    }

    /** A playlist: the user ticks the videos they want and picks one quality for all. */
    data class Playlist(
        val info: PlaylistInfo,
        val selectedIds: Set<String>,
        val options: List<FormatOption>,
        val selectedOptionId: String,
        /** Name of the new folder created inside the download folder. */
        val newFolderName: String,
        /** A folder picked anywhere on the phone instead; null = use [newFolderName]. */
        val pickedFolderUri: String? = null,
        val pickedFolderName: String? = null
    ) : MediaSheetState {
        val selectedOption: FormatOption?
            get() = options.firstOrNull { it.id == selectedOptionId }
    }
}

data class HomeUiState(
    val linkInput: String = "",
    /** A link found on the clipboard that we offer to download. */
    val clipboardLink: String? = null,
    val sheet: MediaSheetState = MediaSheetState.Hidden
)

/**
 * Social media downloads: paste / share / clipboard link -> read it with yt-dlp ->
 * pick a quality (and videos, for a playlist) -> add to the download queue.
 * Shared by the Home and Browser screens.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        /** After this long, show the standard qualities instead of waiting. */
        const val SLOW_READ_FALLBACK_MS = 15_000L
    }

    private val container = application.appContainer
    private val settings: StateFlow<AppSettings> = container.settings.settings

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Short messages for the snackbar. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var sheetJob: Job? = null
    private var lastOfferedClipboardLink: String? = null

    /**
     * Reads that are running or finished, by link. A copied link starts reading in the
     * background straight away, so it's often ready by the time the user taps Download.
     */
    private val reads = object : LinkedHashMap<String, Deferred<FetchResult>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Deferred<FetchResult>>?) = size > 20
    }

    fun onLinkChange(text: String) {
        _state.update { it.copy(linkInput = text) }
    }

    /** Reads the link at [input] (or the text field) and opens the sheet. */
    fun fetch(input: String = _state.value.linkInput, wholePlaylist: Boolean = false) {
        val url = LinkUtils.normalize(input)
        if (!LinkUtils.isWebUrl(url)) {
            _messages.tryEmit("Paste a link that starts with http:// or https://")
            return
        }
        val isPlaylist = wholePlaylist || YtDlpJsonParser.isPlaylistOnlyUrl(url)
        _state.update {
            it.copy(linkInput = url, clipboardLink = null, sheet = MediaSheetState.Loading(url, isPlaylist))
        }

        sheetJob?.cancel()
        sheetJob = viewModelScope.launch {
            // Quick title + thumbnail while the full read runs.
            launch {
                val preview = container.quickPreview.fetch(url) ?: return@launch
                _state.update { state ->
                    val sheet = state.sheet
                    if (sheet is MediaSheetState.Loading && sheet.url == url) state.copy(sheet = sheet.copy(preview = preview)) else state
                }
            }

            // Don't keep the user waiting: if reading a single video takes long, offer the
            // standard qualities so they can download straight away.
            val fallbackTimer = if (!isPlaylist) launch {
                delay(SLOW_READ_FALLBACK_MS)
                _state.update { state ->
                    val sheet = state.sheet
                    if (sheet is MediaSheetState.Loading && sheet.url == url) {
                        state.copy(sheet = provisionalSheet(url, sheet.preview))
                    } else {
                        state
                    }
                }
            } else null

            val result = try {
                Result.success(read(url, wholePlaylist).await())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                forget(url, wholePlaylist)
                Result.failure(e)
            }

            fallbackTimer?.cancel()

            val current = _state.value.sheet
            if (current is MediaSheetState.Ready && current.provisional && current.info.pageUrl == url) {
                // The real qualities arrived while the standard list was showing.
                result.onSuccess { fetched ->
                    val real = sheetFor(fetched)
                    _state.update { state ->
                        if (state.sheet != current) return@update state
                        val keep = (real as? MediaSheetState.Ready)?.info?.options?.any { it.id == current.selectedOptionId } == true
                        state.copy(
                            sheet = if (real is MediaSheetState.Ready && keep) real.copy(selectedOptionId = current.selectedOptionId) else real
                        )
                    }
                }
                return@launch
            }

            if (current !is MediaSheetState.Loading) return@launch
            _state.update {
                it.copy(
                    sheet = result.fold(
                        onSuccess = { fetched -> sheetFor(fetched) },
                        onFailure = { error -> MediaSheetState.Error(url, ErrorMessages.friendly(error), wholePlaylist) }
                    )
                )
            }
        }
    }

    /** From a single video that belongs to a playlist: switch to choosing from the whole playlist. */
    fun openWholePlaylist() {
        val sheet = _state.value.sheet as? MediaSheetState.Ready ?: return
        val playlistUrl = sheet.info.playlistUrl ?: return
        fetch(playlistUrl, wholePlaylist = true)
    }

    fun retry() {
        val sheet = _state.value.sheet as? MediaSheetState.Error ?: return
        fetch(sheet.url, sheet.wholePlaylist)
    }

    fun selectOption(optionId: String) {
        _state.update { state ->
            when (val sheet = state.sheet) {
                is MediaSheetState.Ready -> state.copy(sheet = sheet.copy(selectedOptionId = optionId))
                is MediaSheetState.Playlist -> state.copy(sheet = sheet.copy(selectedOptionId = optionId))
                else -> state
            }
        }
    }

    // region Playlist selection

    fun togglePlaylistEntry(id: String) = updatePlaylist { sheet ->
        val selected = sheet.selectedIds.toMutableSet()
        if (!selected.add(id)) selected.remove(id)
        sheet.copy(selectedIds = selected)
    }

    fun selectAllPlaylistEntries() = updatePlaylist { it.copy(selectedIds = it.info.entries.map { e -> e.id }.toSet()) }

    fun clearPlaylistSelection() = updatePlaylist { it.copy(selectedIds = emptySet()) }

    /** "New folder" name typed by the user. */
    fun setPlaylistFolderName(name: String) = updatePlaylist {
        it.copy(newFolderName = name, pickedFolderUri = null, pickedFolderName = null)
    }

    /** A folder chosen with the system folder picker (anywhere on the phone or SD card). */
    fun setPlaylistPickedFolder(uri: String, name: String) = updatePlaylist {
        it.copy(pickedFolderUri = uri, pickedFolderName = name)
    }

    /** Go back to saving into a new folder inside the download folder. */
    fun usePlaylistNewFolder() = updatePlaylist { it.copy(pickedFolderUri = null, pickedFolderName = null) }

    private fun updatePlaylist(transform: (MediaSheetState.Playlist) -> MediaSheetState.Playlist) {
        _state.update { state ->
            val sheet = state.sheet
            if (sheet is MediaSheetState.Playlist) state.copy(sheet = transform(sheet)) else state
        }
    }

    // endregion

    fun dismissSheet() {
        sheetJob?.cancel()
        _state.update { it.copy(sheet = MediaSheetState.Hidden) }
    }

    /** Adds the chosen video (or the ticked playlist videos) to the download queue. */
    fun downloadSelected() {
        when (val sheet = _state.value.sheet) {
            is MediaSheetState.Ready -> downloadVideo(sheet)
            is MediaSheetState.Playlist -> downloadPlaylist(sheet)
            else -> Unit
        }
    }

    private fun downloadVideo(sheet: MediaSheetState.Ready) {
        val option = sheet.selectedOption ?: return
        val info = sheet.info
        val folder = if (settings.value.platformSubfolders) info.platform.folderName else "Social Media"
        container.downloadEngine.enqueue(
            listOf(
                socialTask(info.pageUrl, info.title, info.thumbnailUrl, info.platform, option, folder)
            )
        )
        _state.update { it.copy(sheet = MediaSheetState.Hidden, linkInput = "") }
        _messages.tryEmit("Download started: ${option.label}")
    }

    private fun downloadPlaylist(sheet: MediaSheetState.Playlist) {
        val option = sheet.selectedOption ?: return
        val info = sheet.info
        val chosen = info.entries.filter { it.id in sheet.selectedIds }
        if (chosen.isEmpty()) {
            _messages.tryEmit("Tick at least one video")
            return
        }
        // Either straight into a folder the user picked, or a new (or existing) folder
        // with the typed name inside the download folder.
        val picked = sheet.pickedFolderUri
        val folder = if (picked != null) {
            ""
        } else {
            StorageHelper.cleanFileName(sheet.newFolderName).take(80)
                .ifBlank { StorageHelper.cleanFileName(info.title).take(80) }
                .ifBlank { info.platform.folderName }
        }
        val tasks = chosen.map { entry ->
            socialTask(entry.url, entry.title, entry.thumbnailUrl, info.platform, option, folder)
                .copy(targetTreeUri = picked.orEmpty())
        }
        container.downloadEngine.enqueue(tasks)
        _state.update { it.copy(sheet = MediaSheetState.Hidden, linkInput = "") }
        val where = sheet.pickedFolderName ?: folder
        _messages.tryEmit("${tasks.size} videos added (${option.label}) → $where")
    }

    private fun socialTask(
        url: String,
        title: String,
        thumbnailUrl: String,
        platform: com.example.data.model.Platform,
        option: FormatOption,
        folder: String
    ): DownloadTask {
        val baseName = StorageHelper.cleanFileName(title).take(100).ifBlank { "${platform.displayName} video" }
        return DownloadTask(
            fileName = "$baseName.${option.extension}",
            sourceUrl = url,
            source = DownloadSource.SOCIAL,
            totalBytes = option.estimatedBytes,
            subFolder = folder,
            title = title,
            thumbnailUrl = thumbnailUrl,
            platform = platform,
            formatSelector = option.selector,
            mediaKind = option.kind,
            audioFormat = option.audioFormat,
            qualityLabel = option.label
        )
    }

    /** Called when the app gains focus with text on the clipboard. */
    fun onClipboardText(text: String?) {
        if (!settings.value.detectClipboardLinks) return
        val link = LinkUtils.extractUrl(text) ?: return
        if (link == lastOfferedClipboardLink || link == _state.value.linkInput) return
        lastOfferedClipboardLink = link
        _state.update { it.copy(clipboardLink = link) }
        // Start reading now so the sheet is (nearly) instant when the user taps Download.
        read(link, wholePlaylist = false)
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

    private fun read(url: String, wholePlaylist: Boolean): Deferred<FetchResult> {
        val key = "$wholePlaylist|$url"
        synchronized(reads) {
            reads[key]?.let { return it }
            val job = viewModelScope.async {
                val cookies = if (settings.value.useBrowserLogin) container.cookieExporter.export() else null
                container.ytDlp.fetch(url, cookies, wholePlaylist)
            }
            reads[key] = job
            return job
        }
    }

    private fun forget(url: String, wholePlaylist: Boolean) {
        synchronized(reads) { reads.remove("$wholePlaylist|$url") }
    }

    /** The standard quality list, shown when reading the link takes too long. */
    private fun provisionalSheet(url: String, preview: PreviewInfo?): MediaSheetState.Ready {
        val platform = com.example.data.model.Platform.fromUrl(url)
        val options = YtDlpJsonParser.playlistOptions()
        val info = MediaInfo(
            pageUrl = url,
            title = preview?.title.orEmpty(),
            uploader = preview?.author.orEmpty(),
            thumbnailUrl = preview?.thumbnailUrl.orEmpty(),
            durationSeconds = 0,
            platform = platform,
            options = options,
            playlistUrl = YtDlpJsonParser.playlistUrlFor(url)
        )
        return MediaSheetState.Ready(info, defaultOption(options).id, provisional = true)
    }

    private fun sheetFor(result: FetchResult): MediaSheetState = when (result) {
        is FetchResult.Video -> MediaSheetState.Ready(result.info, defaultOption(result.info.options).id)
        is FetchResult.Playlist -> {
            val options = YtDlpJsonParser.playlistOptions()
            MediaSheetState.Playlist(
                info = result.info,
                selectedIds = result.info.entries.map { it.id }.toSet(),
                options = options,
                selectedOptionId = defaultOption(options).id,
                newFolderName = StorageHelper.cleanFileName(result.info.title).take(80)
            )
        }
    }

    /** Pre-selects the quality from Settings (or the closest one below it). */
    private fun defaultOption(options: List<FormatOption>): FormatOption {
        val preferred = settings.value.preferredVideoHeight
        val videos = options.filter { it.kind == com.example.data.model.MediaKind.VIDEO }
        val byQuality = when {
            videos.isEmpty() -> null
            preferred <= 0 -> videos.first()
            else -> videos.filter { it.height in 1..preferred }.maxByOrNull { it.height }
                ?: videos.minByOrNull { it.height }
        }
        return byQuality
            ?: options.firstOrNull { it.audioFormat == settings.value.preferredAudioFormat }
            ?: options.first()
    }
}
