package com.example.nflix

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.room.Room
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.nflix.data.SettingsStore
import com.example.nflix.data.local.NflixDatabase
import com.example.nflix.data.source.PhimCobeSource
import com.example.nflix.data.source.HhKungfuSource
import com.example.nflix.data.source.MergedPhimSource
import com.example.nflix.data.source.NetTruyenSource
import com.example.nflix.data.source.TruyenQqSource
import com.example.nflix.work.NewEpisodeWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class NflixApp : Application(), ImageLoaderFactory {

    val database: NflixDatabase by lazy {
        Room.databaseBuilder(this, NflixDatabase::class.java, "nflix.db")
            .addMigrations(com.example.nflix.data.local.MIGRATION_2_3)
            .fallbackToDestructiveMigration()
            .build()
    }

    val phimcobe: PhimCobeSource by lazy { PhimCobeSource() }

    /**
     * V2 (A6): the video cache shared by the player (write-through
     * streaming) and the next-episode prefetcher. Sized generously per
     * the user's rule — the app may be heavy as long as playback is
     * best-quality and fast (the 30 GB data budget is the ceiling, LRU
     * eviction keeps this cache at 2 GB).
     */
    val videoCache: androidx.media3.datasource.cache.SimpleCache by lazy {
        androidx.media3.datasource.cache.SimpleCache(
            java.io.File(cacheDir, "nflix_video_cache"),
            androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(
                2L * 1024 * 1024 * 1024
            ),
            androidx.media3.database.StandaloneDatabaseProvider(this)
        )
    }

    /** Phim/Anime routing front — PhimCobe since 1.3.5 (Cobe + KK dropped by user order). */
    val mergedPhim: MergedPhimSource by lazy { MergedPhimSource(phimcobe) }

    /** 1.2.0: Anime tab catalog rules over the Cobe animation pool. */
    val animeCatalog: com.example.nflix.data.AnimeCatalog by lazy {
        com.example.nflix.data.AnimeCatalog(phimcobe, hhKungfu, settings)
    }
    val truyenQq: TruyenQqSource by lazy { TruyenQqSource() }
    val hhKungfu: HhKungfuSource by lazy { HhKungfuSource() }
    val netTruyen: NetTruyenSource by lazy { NetTruyenSource() }
    val settings: SettingsStore by lazy { SettingsStore(this) }

    /**
     * 1.3.4: true while the reader overlay is on screen (set by
     * MainActivity). Background heavy work — the Manhwa index build —
     * defers while the user is reading instead of fighting the
     * reader for CPU and the shared TruyenQQ client (Dương Tử S2).
     */
    @Volatile
    var readerOpen: Boolean = false

    /**
     * 1.3.4: in-app update state, app-wide so the shell's dialog and
     * Home's reminder line read the same truth. [updateInfo] is the
     * newest release found by UpdateManager.check (null = none);
     * [updateDialogOpen] is set by the startup check and by Home's
     * reminder line.
     */
    val updateInfo = kotlinx.coroutines.flow.MutableStateFlow<
        com.example.nflix.data.UpdateInfo?>(null)
    val updateDialogOpen = kotlinx.coroutines.flow.MutableStateFlow(false)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Heal legacy "Có tập mới" baselines: rows tracked before the baseline
        // rule existed can carry seenCount = 0 with a full back catalog in
        // latestCount, which displayed as "+132 chap mới" (user report
        // 2026-11-01). First sight of the current count IS the baseline —
        // only units released after tracking may ever count as new.
        appScope.launch {
            runCatching {
                val dao = database.userStateDao()
                dao.trackedOnce()
                    .filter { it.seenCount == 0 && it.latestCount > 0 }
                    .forEach { dao.upsert(it.copy(seenCount = it.latestCount)) }
            }
        }
        // Keep the background check schedule in sync with the user's setting.
        appScope.launch {
            settings.flow
                .map { it.checkInterval to it.notifyNewEpisode }
                .distinctUntilChanged()
                .collect { (interval, notify) -> scheduleChecks(interval, notify) }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_NEW_EPISODES,
                "Tập mới",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Báo khi bộ đang theo dõi có tập/chap mới" }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun scheduleChecks(interval: String, notify: Boolean) {
        val workManager = WorkManager.getInstance(this)
        if (!notify || interval == "ON_OPEN") {
            workManager.cancelUniqueWork(WORK_EPISODE_CHECKS)
            return
        }
        val request = when (interval) {
            "HOURLY" -> PeriodicWorkRequestBuilder<NewEpisodeWorker>(1, TimeUnit.HOURS)
            "SIX_HOURS" -> PeriodicWorkRequestBuilder<NewEpisodeWorker>(6, TimeUnit.HOURS)
            else -> PeriodicWorkRequestBuilder<NewEpisodeWorker>(15, TimeUnit.MINUTES)
        }.build()
        workManager.enqueueUniquePeriodicWork(
            WORK_EPISODE_CHECKS,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    companion object {
        const val CHANNEL_NEW_EPISODES = "nflix_new_episodes"
        const val WORK_EPISODE_CHECKS = "nflix_episode_checks"
    }

    /**
     * App-wide image loader. Comic CDNs are Referer-gated:
     * image1.kcgsbok.com (NetTruyen) 403s without Referer: nettruyen.gg,
     * cc3t.net (TruyenQQ) expects its own origin — add both rules here.
     */
    override fun newImageLoader(): ImageLoader {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // 1.3.4: whole-request ceiling for image fetches too
            // (was missing — network audit §callTimeout).
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                val builder = req.newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) Nflix/1.0")
                when {
                    // 1.3.6: PhimCobe posters load from the nguonc
                    // origin directly (the site proxy rejects that
                    // host) — nguonc's CDN expects its own Referer.
                    req.url.host.contains("nguonc.com") ->
                        builder.header("Referer", "https://nguonc.com/")
                    req.url.host.contains("kcgsbok.com") ->
                        builder.header("Referer", "https://nettruyen.gg/")
                    req.url.host.contains("cc3t.net") ->
                        builder.header("Referer", "https://nettruyenqq.org/")
                    req.url.host.contains("nettruyenqq") ||
                        req.url.host.contains("truyentranhqq") ->
                        builder.header("Referer", "https://nettruyenqq.org/")
                }
                chain.proceed(builder.build())
            }
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .crossfade(true)
            .build()
    }
}
