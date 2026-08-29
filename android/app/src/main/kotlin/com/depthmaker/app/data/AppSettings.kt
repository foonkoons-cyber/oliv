package com.depthmaker.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.depthmaker.app.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: androidx.datastore.core.DataStore<Preferences> by
    preferencesDataStore(name = "depthmaker_settings")

data class Settings(
    val serverUrl: String,
    val token: String,
    /** "vits" (Apache-2.0, commercial-safe) or "vitl" (CC-BY-NC-4.0). */
    val model: String,
    /** "mp4" | "png16" | "npz" */
    val format: String
)

object SettingsKeys {
    val SERVER_URL = stringPreferencesKey("server_url")
    val TOKEN = stringPreferencesKey("token")
    val MODEL = stringPreferencesKey("model")
    val FORMAT = stringPreferencesKey("format")
}

class SettingsRepository(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            serverUrl = p[SettingsKeys.SERVER_URL] ?: BuildConfig.DEFAULT_SERVER_URL,
            token = p[SettingsKeys.TOKEN] ?: BuildConfig.DEFAULT_TOKEN,
            model = p[SettingsKeys.MODEL] ?: "vits",
            format = p[SettingsKeys.FORMAT] ?: "mp4"
        )
    }

    suspend fun current(): Settings {
        val p = context.dataStore.data.first()
        return Settings(
            serverUrl = p[SettingsKeys.SERVER_URL] ?: BuildConfig.DEFAULT_SERVER_URL,
            token = p[SettingsKeys.TOKEN] ?: BuildConfig.DEFAULT_TOKEN,
            model = p[SettingsKeys.MODEL] ?: "vits",
            format = p[SettingsKeys.FORMAT] ?: "mp4"
        )
    }

    suspend fun setServerUrl(value: String) = context.dataStore.edit { it[SettingsKeys.SERVER_URL] = value }
    suspend fun setToken(value: String) = context.dataStore.edit { it[SettingsKeys.TOKEN] = value }
    suspend fun setModel(value: String) = context.dataStore.edit { it[SettingsKeys.MODEL] = value }
    suspend fun setFormat(value: String) = context.dataStore.edit { it[SettingsKeys.FORMAT] = value }

    companion object {
        /** Android blocks cleartext by default, so an http:// URL fails silently. */
        fun isValidServerUrl(url: String): Boolean =
            url.trim().startsWith("https://") && url.trim().length > "https://".length + 2
    }
}
