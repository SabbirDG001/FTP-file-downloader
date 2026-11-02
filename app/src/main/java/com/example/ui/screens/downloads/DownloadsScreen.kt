package com.example.ui.screens.downloads

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.DownloadSource
import com.example.data.model.DownloadStatus
import com.example.data.model.DownloadTask
import com.example.data.model.MediaKind
import com.example.data.model.RemoteFileItem
import com.example.data.model.displayTitle
import com.example.data.model.formattedDownloaded
import com.example.data.model.formattedSpeed
import com.example.data.model.formattedTotal
import com.example.data.model.isActive
import com.example.data.model.progress
import com.example.data.model.progressPercent
import com.example.data.util.Formatters
import com.example.ui.components.PlatformBadge
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning

@Composable
fun DownloadsScreen(
    tasks: List<DownloadTask>,
    filter: DownloadFilter,
    onFilterChange: (DownloadFilter) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRemove: (id: String, deleteFile: Boolean) -> Unit,
    onOpen: (DownloadTask) -> Unit,
    onShare: (DownloadTask) -> Unit,
    onClearFinished: () -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingDelete by remember { mutableStateOf<DownloadTask?>(null) }
    val visible = tasks.filter { DownloadsViewModel.matches(it, filter) }
    val groups = DownloadsViewModel.groupForDisplay(visible)
    val anyActive = tasks.any { it.isActive }
    val anyPaused = tasks.any { it.status == DownloadStatus.PAUSED }
    val anyFinished = tasks.any { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.CANCELED }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DownloadFilter.entries.forEach { option ->
                val count = tasks.count { DownloadsViewModel.matches(it, option) }
                FilterChip(
                    selected = filter == option,
                    onClick = { onFilterChange(option) },
                    label = { Text(if (option == DownloadFilter.ALL) option.label else "${option.label} ($count)") },
                    modifier = Modifier.testTag("filter_${option.name}")
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            if (anyActive) {
                TextButton(onClick = onPauseAll) {
                    Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Pause all", fontSize = 13.sp)
                }
            }
            if (anyPaused) {
                TextButton(onClick = onResumeAll) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Resume all", fontSize = 13.sp)
                }
            }
            if (anyFinished) {
                TextButton(onClick = onClearFinished) {
                    Icon(Icons.Default.ClearAll, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear finished", fontSize = 13.sp)
                }
            }
        }

        if (visible.isEmpty()) {
            EmptyDownloads(filter)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                groups.forEach { group ->
                    item(key = "header_${group.title}") {
                        SectionHeader(group.title, group.tasks.size)
                    }
                    items(group.tasks, key = { it.id }) { task ->
                        DownloadCard(
                            task = task,
                            onPause = { onPause(task.id) },
                            onResume = { onResume(task.id) },
                            onRetry = { onRetry(task.id) },
                            onCancel = { onCancel(task.id) },
                            onDelete = {
                                if (task.status == DownloadStatus.COMPLETED) pendingDelete = task else onRemove(task.id, false)
                            },
                            onOpen = { onOpen(task) },
                            onShare = { onShare(task) }
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { task ->
        var deleteFile by remember(task.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove download?") },
            text = {
                Column {
                    Text(task.displayTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { deleteFile = !deleteFile }
                    ) {
                        Checkbox(checked = deleteFile, onCheckedChange = { deleteFile = it })
                        Text("Also delete the file from the device")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(task.id, deleteFile)
                    pendingDelete = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

/** "DOWNLOADING (3)" / "DOWNLOADED (12)" heading with a line under it. */
@Composable
private fun SectionHeader(title: String, count: Int) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = "${title.uppercase()} ($count)",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    }
}

@Composable
private fun DownloadCard(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    val isDone = task.status == DownloadStatus.COMPLETED
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = isDone, onClick = onOpen)
            .testTag("download_card_${task.fileName}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumbnail(task)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.displayTitle,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    StatusLine(task)
                }
            }

            if (task.isActive || task.status == DownloadStatus.PAUSED) {
                Spacer(modifier = Modifier.height(10.dp))
                val indeterminate = task.totalBytes <= 0 && task.status != DownloadStatus.PAUSED
                if (indeterminate) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .testTag("download_progress_bar_${task.fileName}"),
                        trackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (task.status) {
                    DownloadStatus.QUEUED, DownloadStatus.CONNECTING, DownloadStatus.DOWNLOADING -> {
                        ActionButton(Icons.Default.Pause, "Pause", onPause)
                        ActionButton(Icons.Default.Close, "Cancel", onCancel)
                    }
                    DownloadStatus.PAUSED -> {
                        ActionButton(Icons.Default.PlayArrow, "Resume", onResume)
                        ActionButton(Icons.Default.Close, "Cancel", onCancel)
                    }
                    DownloadStatus.COMPLETED -> {
                        ActionButton(Icons.Default.PlayArrow, "Open", onOpen)
                        ActionButton(Icons.Default.Share, "Share", onShare)
                        ActionButton(Icons.Default.Delete, "Remove", onDelete)
                    }
                    DownloadStatus.FAILED, DownloadStatus.CANCELED -> {
                        ActionButton(Icons.Default.Refresh, "Retry", onRetry)
                        ActionButton(Icons.Default.Delete, "Remove", onDelete)
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(task: DownloadTask) {
    Box(
        modifier = Modifier
            .size(width = 72.dp, height = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (task.thumbnailUrl.isNotBlank()) {
            AsyncImage(
                model = task.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = fileIcon(task),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
        if (task.source == DownloadSource.SOCIAL) {
            PlatformBadge(
                platform = task.platform,
                size = 16.dp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(3.dp)
            )
        }
    }
}

@Composable
private fun StatusLine(task: DownloadTask) {
    val (text, color) = when (task.status) {
        DownloadStatus.QUEUED -> "Waiting in queue" to MaterialTheme.colorScheme.onSurfaceVariant
        DownloadStatus.CONNECTING -> "Connecting…" to MaterialTheme.colorScheme.primary
        DownloadStatus.DOWNLOADING -> buildString {
            append("${task.progressPercent}% • ${task.formattedDownloaded} / ${task.formattedTotal}")
            if (task.speedBytesPerSec > 0) append(" • ${task.formattedSpeed}")
            if (task.etaSeconds > 0) append(" • ${Formatters.duration(task.etaSeconds)} left")
        } to MaterialTheme.colorScheme.primary
        DownloadStatus.PAUSED -> "Paused • ${task.formattedDownloaded} / ${task.formattedTotal}" to StatusWarning
        DownloadStatus.COMPLETED -> buildString {
            append("Done • ${task.formattedTotal}")
            if (task.qualityLabel.isNotBlank()) append(" • ${task.qualityLabel}")
            if (task.subFolder.isNotBlank()) append(" • ${task.subFolder}")
        } to StatusSuccess
        DownloadStatus.CANCELED -> "Cancelled" to MaterialTheme.colorScheme.onSurfaceVariant
        DownloadStatus.FAILED -> (task.errorMessage ?: "Failed") to StatusError
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (task.status) {
            DownloadStatus.COMPLETED -> Icon(Icons.Default.CheckCircle, null, tint = StatusSuccess, modifier = Modifier.size(13.dp))
            DownloadStatus.FAILED -> Icon(Icons.Default.ErrorOutline, null, tint = StatusError, modifier = Modifier.size(13.dp))
            else -> Unit
        }
        if (task.status == DownloadStatus.COMPLETED || task.status == DownloadStatus.FAILED) {
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = text,
            fontSize = 12.sp,
            color = color,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, fontSize = 13.sp)
    }
}

@Composable
private fun EmptyDownloads(filter: DownloadFilter) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (filter == DownloadFilter.ALL) "No downloads yet" else "Nothing here",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Paste a video link on Home, browse a site, or pick files from a server.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

private fun fileIcon(task: DownloadTask): ImageVector {
    if (task.mediaKind == MediaKind.AUDIO) return Icons.Default.MusicNote
    val ext = task.fileName.substringAfterLast('.', "").lowercase()
    return when {
        ext in RemoteFileItem.VIDEO_EXTENSIONS -> Icons.Default.Movie
        ext in RemoteFileItem.AUDIO_EXTENSIONS -> Icons.Default.MusicNote
        ext in RemoteFileItem.ARCHIVE_EXTENSIONS -> Icons.Default.Archive
        ext in RemoteFileItem.DOCUMENT_EXTENSIONS -> Icons.Default.Description
        task.source == DownloadSource.FTP -> Icons.Default.Dns
        else -> Icons.Default.Link
    }
}
