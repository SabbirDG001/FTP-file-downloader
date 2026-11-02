package com.example.ui.screens.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.Platform
import com.example.data.util.LinkUtils
import com.example.ui.components.PlatformBadge

/**
 * Keeps one WebView alive while the user switches tabs, so the page (and any login)
 * isn't lost. Created once in the app root.
 */
@Stable
class BrowserController {
    var webView: WebView? = null
        private set

    var currentUrl by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)

    fun load(url: String) {
        currentUrl = url
        webView?.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun obtainWebView(context: Context): WebView {
        webView?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Stay inside the app for web links; ignore app-only schemes like intent://.
                    return !LinkUtils.isWebUrl(request.url.toString())
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    url?.let { currentUrl = it }
                    updateNavigation(view)
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    // Single-page sites like YouTube change the URL without loading a new page.
                    url?.let { currentUrl = it }
                    updateNavigation(view)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    url?.let { currentUrl = it }
                    updateNavigation(view)
                    CookieManager.getInstance().flush()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    this@BrowserController.progress = newProgress
                }
            }
            webView = this
            if (currentUrl.isNotBlank()) loadUrl(currentUrl)
        }
    }

    fun goBack() = webView?.goBack()
    fun goForward() = webView?.goForward()
    fun reload() = webView?.reload()

    private fun updateNavigation(view: WebView) {
        canGoBack = view.canGoBack()
        canGoForward = view.canGoForward()
    }
}

@Composable
fun WebBrowserScreen(
    controller: BrowserController,
    onDownloadPage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    var addressText by remember { mutableStateOf(controller.currentUrl) }
    LaunchedEffect(controller.currentUrl) { addressText = controller.currentUrl }

    BackHandler(enabled = controller.canGoBack) { controller.goBack() }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Address bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { controller.goBack() }, enabled = controller.canGoBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            IconButton(onClick = { controller.goForward() }, enabled = controller.canGoForward) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
            }
            OutlinedTextField(
                value = addressText,
                onValueChange = { addressText = it },
                singleLine = true,
                placeholder = { Text("Search or type a web address") },
                shape = RoundedCornerShape(24.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    focusManager.clearFocus()
                    controller.load(toUrlOrSearch(addressText))
                }),
                modifier = Modifier
                    .weight(1f)
                    .testTag("browser_address_bar")
            )
            IconButton(onClick = { controller.reload() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Reload")
            }
        }

        // Quick links
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Platform.shortcuts.forEach { platform ->
                AssistChip(
                    onClick = { controller.load(platform.homeUrl) },
                    label = { Text(platform.displayName) },
                    leadingIcon = { PlatformBadge(platform, size = 18.dp) }
                )
            }
        }

        if (controller.progress < 100) {
            LinearProgressIndicator(
                progress = { controller.progress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (controller.currentUrl.isBlank()) {
                EmptyBrowser(onOpen = { controller.load(it) })
            } else {
                AndroidView(
                    factory = { context -> controller.obtainWebView(context) },
                    modifier = Modifier.fillMaxSize()
                )
                ExtendedFloatingActionButton(
                    onClick = { onDownloadPage(controller.currentUrl) },
                    icon = { Icon(Icons.Default.Download, contentDescription = null) },
                    text = { Text("Download") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .testTag("browser_download_fab")
                )
            }
        }
    }
}

@Composable
private fun EmptyBrowser(onOpen: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Home, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Spacer(modifier = Modifier.size(12.dp))
        Text("Open a site above, find a video, then tap Download.", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            "Logging in here also lets the app download private Instagram and Facebook posts you can see.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.size(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Platform.shortcuts.take(3).forEach { platform ->
                IconButton(onClick = { onOpen(platform.homeUrl) }, modifier = Modifier.size(56.dp)) {
                    PlatformBadge(platform, size = 44.dp)
                }
            }
        }
    }
}

private fun toUrlOrSearch(text: String): String {
    val trimmed = text.trim()
    return when {
        LinkUtils.isWebUrl(trimmed) -> trimmed
        trimmed.contains('.') && !trimmed.contains(' ') -> "https://$trimmed"
        else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
    }
}
