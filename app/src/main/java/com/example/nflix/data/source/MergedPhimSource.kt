package com.example.nflix.data.source

import com.example.nflix.data.model.TitleDetail
import com.example.nflix.data.model.TitleItem
import com.example.nflix.data.model.matchesStatus
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Routing front for the Phim/Anime families. The name is a leftover
 * from the merged-catalog era (≤1.3.2, when a Phim title's detail
 * combined KKPhim + Cobe server groups).
 *
 * 1.3.4 (user order 2026-11-03 11:39): PHIM IS COBE-ONLY — KKPHIM IS
 * GONE ENTIRELY. The user ordered KK dropped after its streams kept
 * serving spliced-in gambling ads mid-episode while Cobe's do not.
 * Every Phim path now runs on Cobe: catalog lists, Home shelves, the
 * filter sheet, detail, episodes and playback routing. A Phim item
 * an older build saved with a KK identity re-homes onto Cobe by slug
 * (the two catalogs share slugs 1:1); a title Cobe does not carry
 * fails with Cobe's own error, shown verbatim.
 *
 * ANIME keeps its own law (unchanged): Cobe detail via this front;
 * the country gate governs the tab's pool, not a by-slug detail. The
 * KK fallback/append of 1.3.3 is gone with KK.
 *
 * Trade-offs the user accepted when choosing Cobe-only: the Phim
 * catalog shrinks to Cobe's pool; the "Chiếu rạp" kind dies (Cobe
 * has no cinema listing — measured 404); the Quốc gia facet dies
 * (Cobe records carry no country field); the "Sắp chiếu" shelf dies
 * (Cobe publishes no coming-soon listing).
 */
class MergedPhimSource(
    private val cobe: CobeSource
) {

    companion object {
        /** Total budget for one Cobe detail through this front. */
        private const val COBE_DETAIL_BUDGET_MS = 20_000L
    }

    /**
     * Route a title to its Cobe detail, whatever sourceId an older
     * build saved on it — the slug is the identity, and Cobe's pool
     * is the only pool now. Bounded: the unbounded version of this
     * call is what hung the 1.3.2 player init forever.
     */
    suspend fun detailFor(item: TitleItem): TitleDetail =
        withTimeoutOrNull(COBE_DETAIL_BUDGET_MS) {
            cobe.detail(item.slug, item.family)
        } ?: throw IllegalStateException(
            "Nguồn Cobe không phản hồi kịp — hãy thử lại"
        )

    // ------------------------------------------------------------------
    // PHIM LISTS (Cobe web listings — the API's list endpoints are
    // token-locked; the Next.js pages embed the same records, ordered
    // updatedAt desc). The *Merged names are kept so every caller
    // (Home, Phim tab) stays untouched.
    // ------------------------------------------------------------------

    /** Newest-updated Phim feed — Cobe's /phim-moi listing. */
    suspend fun latestMerged(page: Int): List<TitleItem> =
        cobe.latestPage(page).first

    /** Kind listing (phim-bo / phim-le) from Cobe's kind pages. */
    suspend fun byKindMerged(kind: String, page: Int, limit: Int = 24): List<TitleItem> =
        when (kind) {
            "phim-bo", "phim-le" -> cobe.kindPage(kind, page).first.take(limit)
            else -> latestMerged(page).take(limit)
        }

    /**
     * Filtered Phim browse, Cobe-only (1.3.4): the category facet is
     * served by Cobe's own /the-loai/<slug> listing with the kind
     * facet applied on the records' own type field (see
     * [CobeSource.categoryPage]); without a category, the kind page
     * serves. Year and status facets match locally over the page —
     * the same local-filter law the Anime tab already runs on (year
     * = the record's publish_year, status from episode counts).
     */
    suspend fun filteredMerged(
        kind: String,
        category: String = "",
        year: Int = 0,
        status: Int = 0,
        page: Int = 1,
        limit: Int = 24
    ): List<TitleItem> {
        val base = if (category.isNotEmpty()) {
            cobe.categoryPage(category, page, kind).first
        } else {
            cobe.kindPage(kind.ifEmpty { "phim-bo" }, page).first
        }
        return base
            .filter { year == 0 || it.year == year }
            .filter { it.matchesStatus(status) }
            .take(limit)
    }
}
