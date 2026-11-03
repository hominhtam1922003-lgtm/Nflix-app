package com.example.nflix.data

import android.net.Uri
import androidx.media3.exoplayer.hls.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.HlsMasterPlaylist
import androidx.media3.exoplayer.hls.HlsPlaylist
import androidx.media3.exoplayer.hls.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * 1.3.3b: mid-roll ad cutter for DIRECT HLS playback (KKPhim +
 * streamvsmov — every direct stream the player loads).
 *
 * User report 2026-11-03: gambling ads spliced into KK films roughly
 * every 15 minutes. Those ads ride the media playlist as their OWN
 * segment files, typically wrapped between #EXT-X-DISCONTINUITY tags
 * and served from a different host or a different path prefix than
 * the movie segments. This filter rewrites the media playlist BEFORE
 * Media3 parses it: ad blocks are removed whole, the parser then
 * builds a timeline that never contains them.
 *
 * Why a playlist-parser hook and not an OkHttp interceptor: the
 * playback path does not use OkHttp at all — ExoPlayer streams
 * through DefaultHttpDataSource (HttpURLConnection) behind the shared
 * CacheDataSource. [HlsMediaSource.Factory.setPlaylistParserFactory]
 * is Media3's purpose-built seam: it sees every playlist (master and
 * media, cache hit or network) exactly once per parse, and the
 * delegate still does the real parsing. WebView embeds, the source
 * APIs and the comic readers never pass through here.
 *
 * FAIL-OPEN IS ABSOLUTE: any doubt, parse error or exception returns
 * the ORIGINAL playlist untouched. A stream that plays today can
 * never be broken by this filter — worst case the ads stay.
 */
data class AdCutReport(
    /** Segments actually removed from the media playlist. */
    val cutSegments: Int = 0,
    /** Total EXTINF duration of the removed segments, in seconds. */
    val cutSeconds: Double = 0.0
)

object HlsAdFilter {

    private class Block {
        val lines = mutableListOf<String>()
        var segments: Int = 0
        var duration: Double = 0.0

        /** (host, directory prefix) carried by most of this block's play time. */
        var signature: Pair<String, String>? = null

        /** True when at least one segment URI could not be resolved —
         * an unreadable block is never cut and never counts as movie. */
        var hasUnknown: Boolean = false
    }

    private sealed interface Element
    private object Disc : Element
    private class BlockElement(val block: Block) : Element

    /**
     * Return the playlist with ad blocks removed, plus what was
     * actually cut. Returns the input unchanged (and a zero report)
     * whenever nothing qualifies — or when ANYTHING is off.
     */
    fun filterPlaylist(text: String, baseUrl: String): Pair<String, AdCutReport> {
        return try {
            filterInternal(text, baseUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            text to AdCutReport()
        }
    }

    private fun filterInternal(text: String, baseUrl: String): Pair<String, AdCutReport> {
        val zero = AdCutReport()
        if (!text.contains("#EXTINF")) return text to zero

        val lines = text.split("\n").map { it.trimEnd('\r') }

        // Header = everything before the first segment-bearing line
        // (#EXTM3U, VERSION, TARGETDURATION, MEDIA-SEQUENCE, KEY/MAP…).
        // Header lines are preserved verbatim and are never cuttable.
        // MEDIA-SEQUENCE needs no adjustment afterwards: it numbers
        // the FIRST segment of the playlist, and the first block is
        // never cut (see the sandwich rule below).
        val firstBody = lines.indexOfFirst { raw ->
            val t = raw.trim()
            t.startsWith("#EXTINF") || t == "#EXT-X-DISCONTINUITY" ||
                (t.isNotEmpty() && !t.startsWith("#"))
        }
        if (firstBody < 0) return text to zero
        val header = lines.subList(0, firstBody)
        val body = lines.subList(firstBody, lines.size)

        // Tokenize the body into blocks separated by discontinuity
        // markers. Every raw line stays attached to its block, so
        // cutting a block removes exactly its own tags + segments.
        val elements = mutableListOf<Element>()
        var current = Block()
        elements += BlockElement(current)
        for (raw in body) {
            if (raw.trim() == "#EXT-X-DISCONTINUITY") {
                elements += Disc
                current = Block()
                elements += BlockElement(current)
            } else {
                current.lines += raw
            }
        }
        val blocks = elements.filterIsInstance<BlockElement>().map { it.block }

        // Measure every block: segment count, play time, URI signature.
        var totalDuration = 0.0
        val globalSignatures = mutableMapOf<Pair<String, String>, Double>()
        for (block in blocks) {
            var pendingDuration: Double? = null
            val own = mutableMapOf<Pair<String, String>, Double>()
            for (raw in block.lines) {
                val t = raw.trim()
                when {
                    t.startsWith("#EXTINF:") -> pendingDuration =
                        t.removePrefix("#EXTINF:").substringBefore(',')
                            .toDoubleOrNull()
                    t.isEmpty() || t.startsWith("#") -> Unit
                    else -> {
                        block.segments++
                        val d = pendingDuration ?: 0.0
                        block.duration += d
                        pendingDuration = null
                        val sig = signatureOf(baseUrl, t)
                        if (sig == null) {
                            block.hasUnknown = true
                        } else {
                            own[sig] = (own[sig] ?: 0.0) + d
                            globalSignatures[sig] =
                                (globalSignatures[sig] ?: 0.0) + d
                        }
                    }
                }
            }
            if (block.segments > 0) {
                totalDuration += block.duration
                block.signature = own.maxByOrNull { it.value }?.key
            }
        }
        if (totalDuration <= 0.0) return text to zero

        // The movie's signature must carry a STRICT MAJORITY of the
        // whole playlist's play time. No majority = ambiguous layout
        // (e.g. a movie legitimately split across two hosts) = cut
        // nothing.
        val dominant = globalSignatures.maxByOrNull { it.value } ?: return text to zero
        if (dominant.value * 2 <= totalDuration) return text to zero
        val movieSignature = dominant.key

        // Cut rule — a block is removed whole only when ALL hold:
        //  1. it is SANDWICHED between two discontinuity markers
        //     (never the first or last block — a block at the edge has
        //     no marker on its outer side by construction);
        //  2. it carries at least one segment, all resolvable;
        //  3. its URI signature differs from the movie signature in
        //     host OR directory prefix.
        // Blocks without discontinuity wrappers are never touched,
        // whatever they contain: legitimate multi-host layouts do not
        // wrap each run in discontinuity pairs, splices do.
        val cut = mutableSetOf<Block>()
        for (i in elements.indices) {
            val el = elements[i] as? BlockElement ?: continue
            val sandwiched = elements.getOrNull(i - 1) is Disc &&
                elements.getOrNull(i + 1) is Disc
            if (!sandwiched) continue
            val block = el.block
            if (block.segments == 0 || block.hasUnknown) continue
            val sig = block.signature ?: continue
            if (sig == movieSignature) continue
            cut += block
        }
        if (cut.isEmpty()) return text to zero

        // Paranoia cap: never remove half or more of a playlist, even
        // if the rules above somehow selected that much.
        val cutDuration = cut.sumOf { it.duration }
        if (cutDuration * 2 >= totalDuration) return text to zero

        // Rebuild: header verbatim, kept blocks in order, discontinuity
        // markers collapsed (a marker is emitted only between two kept
        // blocks that had one — including where a cut block used to
        // sit, because the timeline still jumps there).
        val out = mutableListOf<String>()
        out += header
        var emittedBlock = false
        var lastWasDisc = false
        for (el in elements) {
            when (el) {
                is Disc -> if (emittedBlock && !lastWasDisc) {
                    out += "#EXT-X-DISCONTINUITY"
                    lastWasDisc = true
                }
                is BlockElement -> if (el.block !in cut) {
                    out += el.block.lines
                    emittedBlock = true
                    lastWasDisc = false
                }
            }
        }
        if (out.lastOrNull()?.trim() == "#EXT-X-DISCONTINUITY") {
            out.removeAt(out.lastIndex)
        }

        val report = AdCutReport(
            cutSegments = cut.sumOf { it.segments },
            cutSeconds = cutDuration
        )
        return out.joinToString("\n") to report
    }

    /**
     * (host, directory prefix) of a segment URI resolved against the
     * playlist URL — e.g. "https://a.kvp726.com/20261031/abc/seg1.ts"
     * resolves against its playlist to ("a.kvp726.com", "/20261031/abc/").
     * Null when the URI cannot be resolved to a hosted path.
     */
    private fun signatureOf(baseUrl: String, ref: String): Pair<String, String>? {
        return try {
            val resolved = java.net.URI(baseUrl).resolve(ref)
            val host = resolved.host?.lowercase() ?: return null
            val path = resolved.path ?: return null
            val dir = path.substringBeforeLast('/', "")
            host to if (dir.isEmpty()) "/" else "$dir/"
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Playlist parser factory for the player: runs [HlsAdFilter] over
 * media playlists and reports REAL cuts through [onReport] (playlist
 * URL + what was removed), then lets the stock Media3 parser do the
 * actual parsing on the filtered text. Master playlists (variant
 * lists) pass through byte-identical, as does anything that is not a
 * media playlist — and any failure in the filtering step falls back
 * to the original bytes (fail-open).
 */
class AdCutPlaylistParserFactory(
    private val onReport: (playlistUrl: String, report: AdCutReport) -> Unit
) : HlsPlaylistParserFactory {

    private val delegate = DefaultHlsPlaylistParserFactory()

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
        FilteringParser(delegate.createPlaylistParser(), onReport)

    override fun createPlaylistParser(
        masterPlaylist: HlsMasterPlaylist
    ): ParsingLoadable.Parser<HlsPlaylist> =
        FilteringParser(delegate.createPlaylistParser(masterPlaylist), onReport)

    private class FilteringParser(
        private val inner: ParsingLoadable.Parser<HlsPlaylist>,
        private val onReport: (String, AdCutReport) -> Unit
    ) : ParsingLoadable.Parser<HlsPlaylist> {

        override fun parse(uri: Uri, inputStream: InputStream): HlsPlaylist {
            val original = inputStream.readBytes()
            val out = try {
                val text = String(original, Charsets.UTF_8)
                when {
                    // Master playlist: never filtered.
                    text.contains("#EXT-X-STREAM-INF") -> original
                    text.contains("#EXTINF") -> {
                        val (filtered, report) =
                            HlsAdFilter.filterPlaylist(text, uri.toString())
                        try {
                            onReport(uri.toString(), report)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Reporting must never disturb playback.
                        }
                        if (report.cutSegments > 0) {
                            filtered.toByteArray(Charsets.UTF_8)
                        } else {
                            original
                        }
                    }
                    else -> original
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                original // fail-open: the unfiltered playlist, as today
            }
            return inner.parse(uri, ByteArrayInputStream(out))
        }
    }
}
