package com.example.ui.screens.servers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.ServerProtocol
import com.example.ui.components.FtpServerDialog

/** "Servers" tab: the FTP / web directory browser and the direct link + season downloader. */
@Composable
fun ServersScreen(viewModel: ServersViewModel, modifier: Modifier = Modifier) {
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var showServerDialog by rememberSaveable { mutableStateOf(false) }

    val config by viewModel.serverConfig.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val browser by viewModel.browser.collectAsStateWithLifecycle()
    val directUrl by viewModel.directUrl.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab, containerColor = MaterialTheme.colorScheme.surface) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Server browser") },
                modifier = Modifier.testTag("tab_server_browser")
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Direct link / Season") },
                modifier = Modifier.testTag("tab_direct_url")
            )
        }

        when (selectedTab) {
            0 -> FtpBrowserScreen(
                browserState = browser,
                serverName = config.name,
                serverAddress = if (config.protocol == ServerProtocol.FTP) "ftp://${config.host}:${config.port}" else config.host,
                canNavigateUp = viewModel.canNavigateUp(),
                onEditServer = { showServerDialog = true },
                isConnected = connection.isConnected,
                isConnecting = connection.isConnecting,
                connectionError = connection.error,
                onConnect = { viewModel.connect() },
                onNavigate = viewModel::navigateTo,
                onNavigateUp = viewModel::navigateUp,
                onSearchChange = viewModel::setSearchQuery,
                onToggleSelect = viewModel::toggleSelect,
                onSelectAllVideos = viewModel::selectAllVideos,
                onClearSelection = viewModel::clearSelection,
                onDownloadItem = viewModel::downloadItem,
                onDownloadSelected = viewModel::downloadSelected
            )

            else -> DirectLinkScreen(
                state = directUrl,
                onUrlChange = viewModel::updateDirectUrl,
                onFolderChange = viewModel::updateDirectFolder,
                onModeChange = viewModel::setSeriesMode,
                onParseUrl = viewModel::parseDirectUrl,
                onStartDownload = viewModel::startDirectDownload
            )
        }
    }

    if (showServerDialog) {
        FtpServerDialog(
            currentConfig = config,
            onDismiss = { showServerDialog = false },
            onSaveAndConnect = { newConfig ->
                showServerDialog = false
                viewModel.saveServerAndConnect(newConfig)
            }
        )
    }
}
