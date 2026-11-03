package com.example.nflix.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
    val homeShelves: List<String> = listOf("continue", "today", "suggested", "foryou", "latest"),
    val autoFallbackSource: Boolean = true,
    val adBlock: Boolean = true,
    val readerMode: String = "SCROLL",            // SCROLL | PAGE
    val readerKeepScreenOn: Boolean = true,
    val readerAutoHideChrome: Boolean = true,     // hide reader bars while reading; tap screen to show
    val readerAutoScroll: Boolean = false,        // SCROLL mode: hands-free page drift
    val readerAutoScrollSpeed: String = "NORMAL", // SLOW | NORMAL | FAST
    val readerEyeFilter: Float = 0f,              // warm overlay strength 0..0.45, 0 = off
    val shelvesMigrated: Boolean = false          // 1.1.0 one-time shelf-list merge done
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
            homeShelves = (p[HOME_SHELVES] ?: "continue,today,suggested,foryou,latest")
                .split(",").map { it.trim() }.filter { it.isNotEmpty() },
            autoFallbackSource = p[AUTO_FALLBACK] ?: true,
            adBlock = p[AD_BLOCK] ?: true,
            readerMode = p[READER_MODE] ?: "SCROLL",
            readerKeepScreenOn = p[READER_KEEP_ON] ?: true,
            readerAutoHideChrome = p[READER_AUTOHIDE] ?: true,
            readerAutoScroll = p[READER_AUTOSCROLL] ?: false,
            readerAutoScrollSpeed = p[READER_AUTOSCROLL_SPEED] ?: "NORMAL",
            readerEyeFilter = p[READER_EYE_FILTER] ?: 0f,
            shelvesMigrated = p[SHELVES_MIGRATED] ?: false
        )
    }

    suspend fun set(key: Preferences.Key<Boolean>, value: Boolean) {
        store.edit { it[key] = value }
    }

    suspend fun setString(key: Preferences.Key<String>, value: String) {
        store.edit { it[key] = value }
    }

    suspend fun setFloat(key: Preferences.Key<Float>, value: Float) {
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

    /**
     * C2 (K9): last background-check stamp per tracked title, stored as
     * "titleId=epochSec;titleId=epochSec" (ids contain ':' but never ';'
     * or '='). The worker rotates fairly through titles older than the
     * per-run batch instead of a hard take(30) that starved the tail.
     */
    val workerStamps: Flow<Map<String, Long>> = store.data.map { p ->
        (p[WORKER_STAMPS] ?: "").split(";")
            .mapNotNull { entry ->
                val idx = entry.lastIndexOf('=')
                if (idx <= 0) null
                else entry.substring(0, idx) to entry.substring(idx + 1).toLongOrNull()
            }
            .mapNotNull { (id, sec) -> sec?.let { id to it } }
            .toMap()
    }

    suspend fun setWorkerStamps(stamps: Map<String, Long>) {
        store.edit { p ->
            p[WORKER_STAMPS] = stamps.entries.joinToString(";") { (id, sec) -> "$id=$sec" }
        }
    }

    /**
     * Đợt 4 (cobe): last API host that actually answered, cached so a host
     * rotation (the site already changed its web domain once) self-heals —
     * CobeSource rediscovers from the site's JS bundles and stores the new
     * host here. Empty = never discovered; the source uses its fallback.
     */
    val cobeApiHost: Flow<String> = store.data.map { p -> p[COBE_API_HOST] ?: "" }

    suspend fun setCobeApiHost(host: String) {
        store.edit { it[COBE_API_HOST] = host }
    }

    /**
     * 1.2.0: the Manhwa genre index (TruyenQQ shelf items + their detail
     * tags, built once and refreshed weekly — the shelf is 220 detail
     * fetches on slow hosts). Stored as one JSON blob with its build time.
     */
    val manhwaIndexJson: Flow<String> = store.data.map { p -> p[MANHWA_INDEX] ?: "" }
    val manhwaIndexBuiltAtMs: Flow<Long> = store.data.map { p ->
        (p[MANHWA_INDEX_AT] ?: "0").toLongOrNull() ?: 0L
    }

    suspend fun setManhwaIndex(json: String) {
        store.edit {
            it[MANHWA_INDEX] = json
            it[MANHWA_INDEX_AT] = System.currentTimeMillis().toString()
        }
    }

    /**
     * 1.3.4: timestamp of the last FAILED or cut-short index build.
     * A failed build used to leave no trace, so the next tab visit
     * restarted the whole 220-detail build from zero (Lý Thấm's
     * reader dossier) — the ViewModel now enforces a 6h cooldown
     * after a failed attempt before trying again.
     */
    val manhwaIndexAttemptMs: Flow<Long> = store.data.map { p ->
        (p[MANHWA_INDEX_ATTEMPT] ?: "0").toLongOrNull() ?: 0L
    }

    suspend fun setManhwaIndexAttempt() {
        store.edit { it[MANHWA_INDEX_ATTEMPT] = System.currentTimeMillis().toString() }
    }

    /**
     * 1.3.4: the update version the user dismissed with "Để sau" — the
     * startup dialog stays quiet for that version (the Home reminder
     * line remains); a newer release, or a forced one, pops again.
     */
    val dismissedUpdateVersion: Flow<String> = store.data.map { p ->
        p[UPDATE_DISMISSED] ?: ""
    }

    suspend fun setDismissedUpdateVersion(version: String) {
        store.edit { it[UPDATE_DISMISSED] = version }
    }

    /**
     * 1.2.0: cached Donghua slug pool (comma-joined). The Anime tab uses
     * it to exclude donghua series Cobe also carries; a warm cache keeps
     * the exclusion active while a fresh pool loads in the background.
     */
    val donghuaSlugs: Flow<String> = store.data.map { p -> p[DONGHUA_SLUGS] ?: "" }

    suspend fun setDonghuaSlugs(slugs: Collection<String>) {
        store.edit { it[DONGHUA_SLUGS] = slugs.joinToString(",") }
    }

    /**
     * 1.2.0 fix: persistent per-slug cache of FULL Cobe categories for
     * Anime-pool titles, enriched once from the by-slug API (the web
     * listing payload only embeds the "hoat-hinh" umbrella). One JSON
     * blob: { slug: [[label, slug], ...] } — each title is fetched at
     * most once, ever; the blob is rewritten only when new entries land.
     */
    val animeGenreCacheJson: Flow<String> = store.data.map { p -> p[ANIME_GENRE_CACHE] ?: "" }

    suspend fun setAnimeGenreCache(json: String) {
        store.edit { it[ANIME_GENRE_CACHE] = json }
    }

    /**
     * Country-law fix (2026-11-01): Cobe country-page slug pools used to
     * classify the Anime pool (Cobe records carry no country field).
     * One JSON blob: { countrySlug: {"done": bool, "slugs": "a,b,c"} } —
     * "done" marks a completed full sweep; later opens only refresh the
     * head pages. ~13k slugs ≈ 0.4 MB.
     */
    val animeCountryPoolsJson: Flow<String> = store.data.map { p -> p[ANIME_COUNTRY_POOLS] ?: "" }

    suspend fun setAnimeCountryPools(json: String) {
        store.edit { it[ANIME_COUNTRY_POOLS] = json }
    }

    /**
     * Country-law fix (2026-11-01): persistent per-slug KKPhim poster
     * fallback for Cobe items whose own cover art is dead at the source.
     * One JSON blob: { slug: posterUrl } — each title is looked up at
     * most once, ever; failures are not stored.
     */
    val animePosterCacheJson: Flow<String> = store.data.map { p -> p[ANIME_POSTER_CACHE] ?: "" }

    suspend fun setAnimePosterCache(json: String) {
        store.edit { it[ANIME_POSTER_CACHE] = json }
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
        val READER_AUTOSCROLL = booleanPreferencesKey("reader_autoscroll")
        val READER_AUTOSCROLL_SPEED = stringPreferencesKey("reader_autoscroll_speed")
        val READER_EYE_FILTER = floatPreferencesKey("reader_eye_filter")
        val SHELVES_MIGRATED = booleanPreferencesKey("shelves_migrated_v110")
        val SEARCH_HISTORY = stringPreferencesKey("search_history")
        val WORKER_STAMPS = stringPreferencesKey("worker_check_stamps")
        val COBE_API_HOST = stringPreferencesKey("cobe_api_host")
        val MANHWA_INDEX = stringPreferencesKey("manhwa_index_json")
        val MANHWA_INDEX_AT = stringPreferencesKey("manhwa_index_built_at")
        val MANHWA_INDEX_ATTEMPT = stringPreferencesKey("manhwa_index_last_failed_attempt")
        val UPDATE_DISMISSED = stringPreferencesKey("update_dismissed_version")
        val DONGHUA_SLUGS = stringPreferencesKey("donghua_slug_pool")
        val ANIME_GENRE_CACHE = stringPreferencesKey("anime_genre_cache_json")
        val ANIME_COUNTRY_POOLS = stringPreferencesKey("anime_country_pools_json")
        val ANIME_POSTER_CACHE = stringPreferencesKey("anime_poster_cache_json")
    }
}
