package com.example.nflix.data.source

import com.example.nflix.data.model.Family
import com.example.nflix.data.model.TitleDetail
import com.example.nflix.data.model.TitleItem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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
 */
class MergedPhimSource(
    private val kkPhim: KkPhimSource,
    private val cobe: CobeSource
) {

    /** Route by the title's own source: cobe-only titles stay pure cobe. */
    suspend fun detailFor(item: TitleItem): TitleDetail =
        if (item.sourceId == CobeSource.SOURCE_ID) {
            cobe.detail(item.slug, item.family)
        } else {
            detail(item.slug, item.family)
        }

    suspend fun detail(slug: String, familyHint: Family = Family.PHIM): TitleDetail = coroutineScope {
        val kkDeferred = async { runCatching { kkPhim.detail(slug) }.getOrNull() }
        val cobeDeferred = async { runCatching { cobe.detail(slug, familyHint) }.getOrNull() }
        val kk = kkDeferred.await()
        val cb = cobeDeferred.await()
        when {
            kk == null && cb == null ->
                throw IllegalStateException("Không tải được chi tiết từ cả hai nguồn")
            kk == null -> cb!!
            cb == null -> kk
            else -> merge(kk, cb)
        }
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
            directors = kk.directors.ifEmpty { cb.directors },
            servers = servers,
            trailerUrl = kk.trailerUrl.ifBlank { cb.trailerUrl },
            genreLinks = kk.genreLinks.ifEmpty { cb.genreLinks }
        )
    }
}
