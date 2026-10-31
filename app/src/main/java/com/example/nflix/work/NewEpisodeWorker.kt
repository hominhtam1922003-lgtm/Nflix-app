package com.example.nflix.work

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.nflix.MainActivity
import com.example.nflix.NflixApp
import com.example.nflix.R
import com.example.nflix.data.toItem
import kotlinx.coroutines.flow.first

/**
 * Background new-episode checker (design: "Kiểm tra tập mới — chạy nền").
 * For every followed title, re-reads its source detail; when the source has
 * more units than last seen, updates latestCount (which drives the orange
 * "+N" pills and the bell dot) and posts ONE summary notification.
 */
class NewEpisodeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as NflixApp
        val settings = app.settings.flow.first()
        if (!settings.notifyNewEpisode) return Result.success()

        val states = app.database.userStateDao().followingOnce().take(30)
        val updatedNames = mutableListOf<String>()
        for (st in states) {
            val entity = app.database.titleDao().observe(st.titleId).first() ?: continue
            val item = entity.toItem()
            val total = runCatching {
                val detail = when (item.sourceId) {
                    "kkphim" -> app.kkPhim.detail(item.slug)
                    "hhkungfu" -> app.hhKungfu.detail(item.slug)
                    "nettruyen" -> app.netTruyen.detail(item.slug)
                    else -> app.truyenQq.detail(item.slug)
                }
                maxOf(detail.totalCount, detail.item.episodeCount)
            }.getOrDefault(0)
            if (total > st.latestCount) {
                app.database.userStateDao().upsert(st.copy(latestCount = total))
                updatedNames += item.name
            }
        }
        if (updatedNames.isNotEmpty()) {
            notifyNewEpisodes(app, updatedNames, settings.lockScreenNotif)
        }
        return Result.success()
    }

    private fun notifyNewEpisodes(context: Context, names: List<String>, lockScreenVisible: Boolean) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (names.size == 1) {
            "${names.first()} vừa có tập/chap mới"
        } else {
            "${names.size} bộ vừa có tập/chap mới: ${names.take(3).joinToString(", ")}"
        }
        val notification = NotificationCompat.Builder(context, NflixApp.CHANNEL_NEW_EPISODES)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("Có tập mới")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setVisibility(
                if (lockScreenVisible) NotificationCompat.VISIBILITY_PUBLIC
                else NotificationCompat.VISIBILITY_SECRET
            )
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val NOTIFICATION_ID = 2001
    }
}
