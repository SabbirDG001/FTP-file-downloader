package com.example.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.appContainer
import com.example.data.model.isActive
import com.example.ui.components.FolderAccessPrompt
import com.example.ui.components.MediaOptionsSheet
import com.example.ui.components.TopAppBarHeader
import com.example.ui.navigation.Destination
import com.example.ui.screens.browser.BrowserController
import com.example.ui.screens.browser.WebBrowserScreen
import com.example.ui.screens.downloads.DownloadsScreen
import com.example.ui.screens.downloads.DownloadsViewModel
import com.example.ui.screens.home.HomeScreen
import com.example.ui.screens.home.HomeViewModel
import com.example.ui.screens.servers.ServersScreen
import com.example.ui.screens.servers.ServersViewModel
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.flow.merge

/**
 * Root of the UI: asks for a download folder on first launch, then shows the
 * bottom-tab app. The quality sheet lives here so it can open from any tab
 * (Home, Browser, or a link shared from another app).
 */
@Composable
fun MediaDownloaderApp(
    homeViewModel: HomeViewModel = viewModel(),
    openDownloadsSignal: Int = 0
) {
    val context = LocalContext.current
    val storage = context.appContainer.storage
    val folderUri by storage.folderUri.collectAsStateWithLifecycle()
    val folderName by storage.folderName.collectAsStateWithLifecycle()

    RequestNotificationPermission()

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) storage.saveFolderUri(uri)
    }

    if (folderUri == null) {
        FolderAccessPrompt(onFolderGranted = { uri -> storage.saveFolderUri(uri) })
        return
    }

    val downloadsViewModel: DownloadsViewModel = viewModel()
    val serversViewModel: ServersViewModel = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel()

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = Destination.fromRoute(backStackEntry?.destination?.route)
    val browserController = remember { BrowserController() }
    val snackbarHostState = remember { SnackbarHostState() }

    val homeState by homeViewModel.state.collectAsStateWithLifecycle()
    val tasks by downloadsViewModel.tasks.collectAsStateWithLifecycle()
    val activeCount = tasks.count { it.isActive }

    // One snackbar for messages from every screen.
    LaunchedEffect(Unit) {
        merge(
            homeViewModel.messages,
            downloadsViewModel.messages,
            serversViewModel.messages,
            settingsViewModel.messages
        ).collect { snackbarHostState.showSnackbar(it) }
    }

    // Opened from a download notification.
    LaunchedEffect(openDownloadsSignal) {
        if (openDownloadsSignal > 0) navController.navigateToTab(Destination.DOWNLOADS)
    }

    // Offer links copied in other apps when the user comes back to this app.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) {
        if (windowFocused) homeViewModel.onClipboardText(readClipboardText(context))
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBarHeader(
                title = current.title,
                folderName = folderName,
                onChangeFolder = { folderPicker.launch(null) },
                onOpenSettings = { navController.navigate(Destination.SETTINGS.route) { launchSingleTop = true } },
                onBack = if (current == Destination.SETTINGS) {
                    { navController.popBackStack(); Unit }
                } else {
                    null
                }
            )
        },
        bottomBar = {
            if (current != Destination.SETTINGS) {
                NavigationBar(modifier = Modifier.testTag("bottom_navigation_bar")) {
                    Destination.bottomBar.forEach { destination ->
                        NavigationBarItem(
                            selected = current == destination,
                            onClick = { navController.navigateToTab(destination) },
                            icon = {
                                if (destination == Destination.DOWNLOADS && activeCount > 0) {
                                    BadgedBox(badge = { Badge { Text(activeCount.toString()) } }) {
                                        Icon(destination.icon!!, contentDescription = destination.label)
                                    }
                                } else {
                                    Icon(destination.icon!!, contentDescription = destination.label)
                                }
                            },
                            label = { Text(destination.label) },
                            modifier = Modifier.testTag("tab_${destination.route}")
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            NavHost(navController = navController, startDestination = Destination.HOME.route) {
                composable(Destination.HOME.route) {
                    HomeScreen(
                        state = homeState,
                        activeDownloads = activeCount,
                        onLinkChange = homeViewModel::onLinkChange,
                        onFetch = { homeViewModel.fetch() },
                        onAcceptClipboard = homeViewModel::acceptClipboardLink,
                        onDismissClipboard = homeViewModel::dismissClipboardLink,
                        onOpenSite = { url ->
                            browserController.load(url)
                            navController.navigateToTab(Destination.BROWSER)
                        },
                        onOpenDownloads = { navController.navigateToTab(Destination.DOWNLOADS) }
                    )
                }
                composable(Destination.BROWSER.route) {
                    WebBrowserScreen(
                        controller = browserController,
                        onDownloadPage = { url -> homeViewModel.fetch(url) }
                    )
                }
                composable(Destination.SERVERS.route) {
                    ServersScreen(viewModel = serversViewModel)
                }
                composable(Destination.DOWNLOADS.route) {
                    val filter by downloadsViewModel.filter.collectAsStateWithLifecycle()
                    DownloadsScreen(
                        tasks = tasks,
                        filter = filter,
                        onFilterChange = downloadsViewModel::setFilter,
                        onPause = downloadsViewModel::pause,
                        onResume = downloadsViewModel::resume,
                        onRetry = downloadsViewModel::retry,
                        onCancel = downloadsViewModel::cancel,
                        onRemove = downloadsViewModel::remove,
                        onOpen = downloadsViewModel::open,
                        onShare = downloadsViewModel::share,
                        onClearFinished = downloadsViewModel::clearFinished,
                        onPauseAll = downloadsViewModel::pauseAll,
                        onResumeAll = downloadsViewModel::resumeAll
                    )
                }
                composable(Destination.SETTINGS.route) {
                    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
                    val engineState by settingsViewModel.engineState.collectAsStateWithLifecycle()
                    SettingsScreen(
                        settings = settings,
                        folderName = folderName,
                        engineState = engineState,
                        onChangeFolder = { folderPicker.launch(null) },
                        onUpdate = settingsViewModel::update,
                        onPrepareEngine = settingsViewModel::prepareEngine,
                        onUpdateEngine = settingsViewModel::updateEngine,
                        onClearBrowserLogin = settingsViewModel::clearBrowserLogin
                    )
                }
            }
        }
    }

    MediaOptionsSheet(
        state = homeState.sheet,
        onSelect = homeViewModel::selectOption,
        onDownload = homeViewModel::downloadSelected,
        onRetry = { url -> homeViewModel.fetch(url) },
        onDismiss = homeViewModel::dismissSheet
    )
}

/** Switches bottom tabs without stacking up history. */
private fun NavHostController.navigateToTab(destination: Destination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Android 13+ needs permission to show download progress notifications. */
@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private fun readClipboardText(context: Context): String? = try {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    if (clipboard.hasPrimaryClip() && (clipboard.primaryClip?.itemCount ?: 0) > 0) {
        clipboard.primaryClip?.getItemAt(0)?.text?.toString()
    } else {
        null
    }
} catch (_: Exception) {
    null
}
