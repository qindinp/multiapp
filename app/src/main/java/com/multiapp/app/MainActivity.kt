package com.multiapp.app

import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multiapp.core.designsystem.theme.MultiAppTheme
import com.multiapp.feature.settings.SettingsRepository
import com.multiapp.feature.settings.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestHighestRefreshRate()
        enableEdgeToEdge()
        setContent {
            val themeMode by settingsRepository.themeMode.collectAsStateWithLifecycle(
                initialValue = ThemeMode.SYSTEM
            )
            MultiAppTheme(
                darkTheme = when (themeMode) {
                    ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
            ) {
                MultiAppNavHost()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        requestHighestRefreshRate()
    }

    @Suppress("DEPRECATION")
    private fun requestHighestRefreshRate() {
        val currentDisplay = windowManager.defaultDisplay
        val currentMode = currentDisplay.mode
        val preferredMode = currentDisplay.supportedModes
            .asSequence()
            .filter {
                it.physicalWidth == currentMode.physicalWidth &&
                    it.physicalHeight == currentMode.physicalHeight
            }
            .sortedWith(
                compareByDescending<Display.Mode> { it.refreshRate }
                    .thenByDescending { it.modeId == currentMode.modeId }
                    .thenBy { it.modeId }
            )
            .firstOrNull()
            ?: return
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = preferredMode.modeId
            preferredRefreshRate = preferredMode.refreshRate
        }
    }
}
