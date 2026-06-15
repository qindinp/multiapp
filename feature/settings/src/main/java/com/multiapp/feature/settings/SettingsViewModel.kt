package com.multiapp.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multiapp.core.common.formatBytes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val appVersion: String = "1.0.0",
    val packageName: String = "com.multiapp.app",
    val buildType: String = "debug",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: Language = Language.SYSTEM,
    val cacheSize: String = "",
    val isCacheClearing: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        observeSettings()
        refreshCacheSize()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.themeMode.collect { mode ->
                _uiState.update { it.copy(themeMode = mode) }
            }
        }
        viewModelScope.launch {
            settingsRepository.language.collect { lang ->
                _uiState.update { it.copy(language = lang) }
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(mode)
        }
    }

    fun setLanguage(language: Language) {
        viewModelScope.launch {
            settingsRepository.setLanguage(language)
        }
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            val size = settingsRepository.getCacheSize()
            _uiState.update { it.copy(cacheSize = formatBytes(size)) }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCacheClearing = true) }
            settingsRepository.clearCache()
            _uiState.update { it.copy(isCacheClearing = false) }
            refreshCacheSize()
        }
    }
}
