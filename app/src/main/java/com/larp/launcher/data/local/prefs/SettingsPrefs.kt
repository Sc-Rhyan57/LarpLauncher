package com.larp.launcher.data.local.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.larpSettingsDataStore by preferencesDataStore(name = "larp_settings")

class SettingsPrefs(private val context: Context) {

    val dynamicColor: Flow<Boolean> = context.larpSettingsDataStore.data
        .map { it[KEY_DYNAMIC_COLOR] ?: true }

    val darkMode: Flow<String> = context.larpSettingsDataStore.data
        .map { it[KEY_DARK_MODE] ?: "system" }

    val apiUrl: Flow<String> = context.larpSettingsDataStore.data
        .map { it[KEY_API_URL] ?: DEFAULT_API_URL }

    val devMode: Flow<Boolean> = context.larpSettingsDataStore.data
        .map { it[KEY_DEV_MODE] ?: false }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.larpSettingsDataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    suspend fun setDarkMode(mode: String) {
        context.larpSettingsDataStore.edit { it[KEY_DARK_MODE] = mode }
    }

    suspend fun setApiUrl(url: String) {
        context.larpSettingsDataStore.edit { it[KEY_API_URL] = url.trim().trimEnd('/') }
    }

    suspend fun setDevMode(enabled: Boolean) {
        context.larpSettingsDataStore.edit { it[KEY_DEV_MODE] = enabled }
    }

    companion object {
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val KEY_DARK_MODE = stringPreferencesKey("dark_mode")
        private val KEY_API_URL = stringPreferencesKey("api_url")
        private val KEY_DEV_MODE = booleanPreferencesKey("dev_mode")
        const val DEFAULT_API_URL = "https://larp-api.workers.dev"
    }
}
