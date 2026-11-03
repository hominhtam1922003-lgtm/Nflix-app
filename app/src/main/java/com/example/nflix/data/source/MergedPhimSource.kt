package com.example.nflix.data.source

import com.example.nflix.data.model.Family
import com.example.nflix.data.model.TitleDetail
import com.example.nflix.data.model.TitleItem
import com.example.nflix.data.model.matchesStatus
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Routing front for the Phim/Anime families. The name is a leftover
 * from the merged-catalog era (≤1.3.2, when a Phim title's detail
 * combined KKPhim + Cobe server groups).
 *
 * 1.3.5 (user order 2026-11-03 15:10): THE SOURCE IS PHIMCOBE
 * (phimcobe.com). The user dropped cobephim.fun after its /phim-moi
 * listing 404'd from his route in the 1.3.4 device verdict (and its
 * player kept landing on Cloudflare-blocked streamc embeds), and
 * KKPhim stays dropped. Every Phim/Anime path now runs on
 * [PhimCobeSource]: catalog lists, Home shelves, the filter sheet,
 * detail, episodes and playback routing. A title an older build
 * saved re-homes by slug (the catalogs share the Ophim pool lineage);
 * a title the new source does not carry fails with the source's own
 * error, shown verbatim.
 *
 * What the swap changes versus 1.3.4: the Quốc gia facet RETURNS to
 * the Phim sheet (PhimCobe records carry countries — Cobe's never
 * did); the "Chiếu rạp" kind stays dead (the new source publishes
 * no cinema listing either — its list slugs are a closed set and
 * phim-chieu-rap falls back to the full catalog, which would be a
 * fake shelf); the actor filmography page is GONE (the source has
 * no actor pages — measured 404; cast names remain as plain text).
 */
class MergedPhimSource(
    private val phimcobe: PhimCobeSource
) {

    companion object {
        /** Total budget for one detail through this front. */
        private const val DETAIL_BUDGET_MS = 20_000L

        /**
         * 1.3.7: API pages folded into one UI page for the Phim
         * lists — the source serves 10 records per page, which read
         * as a starved grid on device (user verdict 1.3.6).
         */
        private const val API_PAGES_PER_UI_PAGE = 3
    }

    /**
     * Route a title to its PhimCobe detail, whatever sourceId an
     * older build saved on it — the slug is the identity. Bounded:
     * the unbounded version of this call is what hung the 1.3.2
     * player init forever.
     */
    suspend fun detailFor(item: TitleItem): TitleDetail =
        withTimeoutOrNull(DETAIL_BUDGET_MS) {
            phimcobe.detail(item.slug, item.family)
        } ?: throw IllegalStateException(
            "Nguồn PhimCobe không phản hồi kịp — hãy thử lại"
        )

    // ------------------------------------------------------------------
    // PHIM LISTS (PhimCobe films API + listing pages). The *Merged
    // names are kept so every caller (Home, Phim tab) stays untouched.
    // ------------------------------------------------------------------

    /**
     * Newest-updated Phim feed — the catalog's latest listing.
     * 1.3.7: animation records are filtered OUT of every Phim list —
     * the source's own phim-bo/phim-moi listings are 30–50%
     * animation (measured live 2026-11-03), and animation belongs
     * to the Anime tab (user verdict 1.3.6: "hoạt hình tùm lum…
     * lọc ra").
     */
    suspend fun latestMerged(page: Int): List<TitleItem> =
        phimcobe.latestMulti(page, API_PAGES_PER_UI_PAGE).first
            .filter { !it.isAnime }

    /** Kind listing (phim-bo / phim-le) from the films API. */
    suspend fun byKindMerged(kind: String, page: Int, limit: Int = 24): List<TitleItem> =
        when (kind) {
            "phim-bo", "phim-le" -> phimcobe.kindMulti(kind, page, API_PAGES_PER_UI_PAGE)
                .first.filter { !it.isAnime }.take(limit)
            else -> latestMerged(page).take(limit)
        }

    /**
     * Filtered Phim browse (1.3.5): the category facet is served by
     * the source's /the-loai/<slug> listing, the country facet by
     * /quoc-gia/<slug> — when both are set, the category listing
     * serves and the country matches locally on the records' own
     * countrySlugs. Without either, the kind page serves. Year and
     * status facets match locally over the page (year = the record's
     * own year field, status from episode counts).
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
            category.isNotEmpty() ->
                phimcobe.categoryPage(category, page, kind, Family.PHIM).first
            country.isNotEmpty() ->
                phimcobe.countryPage(country, page, kind, Family.PHIM).first
            else ->
                phimcobe.kindMulti(kind.ifEmpty { "phim-bo" }, page, API_PAGES_PER_UI_PAGE).first
        }
        return base
            .filter { !it.isAnime }
            .filter { country.isEmpty() || category.isEmpty() || country in it.countrySlugs }
            .filter { year == 0 || it.year == year }
            .filter { it.matchesStatus(status) }
            .take(limit)
    }
}
