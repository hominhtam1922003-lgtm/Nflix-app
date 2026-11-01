package com.example.nflix.data

import com.example.nflix.NflixApp
import com.example.nflix.data.local.TitleEntity
import com.example.nflix.data.local.UserStateEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Export / import of the user's library ("tủ phim") as ONE JSON file
 * (đợt 3, mục B3). Everything already lives in Room — this only serializes
 * it with org.json (no new dependency). Import errors surface as clean
 * Vietnamese messages, never raw parser codes.
 */
object BackupStore {

    private const val FORMAT = "nflix-backup"
    private const val FORMAT_VERSION = 1

    suspend fun exportJson(app: NflixApp): String {
        val states = app.database.userStateDao().allOnce()
        val titles = app.database.titleDao().getByIds(states.map { it.titleId })
            .associateBy { it.id }
        val items = JSONArray()
        states.forEach { st ->
            val entry = JSONObject()
            titles[st.titleId]?.let { t ->
                entry.put("title", JSONObject()
                    .put("id", t.id)
                    .put("family", t.family)
                    .put("sourceId", t.sourceId)
                    .put("slug", t.slug)
                    .put("name", t.name)
                    .put("originName", t.originName)
                    .put("posterUrl", t.posterUrl)
                    .put("thumbUrl", t.thumbUrl)
                    .put("quality", t.quality)
                    .put("versionBadge", t.versionBadge)
                    .put("latestLabel", t.latestLabel)
                    .put("episodeCount", t.episodeCount)
                    .put("year", t.year)
                    .put("updatedEpochSec", t.updatedEpochSec)
                )
            }
            entry.put("state", JSONObject()
                .put("titleId", st.titleId)
                .put("favorite", st.favorite)
                .put("watchLater", st.watchLater)
                .put("following", st.following)
                .put("notifyOn", st.notifyOn)
                .put("lastUnitLabel", st.lastUnitLabel)
                .put("lastUnitNumber", st.lastUnitNumber.toDouble())
                .put("positionMs", st.positionMs)
                .put("durationMs", st.durationMs)
                .put("pageIndex", st.pageIndex)
                .put("pageCount", st.pageCount)
                .put("bookmarkChapter", st.bookmarkChapter.toDouble())
                .put("bookmarkPage", st.bookmarkPage)
                .put("progressFraction", st.progressFraction.toDouble())
                .put("finished", st.finished)
                .put("seenCount", st.seenCount)
                .put("latestCount", st.latestCount)
                .put("watchedUnits", st.watchedUnits)
                .put("lastTouchedEpochSec", st.lastTouchedEpochSec)
            )
            items.put(entry)
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", FORMAT_VERSION)
            .put("exportedAtEpochSec", System.currentTimeMillis() / 1000)
            .put("items", items)
            .toString(2)
    }

    /**
     * Restores titles + states from a backup file (REPLACE by id).
     * Returns how many entries were restored.
     */
    suspend fun importJson(app: NflixApp, text: String): Int {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("File không đọc được — hãy chọn đúng file đã xuất từ Nflix.")
        }
        if (root.optString("format") != FORMAT) {
            throw IllegalArgumentException("Đây không phải file sao lưu của Nflix.")
        }
        val items = root.optJSONArray("items")
            ?: throw IllegalArgumentException("File sao lưu thiếu dữ liệu — hãy xuất lại rồi thử lần nữa.")
        var restored = 0
        for (i in 0 until items.length()) {
            val entry = items.optJSONObject(i) ?: continue
            val stJson = entry.optJSONObject("state") ?: continue
            val titleId = stJson.optString("titleId")
            if (titleId.isBlank()) continue
            val tJson = entry.optJSONObject("title")
            // Metadata may be absent in a hand-trimmed file: fall back to
            // what the id itself encodes ("<FAMILY>:<source>:<slug>").
            val parts = titleId.split(":", limit = 3)
            val title = TitleEntity(
                id = titleId,
                family = tJson?.optString("family")?.takeIf { it.isNotBlank() }
                    ?: parts.getOrNull(0) ?: "PHIM",
                sourceId = tJson?.optString("sourceId")?.takeIf { it.isNotBlank() }
                    ?: parts.getOrNull(1) ?: "",
                slug = tJson?.optString("slug")?.takeIf { it.isNotBlank() }
                    ?: parts.getOrNull(2) ?: "",
                name = tJson?.optString("name")?.takeIf { it.isNotBlank() }
                    ?: parts.getOrNull(2) ?: titleId,
                originName = tJson?.optString("originName") ?: "",
                posterUrl = tJson?.optString("posterUrl") ?: "",
                thumbUrl = tJson?.optString("thumbUrl") ?: "",
                quality = tJson?.optString("quality") ?: "",
                versionBadge = tJson?.optString("versionBadge") ?: "",
                latestLabel = tJson?.optString("latestLabel") ?: "",
                episodeCount = tJson?.optInt("episodeCount") ?: 0,
                year = tJson?.optInt("year") ?: 0,
                updatedEpochSec = tJson?.optLong("updatedEpochSec") ?: 0
            )
            app.database.titleDao().upsert(title)
            app.database.userStateDao().upsert(
                UserStateEntity(
                    titleId = titleId,
                    favorite = stJson.optBoolean("favorite"),
                    watchLater = stJson.optBoolean("watchLater"),
                    following = stJson.optBoolean("following"),
                    notifyOn = stJson.optBoolean("notifyOn"),
                    lastUnitLabel = stJson.optString("lastUnitLabel"),
                    lastUnitNumber = stJson.optDouble("lastUnitNumber", 0.0).toFloat(),
                    positionMs = stJson.optLong("positionMs"),
                    durationMs = stJson.optLong("durationMs"),
                    pageIndex = stJson.optInt("pageIndex"),
                    pageCount = stJson.optInt("pageCount"),
                    bookmarkChapter = stJson.optDouble("bookmarkChapter", 0.0).toFloat(),
                    bookmarkPage = stJson.optInt("bookmarkPage"),
                    progressFraction = stJson.optDouble("progressFraction", 0.0).toFloat(),
                    finished = stJson.optBoolean("finished"),
                    seenCount = stJson.optInt("seenCount"),
                    latestCount = stJson.optInt("latestCount"),
                    watchedUnits = stJson.optString("watchedUnits"),
                    lastTouchedEpochSec = stJson.optLong("lastTouchedEpochSec")
                )
            )
            restored++
        }
        if (restored == 0) {
            throw IllegalArgumentException("Không có mục nào hợp lệ trong file này.")
        }
        return restored
    }
}
