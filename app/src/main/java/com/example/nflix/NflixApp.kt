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
import com.example.nflix.data.source.HhKungfuSource
import com.example.nflix.data.source.KkPhimSource
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
            .fallbackToDestructiveMigration()
            .build()
    }

    val kkPhim: KkPhimSource by lazy { KkPhimSource() }
    val truyenQq: TruyenQqSource by lazy { TruyenQqSource() }
    val hhKungfu: HhKungfuSource by lazy { HhKungfuSource() }
    val netTruyen: NetTruyenSource by lazy { NetTruyenSource() }
    val settings: SettingsStore by lazy { SettingsStore(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
            .addInterceptor { chain ->
                val req = chain.request()
                val builder = req.newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) Nflix/1.0")
                when {
                    req.url.host.contains("kcgsbok.com") ->
                        builder.header("Referer", "https://nettruyen.gg/")
                    req.url.host.contains("cc3t.net") ->
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
