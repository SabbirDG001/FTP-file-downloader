package com.example.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.appContainer
import com.example.data.remote.social.EngineState
import com.example.data.settings.AppSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.appContainer

    val settings: StateFlow<AppSettings> = container.settings.settings
    val folderName: StateFlow<String> = container.storage.folderName
    val engineState: StateFlow<EngineState> = container.ytDlp.state

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun update(transform: (AppSettings) -> AppSettings) = container.settings.update(transform)

    fun prepareEngine() {
        viewModelScope.launch { runCatching { container.ytDlp.ensureReady() } }
    }

    fun updateEngine() {
        viewModelScope.launch {
            container.ytDlp.update().fold(
                onSuccess = { message ->
                    container.settings.update { it.copy(lastEngineUpdate = System.currentTimeMillis()) }
                    _messages.tryEmit(message)
                },
                onFailure = { _messages.tryEmit(it.message ?: "Update failed") }
            )
        }
    }

    fun clearBrowserLogin() {
        viewModelScope.launch {
            container.cookieExporter.clear()
            _messages.tryEmit("Logged out of all sites in the built-in browser")
        }
    }
}
