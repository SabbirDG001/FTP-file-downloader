package com.example.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import com.example.BuildConfig
import com.example.data.remote.social.EngineState
import com.example.data.settings.AppSettings
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.components.PlatformBadge
import com.example.data.model.Platform
import com.example.data.remote.social.BrowserCookieExporter

@Composable
fun SettingsScreen(
    settings: AppSettings,
    folderName: String,
    engineState: EngineState,
    onChangeFolder: () -> Unit,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onPrepareEngine: () -> Unit,
    onUpdateEngine: () -> Unit,
    onClearBrowserLogin: () -> Unit,
    loginStatus: Map<Platform, Boolean>,
    onLogin: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Section("Storage") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Download folder", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                    Text(folderName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = onChangeFolder) { Text("Change") }
            }
            HorizontalDivider()
            ToggleRow(
                title = "Sort social downloads by site",
                subtitle = "Save into \"YouTube\", \"Facebook\", \"Instagram\"… folders",
                checked = settings.platformSubfolders,
                onChange = { value -> onUpdate { it.copy(platformSubfolders = value) } }
            )
        }

        Section("Downloads") {
            SettingTitle(
                title = "Number of downloads at a time",
                subtitle = "Others wait in the queue and start automatically"
            )
            ChoiceRow(
                options = listOf(1, 2, 3, 4, 5),
                selected = settings.maxConcurrentDownloads,
                label = { it.toString() },
                onSelect = { value -> onUpdate { it.copy(maxConcurrentDownloads = value) } }
            )
            HorizontalDivider()
            SettingTitle(
                title = "Speed limit",
                subtitle = "For all downloads together. Leaves internet speed for other apps."
            )
            ChoiceRow(
                options = listOf(0, 256, 512, 1024, 2048, 5120, 10240),
                selected = settings.speedLimitKbps,
                label = { kbps ->
                    when {
                        kbps == 0 -> "No limit"
                        kbps < 1024 -> "$kbps KB/s"
                        else -> "${kbps / 1024} MB/s"
                    }
                },
                onSelect = { value -> onUpdate { it.copy(speedLimitKbps = value) } }
            )
            HorizontalDivider()
            Text("Preferred video quality", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
            ChoiceRow(
                options = listOf(360, 480, 720, 1080, 0),
                selected = settings.preferredVideoHeight,
                label = { if (it == 0) "Best" else "${it}p" },
                onSelect = { value -> onUpdate { it.copy(preferredVideoHeight = value) } }
            )
            HorizontalDivider()
            Text("Preferred audio format", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
            ChoiceRow(
                options = listOf("mp3", "m4a"),
                selected = settings.preferredAudioFormat,
                label = { it.uppercase() },
                onSelect = { value -> onUpdate { it.copy(preferredAudioFormat = value) } }
            )
            HorizontalDivider()
            ToggleRow(
                title = "Detect copied links",
                subtitle = "Offer to download a link you copied in another app",
                checked = settings.detectClipboardLinks,
                onChange = { value -> onUpdate { it.copy(detectClipboardLinks = value) } }
            )
        }

        Section("Accounts") {
            Text(
                text = "Log in to download private posts and stories you can see. Logins stay on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            BrowserCookieExporter.LOGIN_SITES.forEach { (platform, site) ->
                val loggedIn = loginStatus[platform] == true
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlatformBadge(platform, size = 28.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(platform.displayName, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                        Text(
                            text = if (loggedIn) "Logged in" else "Not logged in",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (loggedIn) StatusSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!loggedIn) {
                        OutlinedButton(onClick = { onLogin(site.loginUrl) }) { Text("Log in") }
                    }
                }
            }
            Text(
                text = "Google may still refuse to log in inside apps. YouTube works without logging in for most videos.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Section("YouTube, Facebook & Instagram") {
            EngineStatus(engineState, onPrepareEngine)
            Button(
                onClick = onUpdateEngine,
                enabled = engineState !is EngineState.Updating && engineState !is EngineState.Preparing,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Update download engine now") }
            ToggleRow(
                title = "Update automatically",
                subtitle = "Sites change often; a weekly update keeps downloads working",
                checked = settings.autoUpdateEngine,
                onChange = { value -> onUpdate { it.copy(autoUpdateEngine = value) } }
            )
            HorizontalDivider()
            ToggleRow(
                title = "Use browser login",
                subtitle = "Lets the app download posts you can see after logging in on the Browser tab",
                checked = settings.useBrowserLogin,
                onChange = { value -> onUpdate { it.copy(useBrowserLogin = value) } }
            )
            OutlinedButton(onClick = onClearBrowserLogin, modifier = Modifier.fillMaxWidth()) {
                Text("Log out of all sites")
            }
        }

        Section("About") {
            Text("MediaDL ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "Only download videos you own or have permission to save. Respect each site's terms of service and copyright.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SettingTitle(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) }
            )
        }
    }
}

@Composable
private fun EngineStatus(state: EngineState, onPrepare: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (state) {
            EngineState.Preparing, EngineState.Updating -> {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(if (state == EngineState.Updating) "Updating download engine…" else "Starting download engine…")
            }
            is EngineState.Ready -> Text("Download engine (yt-dlp) version ${state.version}")
            is EngineState.Failed -> Column {
                Text("Download engine failed to start", color = StatusError)
                Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(onClick = onPrepare) { Text("Try again") }
            }
            EngineState.NotReady -> Column {
                Text("Download engine not started yet")
                OutlinedButton(onClick = onPrepare) { Text("Start now") }
            }
        }
    }
}
