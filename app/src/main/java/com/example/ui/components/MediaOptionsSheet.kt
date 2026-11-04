package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.FormatOption
import com.example.data.model.MediaKind
import com.example.data.model.Platform
import com.example.data.model.PlaylistEntry
import com.example.data.util.Formatters
import com.example.ui.screens.home.MediaSheetState
import com.example.ui.theme.StatusError

/** Actions the sheet can trigger. */
class MediaSheetActions(
    val onSelectOption: (String) -> Unit,
    val onDownload: () -> Unit,
    val onRetry: () -> Unit,
    val onOpenWholePlaylist: () -> Unit,
    val onToggleEntry: (String) -> Unit,
    val onSelectAll: () -> Unit,
    val onSelectNone: () -> Unit,
    val onFolderNameChange: (String) -> Unit,
    val onPickFolder: () -> Unit,
    val onUseNewFolder: () -> Unit,
    val onDismiss: () -> Unit
)

/** Bottom sheet that reads a link and lets the user choose quality (and videos, for a playlist). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaOptionsSheet(state: MediaSheetState, actions: MediaSheetActions) {
    if (state is MediaSheetState.Hidden) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = actions.onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("media_options_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
        ) {
            when (state) {
                is MediaSheetState.Loading -> LoadingContent(state)
                is MediaSheetState.Error -> ErrorContent(state, actions)
                is MediaSheetState.Ready -> VideoContent(state, actions)
                is MediaSheetState.Playlist -> PlaylistContent(state, actions)
                MediaSheetState.Hidden -> Unit
            }
        }
    }
}

@Composable
private fun LoadingContent(state: MediaSheetState.Loading) {
    val preview = state.preview
    if (preview != null) {
        // Show what we already know while qualities load.
        MediaHeader(
            title = preview.title,
            subtitle = preview.author,
            thumbnailUrl = preview.thumbnailUrl,
            durationSeconds = 0,
            platform = Platform.fromUrl(state.url)
        )
        Spacer(modifier = Modifier.height(16.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (state.isPlaylist) "Reading the playlist…" else "Finding available qualities…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (state.isPlaylist) "Reading the playlist…" else "Reading video details…",
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = state.url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ErrorContent(state: MediaSheetState.Error, actions: MediaSheetActions) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = StatusError, modifier = Modifier.size(40.dp))
        Spacer(modifier = Modifier.height(12.dp))
        Text("Couldn't read this link", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Tip: private posts need you to log in once in the Browser tab. Updating the download engine in Settings also fixes many errors.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = actions.onDismiss) { Text("Close") }
            Button(onClick = actions.onRetry) { Text("Try again") }
        }
    }
}

/**
 * Everything in [content] scrolls; [button] stays pinned at the bottom at full size,
 * however long the list is (it used to get squashed on long playlists).
 */
@Composable
private fun ScrollingWithPinnedButton(
    content: LazyListScope.() -> Unit,
    button: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 4.dp),
            content = content
        )
        Spacer(modifier = Modifier.height(12.dp))
        button()
    }
}

@Composable
private fun VideoContent(state: MediaSheetState.Ready, actions: MediaSheetActions) {
    val info = state.info
    ScrollingWithPinnedButton(
        content = {
            item {
                MediaHeader(
                    title = info.title.ifBlank { "${info.platform.displayName} video" },
                    subtitle = info.uploader,
                    thumbnailUrl = info.thumbnailUrl,
                    durationSeconds = info.durationSeconds,
                    platform = info.platform
                )
            }
            if (state.provisional) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "This video is slow to read, so here are the standard qualities. You can download now; exact sizes appear when ready.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (info.playlistUrl != null) {
                item {
                    OutlinedButton(
                        onClick = actions.onOpenWholePlaylist,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .testTag("open_whole_playlist_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("This video is in a playlist — choose videos from it")
                    }
                }
            }
            if (info.videoOptions.isNotEmpty()) {
                item { SectionLabel("VIDEO") }
                items(info.videoOptions, key = { it.id }) { option ->
                    OptionRow(option, selected = option.id == state.selectedOptionId, onClick = { actions.onSelectOption(option.id) })
                }
            }
            if (info.audioOptions.isNotEmpty()) {
                item { SectionLabel("AUDIO ONLY") }
                items(info.audioOptions, key = { it.id }) { option ->
                    OptionRow(option, selected = option.id == state.selectedOptionId, onClick = { actions.onSelectOption(option.id) })
                }
            }
        },
        button = {
            DownloadButton(
                label = state.selectedOption?.let { "Download ${it.label}" } ?: "Download",
                enabled = state.selectedOption != null,
                onClick = actions.onDownload
            )
        }
    )
}

@Composable
private fun PlaylistContent(state: MediaSheetState.Playlist, actions: MediaSheetActions) {
    val info = state.info
    val count = state.selectedIds.size
    ScrollingWithPinnedButton(
        content = {
            item { PlaylistHeader(state) }
            item { PlaylistQualityPicker(state, actions) }
            item { Column { SaveToSection(state, actions) } }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "$count of ${info.entries.size} selected",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = actions.onSelectAll) { Text("Select all") }
                    TextButton(onClick = actions.onSelectNone) { Text("None") }
                }
            }
            if (info.entries.isEmpty()) {
                item {
                    Text(
                        text = "No downloadable videos were found in this playlist.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            }
            itemsIndexed(info.entries, key = { _, entry -> entry.id }) { index, entry ->
                PlaylistRow(
                    index = index + 1,
                    entry = entry,
                    checked = entry.id in state.selectedIds,
                    onToggle = { actions.onToggleEntry(entry.id) }
                )
            }
        },
        button = {
            DownloadButton(
                label = when (count) {
                    0 -> "Tick videos to download"
                    1 -> "Download 1 video • ${state.selectedOption?.label.orEmpty()}"
                    else -> "Download $count videos • ${state.selectedOption?.label.orEmpty()}"
                },
                enabled = count > 0 && state.selectedOption != null,
                onClick = actions.onDownload
            )
        }
    )
}

@Composable
private fun PlaylistHeader(state: MediaSheetState.Playlist) {
    val info = state.info
    Row(verticalAlignment = Alignment.CenterVertically) {
        PlatformBadge(info.platform, size = 36.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = info.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append("${info.entries.size} videos")
                    if (info.uploader.isNotBlank()) append(" • ${info.uploader}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlaylistQualityPicker(state: MediaSheetState.Playlist, actions: MediaSheetActions) {
    Column {
        SectionLabel("QUALITY FOR ALL VIDEOS")
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.options.forEach { option ->
                FilterChip(
                    selected = option.id == state.selectedOptionId,
                    onClick = { actions.onSelectOption(option.id) },
                    label = { Text(option.label) },
                    leadingIcon = {
                        Icon(
                            imageVector = if (option.kind == MediaKind.AUDIO) Icons.Default.MusicNote else Icons.Default.Videocam,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.testTag("playlist_quality_${option.id}")
                )
            }
        }
    }
}

/** Where the playlist is saved: a new folder (name it) or any folder picked on the phone. */
@Composable
private fun SaveToSection(state: MediaSheetState.Playlist, actions: MediaSheetActions) {
    val usingPicked = state.pickedFolderUri != null
    SectionLabel("SAVE TO")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = actions.onUseNewFolder)
    ) {
        RadioButton(selected = !usingPicked, onClick = actions.onUseNewFolder)
        OutlinedTextField(
            value = state.newFolderName,
            onValueChange = actions.onFolderNameChange,
            label = { Text("New folder name") },
            leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .weight(1f)
                .testTag("playlist_folder_name")
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = actions.onPickFolder)
            .padding(vertical = 2.dp)
            .testTag("playlist_pick_folder")
    ) {
        RadioButton(selected = usingPicked, onClick = actions.onPickFolder)
        Icon(Icons.Default.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (usingPicked) state.pickedFolderName.orEmpty() else "Choose a folder on my phone…",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (usingPicked) "Tap to choose a different folder" else "Any folder or SD card • you can create one there too",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlaylistRow(index: Int, entry: PlaylistEntry, checked: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp)
            .testTag("playlist_entry_${entry.id}")
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(
            text = "$index",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(26.dp)
        )
        Box(
            modifier = Modifier
                .width(80.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (entry.thumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = entry.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            DurationTag(entry.durationSeconds, Modifier.align(Alignment.BottomEnd))
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DownloadButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .testTag("sheet_download_button"),
        shape = RoundedCornerShape(24.dp)
    ) {
        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MediaHeader(
    title: String,
    subtitle: String,
    thumbnailUrl: String,
    durationSeconds: Int,
    platform: Platform
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(128.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (thumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            DurationTag(durationSeconds, Modifier.align(Alignment.BottomEnd))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatformBadge(platform, size = 18.dp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = platform.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DurationTag(durationSeconds: Int, modifier: Modifier = Modifier) {
    if (durationSeconds <= 0) return
    Surface(
        color = Color.Black.copy(alpha = 0.75f),
        shape = RoundedCornerShape(4.dp),
        modifier = modifier.padding(3.dp)
    ) {
        Text(
            text = Formatters.duration(durationSeconds.toLong()),
            color = Color.White,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun OptionRow(option: FormatOption, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("format_option_${option.id}")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (option.kind == MediaKind.AUDIO) Icons.Default.MusicNote else Icons.Default.Videocam,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(option.label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                Text(
                    text = option.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (option.estimatedBytes > 0) {
                Text(
                    text = "~" + Formatters.bytes(option.estimatedBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            RadioButton(selected = selected, onClick = onClick)
        }
    }
}
