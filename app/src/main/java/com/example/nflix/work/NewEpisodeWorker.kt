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
 * more units than last seen, updates latestCount and posts ONE summary
 * notification. Since 2026-11-01 the counts are bookkeeping only — no UI
 * surface displays them (the user's "+N" removal order); this worker's
 * notification is the one place new episodes are announced, and its text
 * names titles without any "+N" count.
 *
 * C2 (K9): the old hard take(30) meant every tracked title past #30 was
 * NEVER checked. A run still checks at most [BATCH_SIZE] titles, but the
 * batch is chosen by fair rotation — the least-recently-checked titles go
 * first (stamps in DataStore), so over successive runs every title is
 * covered. Baseline/notification logic is unchanged.
 */
class NewEpisodeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as NflixApp
        val settings = app.settings.flow.first()
        if (!settings.notifyNewEpisode) return Result.success()

        val dao = app.database.userStateDao()
        val all = dao.trackedOnce()
        val stamps = app.settings.workerStamps.first()
        // Never-checked titles (stamp 0) first, then stalest-checked.
        val states = all
            .sortedBy { stamps[it.titleId] ?: 0L }
            .take(BATCH_SIZE)
        android.util.Log.d(
            TAG,
            "Đang canh tổng ${all.size} bộ; lượt này kiểm tra ${states.size} bộ"
        )
        val updatedNames = mutableListOf<String>()
        for (st in states) {
            val entity = app.database.titleDao().observe(st.titleId).first() ?: continue
            val item = entity.toItem()
            val total = runCatching {
                val detail = when (item.sourceId) {
                    // Đợt 4: Phim/Anime totals come from the MERGED pool —
                    // when cobe releases an episode first, the bell catches
                    // it. Baseline/seen semantics are unchanged.
                    "kkphim", "cobe" -> app.mergedPhim.detailFor(item)
                    "hhkungfu" -> app.hhKungfu.detail(item.slug)
                    "nettruyen" -> app.netTruyen.detail(item.slug)
                    else -> app.truyenQq.detail(item.slug, item.family)
                }
                maxOf(detail.totalCount, detail.item.episodeCount)
            }.getOrDefault(0)
            if (total <= 0) continue
            when {
                // First check after tracking started: everything already out
                // is the BASELINE — it must never be reported as new.
                st.seenCount == 0 && st.latestCount == 0 ->
                    dao.upsert(st.copy(latestCount = total, seenCount = total))
                // Legacy row that never got a baseline — heal silently.
                st.seenCount == 0 -> {
                    val base = maxOf(st.latestCount, total)
                    dao.upsert(st.copy(latestCount = base, seenCount = base))
                }
                // Genuinely new units beyond the baseline.
                total > st.latestCount -> {
                    dao.upsert(st.copy(latestCount = total))
                    if (total > st.seenCount) updatedNames += item.name
                }
            }
        }
        if (updatedNames.isNotEmpty()) {
            notifyNewEpisodes(app, updatedNames, settings.lockScreenNotif)
        }
        // C2: stamp every attempted title so the next run rotates to the
        // ones left behind; prune stamps of titles no longer tracked.
        val nowSec = System.currentTimeMillis() / 1000
        val trackedIds = all.map { it.titleId }.toSet()
        val newStamps = stamps.toMutableMap()
        newStamps.keys.retainAll(trackedIds)
        states.forEach { newStamps[it.titleId] = nowSec }
        runCatching { app.settings.setWorkerStamps(newStamps) }
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
            // Grouped form lists the titles themselves — no counts of any
            // kind ("+N" or title tallies) per the user's 2026-11-01 order.
            "Có tập/chap mới: ${names.take(3).joinToString(", ")}" +
                if (names.size > 3) "…" else ""
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
        private const val TAG = "NewEpisodeWorker"

        /** Max titles checked per run — rotation (not a hard cap) covers the rest. */
        private const val BATCH_SIZE = 30
    }
}
