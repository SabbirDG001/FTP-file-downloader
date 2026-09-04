package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.components.FolderAccessPrompt
import com.example.ui.components.TopAppBarHeader
import com.example.ui.screens.DirectUrlScreen
import com.example.ui.screens.TransfersScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.DownloaderViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MediaDownloaderApp()
            }
        }
    }
}

@Composable
fun MediaDownloaderApp(
    viewModel: DownloaderViewModel = viewModel()
) {
    val context = LocalContext.current
    val folderUri by viewModel.folderUri.collectAsState()
    val folderDisplayName by viewModel.folderDisplayName.collectAsState()
    val directUrlState by viewModel.directUrlState.collectAsState()
    val tasks by viewModel.tasks.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()

    // Request notification permission for background download progress notifications (Android 13+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Launcher for changing folder
    val changeFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onFolderSelected(uri)
        }
    }

    // If no folder access has been granted yet, show the storage permission prompt
    if (folderUri == null) {
        FolderAccessPrompt(
            onFolderGranted = { uri ->
                viewModel.onFolderSelected(uri)
            }
        )
        return
    }

    val activeDownloadsCount = tasks.count { it.isActive }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBarHeader(
                folderName = folderDisplayName,
                onChangeFolder = { changeFolderLauncher.launch(null) }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                modifier = Modifier.testTag("bottom_navigation_bar")
            ) {
                // Tab 0: Direct URL / Season
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { viewModel.setTab(0) },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = "Direct URL",
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    label = { Text("Direct Link", fontSize = 12.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.testTag("tab_direct_url")
                )

                // Tab 1: Active Transfers
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { viewModel.setTab(1) },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (activeDownloadsCount > 0) {
                                    Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                        Text(
                                            text = activeDownloadsCount.toString(),
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Transfers",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    },
                    label = { Text("Transfers", fontSize = 12.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.testTag("tab_transfers")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Crossfade(targetState = selectedTab, label = "tab_crossfade") { tab ->
                when (tab) {
                    0 -> DirectUrlScreen(
                        state = directUrlState,
                        onUrlChange = { viewModel.updateDirectUrl(it) },
                        onFolderChange = { viewModel.updateDirectFolder(it) },
                        onModeChange = { viewModel.setSeriesMode(it) },
                        onParseUrl = { viewModel.parseDirectUrl() },
                        onStartDownload = { viewModel.startDirectDownload() }
                    )
                    1 -> TransfersScreen(
                        tasks = tasks,
                        onCancelTask = { viewModel.cancelTask(it) },
                        onRetryTask = { viewModel.retryTask(it) },
                        onRemoveTask = { viewModel.removeTask(it) },
                        onClearCompleted = { viewModel.clearCompletedTasks() }
                    )
                }
            }
        }
    }
}

