package com.example.nflix.data.source

import com.example.nflix.data.model.Family
import com.example.nflix.data.model.TitleDetail
import com.example.nflix.data.model.TitleItem
import com.example.nflix.data.model.matchesStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Routing front for the Phim/Anime families. The name is a leftover
 * from the merged-catalog era (≤1.3.2, when a Phim title's detail
 * combined KKPhim + Cobe server groups).
 *
 * 1.3.3 (user order 2026-11-03 09:42): PHIM IS KKPHIM-ONLY. Cobe is
 * removed from every Phim path — catalog lists, search surfaces, the
 * filter sheet, detail, episodes and playback routing — after the
 * 1.3.2 device verdict showed Cobe errors ("Cobe HTTP 404") killing
 * detail pages for titles KKPhim carries fine. A Phim item an older
 * build saved with a Cobe identity re-homes onto KKPhim here: the
 * slugs match 1:1, and a title KKPhim does not carry fails with
 * KKPhim's own error instead of a Cobe one.
 *
 * ANIME keeps its own law (unchanged): Cobe-first detail via
 * [animeDetail], with KKPhim's animation groups appended on a slug
 * match and as the fallback when Cobe cannot serve the title.
 * Cobe also still powers the actor pages — neither is a Phim path.
 */
class MergedPhimSource(
    private val kkPhim: KkPhimSource,
    private val cobe: CobeSource
) {

    companion object {
        /** Hard budget for the Cobe call inside an Anime detail. */
        private const val COBE_BUDGET_MS = 6_000L
    }

    /**
     * Route a title to its detail. ANIME items carrying a Cobe
     * identity go through [animeDetail] (the Anime law is unchanged);
     * EVERYTHING else — every Phim item included, whatever sourceId an
     * older build saved on it — is served by KKPhim alone (1.3.3).
     */
    suspend fun detailFor(item: TitleItem): TitleDetail =
        if (item.sourceId == CobeSource.SOURCE_ID && item.family == Family.ANIME) {
            animeDetail(item)
        } else {
            kkPhim.detail(item.slug)
        }

    /**
     * The Cobe call of an Anime detail under a hard budget (kept from
     * 1.3.2). It used to run with no ceiling at all: when Cobe died
     * (502 / the homepage slow-dripping through host rediscovery),
     * Player init hung forever on "Đang tải…" with an empty episode
     * sheet (measured 2026-11-03, dossier phim-0-tap branch D).
     */
    private suspend fun cobeDetailBounded(slug: String, family: Family): TitleDetail =
        withTimeoutOrNull(COBE_BUDGET_MS) { cobe.detail(slug, family) }
            ?: throw IllegalStateException(
                "Nguồn Cobe không phản hồi kịp — hãy thử lại"
            )

    /**
     * Cobe-first Anime detail: identity and groups from Cobe; KKPhim
     * groups appended only when KKPhim really carries this slug as
     * animation (a same-slug live-action title must never leak in).
     */
    private suspend fun animeDetail(item: TitleItem): TitleDetail {
        // 1.3.2: bounded like every other cobe entry point (see
        // cobeDetailBounded) — Anime details start from Cobe too.
        // 1.3.3: a Cobe failure no longer kills the page on the spot —
        // when KKPhim carries the same slug as animation with real
        // server groups, its detail serves instead; only when KKPhim
        // cannot serve it either does the Cobe error propagate.
        val cb = try {
            cobeDetailBounded(item.slug, Family.ANIME)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val kkFallback = try {
                kkPhim.detail(item.slug)
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Exception) {
                null
            }
            if (kkFallback != null && kkFallback.item.family == Family.ANIME &&
                kkFallback.servers.isNotEmpty()
            ) {
                return kkFallback
            }
            throw e
        }
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

    // ------------------------------------------------------------------
    // PHIM LISTS: KKPhim only since 1.3.3 (the 1.2.0 union with Cobe's
    // web lists is gone, per the same user order). The *Merged names
    // are kept so every caller (Home, Phim tab) stays untouched.
    // ------------------------------------------------------------------

    /** Newest-updated Phim feed — KKPhim's latest list, nothing else. */
    suspend fun latestMerged(page: Int): List<TitleItem> = kkPhim.latest(page)

    /** Kind listing (phim-bo / phim-le / chieu-rap) from KKPhim only. */
    suspend fun byKindMerged(kind: String, page: Int, limit: Int = 24): List<TitleItem> =
        if (kind == "chieu-rap") {
            kkPhim.cinema(page, limit)
        } else {
            kkPhim.listByKind(kind, page, limit)
        }

    /**
     * Filtered Phim browse, KKPhim only (1.3.3): with a kind set, the
     * API filters server-side (category/country/year are real params);
     * with only a category set, the dedicated /the-loai/{slug} listing
     * serves it; with neither, the newest-updated pages accumulate
     * (country/year facets alone have no KK listing — the same
     * looseness the merged era had on its KKPhim side). The status
     * facet matches locally on derived counts, as before.
     */
    suspend fun filteredMerged(
        kind: String,
        category: String = "",
        country: String = "",
        year: Int = 0,
        status: Int = 0,
        page: Int = 1,
        limit: Int = 24
    ): List<TitleItem> {
        val base = when {
            kind.isNotEmpty() ->
                kkPhim.listFiltered(kind, category, country, year, page, limit)
            category.isNotEmpty() ->
                kkPhim.byCategory(category, page, limit)
            else ->
                (1..page).flatMap { p -> kkPhim.latest(p) }.distinctBy { it.slug }
        }
        return base.filter { it.matchesStatus(status) }
    }
}
