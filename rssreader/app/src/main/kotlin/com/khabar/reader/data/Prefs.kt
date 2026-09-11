package com.khabar.reader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "khabar_prefs")

enum class ThemeMode { System, Light, Dark }

data class Prefs(
    val themeMode: ThemeMode = ThemeMode.System,
    /** Reader body text multiplier, 0.85 … 1.6. */
    val textScale: Float = 1.0f,
    /** 0 = background refresh off. WorkManager will not go below one hour here anyway. */
    val refreshIntervalHours: Int = 6,
    val wifiOnly: Boolean = false,
    /** Articles kept per feed before pruning. Bookmarks are never pruned. */
    val keepPerFeed: Int = 200,
    val markReadOnOpen: Boolean = true,
    val showImages: Boolean = true
)

class PrefsRepository(private val context: Context) {

    val prefs: Flow<Prefs> = context.dataStore.data.map { it.toPrefs() }

    suspend fun current(): Prefs = context.dataStore.data.first().toPrefs()

    suspend fun setThemeMode(v: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME] = v.name }
    }

    suspend fun setTextScale(v: Float) {
        context.dataStore.edit { it[Keys.TEXT_SCALE] = v.coerceIn(0.85f, 1.6f) }
    }

    suspend fun setRefreshIntervalHours(v: Int) {
        context.dataStore.edit { it[Keys.REFRESH_HOURS] = v.coerceIn(0, 48) }
    }

    suspend fun setWifiOnly(v: Boolean) {
        context.dataStore.edit { it[Keys.WIFI_ONLY] = v }
    }

    suspend fun setKeepPerFeed(v: Int) {
        context.dataStore.edit { it[Keys.KEEP_PER_FEED] = v.coerceIn(50, 2000) }
    }

    suspend fun setMarkReadOnOpen(v: Boolean) {
        context.dataStore.edit { it[Keys.MARK_READ_ON_OPEN] = v }
    }

    suspend fun setShowImages(v: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_IMAGES] = v }
    }

    private fun Preferences.toPrefs(): Prefs {
        val defaults = Prefs()
        return Prefs(
            themeMode = this[Keys.THEME]?.let { name ->
                // An unknown value can only come from a downgrade; fall back rather than crash.
                ThemeMode.entries.firstOrNull { it.name == name }
            } ?: defaults.themeMode,
            textScale = this[Keys.TEXT_SCALE] ?: defaults.textScale,
            refreshIntervalHours = this[Keys.REFRESH_HOURS] ?: defaults.refreshIntervalHours,
            wifiOnly = this[Keys.WIFI_ONLY] ?: defaults.wifiOnly,
            keepPerFeed = this[Keys.KEEP_PER_FEED] ?: defaults.keepPerFeed,
            markReadOnOpen = this[Keys.MARK_READ_ON_OPEN] ?: defaults.markReadOnOpen,
            showImages = this[Keys.SHOW_IMAGES] ?: defaults.showImages
        )
    }

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val TEXT_SCALE = floatPreferencesKey("text_scale")
        val REFRESH_HOURS = intPreferencesKey("refresh_hours")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val KEEP_PER_FEED = intPreferencesKey("keep_per_feed")
        val MARK_READ_ON_OPEN = booleanPreferencesKey("mark_read_on_open")
        val SHOW_IMAGES = booleanPreferencesKey("show_images")
    }
}
