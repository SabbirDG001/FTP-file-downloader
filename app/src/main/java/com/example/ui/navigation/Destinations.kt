package com.example.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.ui.graphics.vector.ImageVector

/** Screens in the bottom navigation bar, plus settings. */
enum class Destination(val route: String, val label: String, val title: String, val icon: ImageVector?) {
    HOME("home", "Home", "Media Downloader", Icons.Default.Home),
    BROWSER("browser", "Browser", "Browser", Icons.Default.Public),
    SERVERS("servers", "Servers", "Servers & Links", Icons.Default.Dns),
    DOWNLOADS("downloads", "Downloads", "Downloads", Icons.Default.Download),
    SETTINGS("settings", "Settings", "Settings", null);

    companion object {
        val bottomBar = listOf(HOME, BROWSER, SERVERS, DOWNLOADS)

        fun fromRoute(route: String?): Destination = entries.firstOrNull { it.route == route } ?: HOME
    }
}
