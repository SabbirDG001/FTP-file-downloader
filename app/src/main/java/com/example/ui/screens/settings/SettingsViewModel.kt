package com.example.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.appContainer
import com.example.data.remote.social.EngineState
import com.example.data.settings.AppSettings
import com.example.data.model.Platform
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.appContainer

    val settings: StateFlow<AppSettings> = container.settings.settings
    val folderName: StateFlow<String> = container.storage.folderName
    val engineState: StateFlow<EngineState> = container.ytDlp.state

    private val _loginStatus = MutableStateFlow<Map<Platform, Boolean>>(emptyMap())
    /** Which sites are logged in inside the built-in browser. */
    val loginStatus: StateFlow<Map<Platform, Boolean>> = _loginStatus.asStateFlow()

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

    fun refreshLoginStatus() {
        viewModelScope.launch { _loginStatus.value = container.cookieExporter.loginStatus() }
    }

    fun clearBrowserLogin() {
        viewModelScope.launch {
            container.cookieExporter.clear()
            refreshLoginStatus()
            _messages.tryEmit("Logged out of all sites in the built-in browser")
        }
    }
}
