package com.example.nflix.data.source

import android.util.Log
import com.example.nflix.data.model.Family
import com.example.nflix.data.model.TitleDetail
import com.example.nflix.data.model.TitleItem
import com.example.nflix.data.model.matchesStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Merged front for the Phim/Anime families (đợt 4): one title, one card —
 * KKPhim stays the identity and the primary server block, CobePhim rides
 * along as extra server groups (labeled " · Cobe") and can push the
 * released-episode count higher, which is what the "Có tập mới" worker
 * and the pills consume. Slugs match 1:1 between the two catalogs.
 *
 * Failure rule: cobe must never break the KKPhim path — a cobe error or
 * a title cobe does not carry simply yields the plain KKPhim detail,
 * and a KKPhim error falls back to the cobe detail (cobe-only titles
 * reached through a KKPhim-shaped id still open).
 *
 * 1.1.1 (N1/N2): "never break" no longer means "fail silently". When the
 * cobe SOURCE is unreachable (as opposed to simply not carrying the
 * title), the returned detail is flagged [TitleDetail.cobeFailed] so the
 * UI can say "Nguồn Cobe: không kết nối được" instead of the source
 * vanishing. The cobe branch also runs under a hard time budget so a
 * dead cobe can never drag the KKPhim detail down with it.
 */
class MergedPhimSource(
    private val kkPhim: KkPhimSource,
    private val cobe: CobeSource
) {

    companion object {
        private const val TAG = "MergedPhim"

        /** Hard budget for the whole cobe branch of one merged detail. */
        private const val COBE_BUDGET_MS = 6_000L

        /** Cobe pages scanned per filtered-browse page (local filtering horizon). */
        private const val COBE_LOCAL_SCAN_PAGES = 3
    }

    /** Route by the title's own source: cobe-only titles stay pure cobe. */
    suspend fun detailFor(item: TitleItem): TitleDetail =
        if (item.sourceId == CobeSource.SOURCE_ID) {
            // 1.2.0 (Khối B): an Anime title lives on Cobe, but when
            // KKPhim carries the same slug as hoạt hình its server groups
            // join too — the user ordered ALL groups collected (Vietsub /
            // Thuyết minh / Lồng tiếng / Song ngữ), none dropped.
            if (item.family == Family.ANIME) animeDetail(item)
            else cobe.detail(item.slug, item.family)
        } else {
            detail(item.slug, item.family)
        }

    /**
     * Cobe-first Anime detail: identity and groups from Cobe; KKPhim
     * groups appended only when KKPhim really carries this slug as
     * animation (a same-slug live-action title must never leak in).
     */
    private suspend fun animeDetail(item: TitleItem): TitleDetail {
        val cb = cobe.detail(item.slug, Family.ANIME)
        val kk = runCatching { kkPhim.detail(item.slug) }.getOrNull()
            ?: return cb
        if (kk.item.family != Family.ANIME || kk.servers.isEmpty()) return cb
        val servers = cb.servers + kk.servers
        val released = servers.maxOfOrNull { g ->
            g.episodes.maxOfOrNull { it.number } ?: 0f
        }?.toInt() ?: 0
        val total = maxOf(cb.totalCount, kk.totalCount, released)
        return TitleDetail(
            item = cb.item.copy(episodeCount = maxOf(cb.item.episodeCount, total)),
            description = cb.description.ifBlank { kk.description },
            status = cb.status.ifBlank { kk.status },
            totalCount = total,
            durationLabel = cb.durationLabel.ifBlank { kk.durationLabel },
            actors = cb.actors.ifEmpty { kk.actors },
            actorRefs = cb.actorRefs,
            directors = cb.directors.ifEmpty { kk.directors },
            servers = servers,
            trailerUrl = cb.trailerUrl.ifBlank { kk.trailerUrl },
            genreLinks = cb.genreLinks.ifEmpty { kk.genreLinks }
        )
    }

    suspend fun detail(slug: String, familyHint: Family = Family.PHIM): TitleDetail = coroutineScope {
        val kkDeferred = async { runCatching { kkPhim.detail(slug) }.getOrNull() }
        val cobeDeferred = async { fetchCobe(slug, familyHint) }
        val kk = kkDeferred.await()
        val cobeOutcome = cobeDeferred.await()
        val cb = cobeOutcome.detail
        when {
            kk == null && cb == null ->
                throw IllegalStateException("Không tải được chi tiết từ cả hai nguồn")
            kk == null -> cb!!
            cb == null -> kk.copy(cobeFailed = cobeOutcome.failed)
            else -> merge(kk, cb)
        }
    }

    // ------------------------------------------------------------------
    // Merged LISTS (1.2.0, Khối A): the Phim catalog is the UNION of both
    // sources — Cobe-only titles are first-class citizens of the tab, not
    // just extra servers on KKPhim titles. Dedupe by slug; the KKPhim
    // copy keeps the identity when both carry a title (its detail is the
    // merged one anyway). Cobe lists come from its web pages (the API
    // list endpoints are token-locked).
    // ------------------------------------------------------------------

    private fun union(pages: List<List<TitleItem>>): List<TitleItem> {
        val bySlug = LinkedHashMap<String, TitleItem>()
        for (page in pages) {
            for (item in page) {
                val cur = bySlug[item.slug]
                if (cur == null ||
                    (cur.sourceId != KkPhimSource.SOURCE_ID && item.sourceId == KkPhimSource.SOURCE_ID)
                ) {
                    bySlug[item.slug] = item
                }
            }
        }
        return bySlug.values.sortedByDescending { it.updatedEpochSec }
    }

    /** Newest-updated Phim feed: KKPhim latest + Cobe series + Cobe movies of the same page. */
    suspend fun latestMerged(page: Int): List<TitleItem> = coroutineScope {
        val kkD = async { runCatching { kkPhim.latest(page) }.getOrDefault(emptyList()) }
        val boD = async { runCatching { cobe.kindPage("phim-bo", page).first }.getOrDefault(emptyList()) }
        val leD = async { runCatching { cobe.kindPage("phim-le", page).first }.getOrDefault(emptyList()) }
        union(listOf(kkD.await(), boD.await(), leD.await()))
    }

    /** Kind listing (phim-bo / phim-le) merged across both sources. */
    suspend fun byKindMerged(kind: String, page: Int, limit: Int = 24): List<TitleItem> = coroutineScope {
        if (kind == "chieu-rap") {
            // Chiếu rạp is a KKPhim flag concept; Cobe has no such list.
            return@coroutineScope kkPhim.cinema(page, limit)
        }
        val kkD = async { runCatching { kkPhim.listByKind(kind, page, limit) }.getOrDefault(emptyList()) }
        val cbD = async { runCatching { cobe.kindPage(kind, page).first }.getOrDefault(emptyList()) }
        union(listOf(kkD.await(), cbD.await())).take(limit + 16)
    }

    /**
     * Filtered Phim browse: KKPhim filters server-side; the Cobe side is
     * filtered LOCALLY over its kind pages (its API list is token-locked
     * and its web pages carry full records). Genre matches on the shared
     * Vietnamese category NAME (the slug systems differ: Cobe "hai" vs
     * KKPhim "hai-huoc"); Cobe has no country field, so a country filter
     * narrows only the KKPhim side. Status matches on derived counts.
     */
    suspend fun filteredMerged(
        kind: String,
        category: String = "",
        country: String = "",
        year: Int = 0,
        status: Int = 0,
        page: Int = 1,
        limit: Int = 24
    ): List<TitleItem> = coroutineScope {
        val kkD = async {
            runCatching {
                kkPhim.listFiltered(kind, category, country, year, page, limit)
            }.getOrDefault(emptyList()).filter { it.matchesStatus(status) }
        }
        val categoryName = KkPhimSource.CATEGORIES.firstOrNull { it.second == category }?.first
        val cbD = async {
            if (page > COBE_LOCAL_SCAN_PAGES) {
                emptyList()
            } else {
                runCatching { cobe.kindPage(kind, page).first }.getOrDefault(emptyList())
                    .filter { item ->
                        (category.isEmpty() ||
                            item.genreSlugs.contains(category) ||
                            (categoryName != null && item.genres.any {
                                it.equals(categoryName, ignoreCase = true)
                            })) &&
                            (year == 0 || item.year == year) &&
                            item.matchesStatus(status)
                    }
            }
        }
        union(listOf(kkD.await(), cbD.await()))
    }

    /** Outcome of the cobe branch: the detail (if any) + whether the source itself failed. */
    private class CobeOutcome(val detail: TitleDetail?, val failed: Boolean)

    /**
     * Cobe under a hard budget. A timeout counts as a source failure; an
     * HTTP 404 / empty movie payload means cobe simply does not carry
     * this title — that is NOT flagged, the KKPhim detail stays clean.
     */
    private suspend fun fetchCobe(slug: String, familyHint: Family): CobeOutcome {
        val detail = try {
            withTimeoutOrNull(COBE_BUDGET_MS) { cobe.detail(slug, familyHint) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val notCarried = isNotCarried(e)
            if (!notCarried) {
                Log.w(TAG, "Cobe detail failed for slug=$slug: ${e.message}")
            }
            return CobeOutcome(null, failed = !notCarried)
        }
        return if (detail != null) {
            CobeOutcome(detail, failed = false)
        } else {
            Log.w(TAG, "Cobe detail timed out for slug=$slug")
            CobeOutcome(null, failed = true)
        }
    }

    /** "Cobe does not have this title" signals, per CobeSource's error shapes. */
    private fun isNotCarried(e: Throwable): Boolean {
        val msg = e.message ?: return false
        return msg.contains("Cobe HTTP 404") || msg.contains("Cobe: no movie")
    }

    private fun merge(kk: TitleDetail, cb: TitleDetail): TitleDetail {
        val servers = kk.servers + cb.servers
        // Released total on the MERGED server pool, counted the same way
        // each adapter counts its own: highest episode number present.
        val released = servers.maxOfOrNull { g ->
            g.episodes.maxOfOrNull { it.number } ?: 0f
        }?.toInt() ?: 0
        val total = maxOf(kk.totalCount, cb.totalCount, released)
        val badge = when {
            servers.any { it.langKey == "tm" } -> "TM"
            servers.any { it.langKey == "vs" } -> "VS"
            else -> kk.item.versionBadge
        }
        val item = kk.item.copy(
            episodeCount = maxOf(kk.item.episodeCount, total),
            versionBadge = badge,
            // When cobe is ahead, the card must say so. Only numbered
            // series labels are rewritten — a movie keeps its "Full".
            latestLabel = if (total > kk.totalCount && total > 0 &&
                kk.item.latestLabel.startsWith("Tập", ignoreCase = true)
            ) {
                "Tập $total"
            } else {
                kk.item.latestLabel
            },
            updatedEpochSec = maxOf(kk.item.updatedEpochSec, cb.item.updatedEpochSec)
        )
        return TitleDetail(
            item = item,
            description = kk.description.ifBlank { cb.description },
            status = kk.status.ifBlank { cb.status },
            totalCount = total,
            durationLabel = kk.durationLabel.ifBlank { cb.durationLabel },
            actors = kk.actors.ifEmpty { cb.actors },
            // V2 (A5): the Cobe record is the one carrying actor page
            // slugs/thumbs — keep its refs whichever side owns the names.
            actorRefs = cb.actorRefs,
            directors = kk.directors.ifEmpty { cb.directors },
            servers = servers,
            trailerUrl = kk.trailerUrl.ifBlank { cb.trailerUrl },
            genreLinks = kk.genreLinks.ifEmpty { cb.genreLinks }
        )
    }
}
