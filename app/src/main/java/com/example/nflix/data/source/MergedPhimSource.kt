package com.example.nflix.data.source

import android.util.Log
import com.example.nflix.data.model.Family
import com.example.nflix.data.model.TitleDetail
import com.example.nflix.data.model.TitleItem
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
    }

    /** Route by the title's own source: cobe-only titles stay pure cobe. */
    suspend fun detailFor(item: TitleItem): TitleDetail =
        if (item.sourceId == CobeSource.SOURCE_ID) {
            cobe.detail(item.slug, item.family)
        } else {
            detail(item.slug, item.family)
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
            directors = kk.directors.ifEmpty { cb.directors },
            servers = servers,
            trailerUrl = kk.trailerUrl.ifBlank { cb.trailerUrl },
            genreLinks = kk.genreLinks.ifEmpty { cb.genreLinks }
        )
    }
}
