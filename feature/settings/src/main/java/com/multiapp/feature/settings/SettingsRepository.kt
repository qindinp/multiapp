package com.multiapp.feature.settings

import android.content.Context
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode(val value: Int) {
    SYSTEM(0),
    LIGHT(1),
    DARK(2);

    companion object {
        fun fromValue(value: Int) = entries.firstOrNull { it.value == value } ?: SYSTEM
    }
}

enum class Language(val value: Int, val code: String) {
    SYSTEM(0, ""),
    CHINESE(1, "zh"),
    ENGLISH(2, "en");

    companion object {
        fun fromValue(value: Int) = entries.firstOrNull { it.value == value } ?: SYSTEM
    }
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private val KEY_THEME_MODE = intPreferencesKey("theme_mode")
        private val KEY_LANGUAGE = intPreferencesKey("language")
    }

    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map { prefs ->
        ThemeMode.fromValue(prefs[KEY_THEME_MODE] ?: ThemeMode.SYSTEM.value)
    }

    val language: Flow<Language> = context.settingsDataStore.data.map { prefs ->
        Language.fromValue(prefs[KEY_LANGUAGE] ?: Language.SYSTEM.value)
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode.value
        }
    }

    suspend fun setLanguage(language: Language) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_LANGUAGE] = language.value
        }
        applyLanguage(language)
    }

    fun getCacheSize(): Long {
        return try {
            getDirSize(context.cacheDir)
        } catch (_: Exception) {
            0L
        }
    }

    suspend fun clearCache() {
        try {
            context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun getDirSize(dir: java.io.File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { getDirSize(it) } ?: 0L
    }

    private fun applyLanguage(language: Language) {
        val localeTag = language.code
        if (Build.VERSION.SDK_INT >= 33) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            localeManager?.applicationLocales = LocaleList.forLanguageTags(localeTag)
            return
        }
        @Suppress("DEPRECATION")
        val config = context.resources.configuration
        @Suppress("DEPRECATION")
        config.setLocales(LocaleList.forLanguageTags(localeTag))
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }
}
