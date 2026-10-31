package com.example.nflix.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nflix_settings")

/** All user settings (Màn 5). Defaults match the design exactly. */
data class AppSettings(
    val qualityDefault: String = "CAO_NHAT",      // CAO_NHAT | P1080 | P720 | P480 | AUTO
    val versionPriority: String = "TM",           // TM | VS | LT — fallback order TM → VS → LT
    val autoFastestServer: Boolean = true,
    val autoNextEpisode: Boolean = true,
    val skipIntroOutro: Boolean = true,
    val pipEnabled: Boolean = true,
    val notifyNewEpisode: Boolean = true,
    val lockScreenNotif: Boolean = true,
    val checkInterval: String = "CONTINUOUS",     // CONTINUOUS | HOURLY | SIX_HOURS | ON_OPEN
    val homeShelves: List<String> = listOf("continue", "new", "suggested", "latest"),
    val autoFallbackSource: Boolean = true,
    val adBlock: Boolean = true,
    val readerMode: String = "SCROLL",            // SCROLL | PAGE
    val readerKeepScreenOn: Boolean = true,
    val readerAutoHideChrome: Boolean = true      // hide reader bars while reading; tap screen to show
)

class SettingsStore(context: Context) {

    private val store = context.applicationContext.dataStore

    val flow: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            qualityDefault = p[QUALITY] ?: "CAO_NHAT",
            versionPriority = p[VERSION_PRIORITY] ?: "TM",
            autoFastestServer = p[AUTO_SERVER] ?: true,
            autoNextEpisode = p[AUTO_NEXT] ?: true,
            skipIntroOutro = p[SKIP_INTRO] ?: true,
            pipEnabled = p[PIP] ?: true,
            notifyNewEpisode = p[NOTIFY_NEW] ?: true,
            lockScreenNotif = p[LOCK_NOTIF] ?: true,
            checkInterval = p[CHECK_INTERVAL] ?: "CONTINUOUS",
            homeShelves = (p[HOME_SHELVES] ?: "continue,new,suggested,latest")
                .split(",").map { it.trim() }.filter { it.isNotEmpty() },
            autoFallbackSource = p[AUTO_FALLBACK] ?: true,
            adBlock = p[AD_BLOCK] ?: true,
            readerMode = p[READER_MODE] ?: "SCROLL",
            readerKeepScreenOn = p[READER_KEEP_ON] ?: true,
            readerAutoHideChrome = p[READER_AUTOHIDE] ?: true
        )
    }

    suspend fun set(key: Preferences.Key<Boolean>, value: Boolean) {
        store.edit { it[key] = value }
    }

    suspend fun setString(key: Preferences.Key<String>, value: String) {
        store.edit { it[key] = value }
    }

    suspend fun setHomeShelves(shelves: List<String>) {
        store.edit { it[HOME_SHELVES] = shelves.joinToString(",") }
    }

    /** Search history (Màn 12), newest first, max 12 entries. */
    val searchHistory: Flow<List<String>> = store.data.map { p ->
        (p[SEARCH_HISTORY] ?: "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    suspend fun addSearchQuery(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        store.edit { p ->
            val current = (p[SEARCH_HISTORY] ?: "").split("\n")
                .map { it.trim() }.filter { it.isNotEmpty() && !it.equals(q, ignoreCase = true) }
            p[SEARCH_HISTORY] = (listOf(q) + current).take(12).joinToString("\n")
        }
    }

    suspend fun removeSearchQuery(query: String) {
        store.edit { p ->
            val current = (p[SEARCH_HISTORY] ?: "").split("\n")
                .map { it.trim() }.filter { it.isNotEmpty() && it != query }
            p[SEARCH_HISTORY] = current.joinToString("\n")
        }
    }

    suspend fun clearSearchHistory() {
        store.edit { it[SEARCH_HISTORY] = "" }
    }

    companion object {
        val QUALITY = stringPreferencesKey("quality_default")
        val VERSION_PRIORITY = stringPreferencesKey("version_priority")
        val AUTO_SERVER = booleanPreferencesKey("auto_fastest_server")
        val AUTO_NEXT = booleanPreferencesKey("auto_next_episode")
        val SKIP_INTRO = booleanPreferencesKey("skip_intro_outro")
        val PIP = booleanPreferencesKey("pip_enabled")
        val NOTIFY_NEW = booleanPreferencesKey("notify_new_episode")
        val LOCK_NOTIF = booleanPreferencesKey("lock_screen_notif")
        val CHECK_INTERVAL = stringPreferencesKey("check_interval")
        val HOME_SHELVES = stringPreferencesKey("home_shelves")
        val AUTO_FALLBACK = booleanPreferencesKey("auto_fallback_source")
        val AD_BLOCK = booleanPreferencesKey("ad_block")
        val READER_MODE = stringPreferencesKey("reader_mode")
        val READER_KEEP_ON = booleanPreferencesKey("reader_keep_screen_on")
        val READER_AUTOHIDE = booleanPreferencesKey("reader_autohide_chrome")
        val SEARCH_HISTORY = stringPreferencesKey("search_history")
    }
}
