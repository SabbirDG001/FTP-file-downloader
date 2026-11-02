package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.example.ui.MediaDownloaderApp
import com.example.ui.screens.home.HomeViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val homeViewModel: HomeViewModel by viewModels()

    /** Increases each time a notification asks to show the Downloads tab. */
    private var openDownloadsSignal by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            MyApplicationTheme {
                MediaDownloaderApp(
                    homeViewModel = homeViewModel,
                    openDownloadsSignal = openDownloadsSignal
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when {
            intent == null -> Unit
            intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
                homeViewModel.handleSharedText(text)
            }
            intent.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) -> openDownloadsSignal++
        }
    }

    companion object {
        const val EXTRA_OPEN_DOWNLOADS = "com.example.extra.OPEN_DOWNLOADS"
    }
}
