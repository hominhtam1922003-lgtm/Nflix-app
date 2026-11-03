package com.example.nflix.data

import com.example.nflix.data.model.Family
import com.example.nflix.data.model.TitleItem
import com.example.nflix.data.source.TruyenQqSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * 1.2.0 (Khối C): the Manhwa genre index. TruyenQQ cannot combine two
 * filters server-side (measured: extra params are ignored), so the tab's
 * genre browsing runs LOCALLY over the source's own manhwa shelf
 * (/the-loai/manhwa, 11 pages = 220 titles — the source's listing cap).
 * Each shelf title's genre tags are read from its detail page once and
 * cached (DataStore, weekly refresh). Sample-verified 6/6: every shelf
 * title really carries the Manhwa tag — the chips below only ever
 * resolve to titles in this index, never to the whole catalog.
 */
data class ManhwaIndexEntry(
    val item: TitleItem,
    val genreSlugs: Set<String>,
    val audienceSlugs: Set<String>,
    val completed: Boolean
)

object ManhwaIndex {

    /** Content chips (Vietnamese label to canonical slug) — dossier table 3.1 minus Mafia (1 title). */
    val CONTENT_CHIPS: List<Pair<String, String>> = listOf(
        "Hành động" to "action",
        "Phiêu lưu" to "adventure",
        "Chuyển Sinh" to "chuyen-sinh",
        "Cổ Đại" to "co-dai",
        "Hài hước" to "comedy",
        "Ác quỷ" to "demons",
        "Trinh thám" to "detective",
        "Drama" to "drama",
        "Giả tưởng" to "fantasy",
        "Hoán đổi giới tính" to "gender-bender",
        "Hậu cung" to "harem",
        "Lịch sử" to "historical",
        "Kinh dị" to "horror",
        "Huyền Huyễn" to "huyen-huyen",
        "Dị giới" to "isekai",
        "Phép thuật" to "magic",
        "Võ thuật" to "martial-arts",
        "Quân sự" to "military",
        "Bí ẩn" to "mystery",
        "Ngôn Tình" to "ngon-tinh",
        "Truyện ngắn (oneshot)" to "one-shot",
        "Tâm lý" to "psychological",
        "Lãng mạn" to "romance",
        "Học đường" to "school-life",
        "Khoa học viễn tưởng" to "sci-fi",
        "Đời thường" to "slice-of-life",
        "Thể thao" to "sports",
        "Siêu nhiên" to "supernatural",
        "Bi kịch" to "tragedy",
        "Trọng Sinh" to "trong-sinh",
        "Xuyên Không" to "xuyen-khong"
    )

    /**
     * Audience chips — display labels in Vietnamese per the user's
     * 2026-11-01 ruling; the filter KEYS stay the source slugs.
     */
    val AUDIENCE_CHIPS: List<Pair<String, String>> = listOf(
        "Nam thiếu niên" to "shounen",
        "Nữ thiếu niên" to "shoujo",
        "Nam trưởng thành" to "seinen",
        "Nữ trưởng thành" to "josei",
        "Tình cảm nữ – nữ" to "shoujo-ai",
        "Tình cảm nam – nam" to "shounen-ai"
    )

    private val CONTENT_SLUGS = CONTENT_CHIPS.map { it.second }.toSet()
    private val AUDIENCE_SLUGS = AUDIENCE_CHIPS.map { it.second }.toSet()

    /**
     * Detail-page tags mix the menu slugs with a parallel Vietnamese tag
     * system (a title can carry `hanh-dong` instead of `action`) — map
     * the aliases onto the canonical chip slugs; anything without a chip
     * (định dạng/gốc tags, dead-end tags) is dropped.
     */
    private val ALIASES: Map<String, String> = mapOf(
        "hanh-dong" to "action",
        "hai-huoc" to "comedy",
        "lang-man" to "romance",
        "hoc-duong" to "school-life",
        "doi-thuong" to "slice-of-life",
        "vo-thuat" to "martial-arts",
        "vo-lam" to "martial-arts",
        "au-co" to "historical",
        "huyen-bi" to "mystery",
        "phieu-luu" to "adventure",
        "the-thao" to "sports",
        "trinh-tham" to "detective"
    )

    /** Raw detail slugs → (content slugs, audience slugs), both canonical. */
    fun normalize(rawSlugs: List<String>): Pair<Set<String>, Set<String>> {
        val content = mutableSetOf<String>()
        val audience = mutableSetOf<String>()
        for (raw in rawSlugs) {
            val slug = ALIASES[raw] ?: raw
            when (slug) {
                in CONTENT_SLUGS -> content.add(slug)
                in AUDIENCE_SLUGS -> audience.add(slug)
            }
        }
        return content to audience
    }

    /**
     * Build the index from the live shelf: every shelf page, then each
     * title's detail tags (small parallel batches — the hosts are slow
     * and fragile). A title whose detail fails keeps an entry with no
     * tags: it still belongs to the shelf, it just matches no chip.
     */
    suspend fun build(source: TruyenQqSource): List<ManhwaIndexEntry> = coroutineScope {
        val shelf = mutableListOf<TitleItem>()
        for (p in 1..11) {
            val items = try {
                source.manhwaShelf(p)
            } catch (e: CancellationException) {
                // 1.3.3: the caller's total budget (withTimeoutOrNull
                // in loadManhwaIndex) must abort the build — swallowing
                // cancellation here would degrade it into a partial,
                // tag-less index that then gets cached as if complete.
                throw e
            } catch (e: Exception) {
                break
            }
            if (items.isEmpty()) break
            shelf += items
        }
        val titles = shelf.distinctBy { it.slug }
        val entries = mutableListOf<ManhwaIndexEntry>()
        // 1.3.4: batch 6 → 3 parallel detail fetches — the build runs
        // while the user may be reading, and 6-wide batches against
        // the fragile TruyenQQ hosts starved the reader's own loads
        // (Lý Thấm's reader dossier).
        for (batch in titles.chunked(3)) {
            val built = batch.map { item ->
                async {
                    val detail = try {
                        source.detail(item.slug, Family.MANHWA)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    val (genres, audience) = normalize(
                        detail?.genreLinks?.map { it.second } ?: emptyList()
                    )
                    ManhwaIndexEntry(
                        item = item,
                        genreSlugs = genres,
                        audienceSlugs = audience,
                        completed = detail?.status?.contains("Hoàn thành", true) == true
                    )
                }
            }.awaitAll()
            entries += built
        }
        entries
    }

    fun filter(
        entries: List<ManhwaIndexEntry>,
        genre: String?,
        audience: String?,
        status: Int
    ): List<ManhwaIndexEntry> = entries.filter { e ->
        (genre == null || genre in e.genreSlugs) &&
            (audience == null || audience in e.audienceSlugs) &&
            (status == 0 || (status == 2) == e.completed)
    }

    // ------------------------------------------------------------------
    // Cache serialization (one JSON blob in DataStore)
    // ------------------------------------------------------------------

    fun toJson(entries: List<ManhwaIndexEntry>): String {
        val arr = JSONArray()
        for (e in entries) {
            val o = JSONObject()
            o.put("slug", e.item.slug)
            o.put("name", e.item.name)
            o.put("poster", e.item.posterUrl)
            o.put("latest", e.item.latestLabel)
            o.put("eps", e.item.episodeCount)
            o.put("upd", e.item.updatedLabel)
            o.put("genres", JSONArray(e.genreSlugs.toList()))
            o.put("audience", JSONArray(e.audienceSlugs.toList()))
            o.put("done", e.completed)
            arr.put(o)
        }
        return arr.toString()
    }

    fun fromJson(json: String): List<ManhwaIndexEntry> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val slug = o.optString("slug")
                if (slug.isBlank()) return@mapNotNull null
                fun strSet(key: String): Set<String> {
                    val a = o.optJSONArray(key) ?: return emptySet()
                    return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.toSet()
                }
                ManhwaIndexEntry(
                    item = TitleItem(
                        id = "${Family.MANHWA.name}:${TruyenQqSource.SOURCE_ID}:$slug",
                        family = Family.MANHWA,
                        sourceId = TruyenQqSource.SOURCE_ID,
                        slug = slug,
                        name = o.optString("name"),
                        posterUrl = o.optString("poster"),
                        thumbUrl = o.optString("poster"),
                        latestLabel = o.optString("latest"),
                        episodeCount = o.optInt("eps"),
                        updatedLabel = o.optString("upd")
                    ),
                    genreSlugs = strSet("genres"),
                    audienceSlugs = strSet("audience"),
                    completed = o.optBoolean("done")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
