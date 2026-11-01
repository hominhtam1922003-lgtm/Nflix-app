package com.example.nflix.data

import android.net.Uri
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * V2 (A6): prefetch the HEAD of the next episode while the current one
 * plays. Only direct HLS is prefetchable — embed (WebView) episodes are
 * never touched and nothing in the UI promises otherwise.
 *
 * Mechanism: the player itself streams through a shared SimpleCache
 * (write-through), so warming that same cache with the next episode's
 * playlist + first segments makes the episode switch start from local
 * bytes instead of a cold network fetch. Quality law: segments are
 * cached exactly as the source serves them (the highest-bandwidth
 * variant of a master playlist) — prefetch never picks a lower
 * rendition to save space.
 */
object PlayerPrefetch {

    /** How many leading segments of the next episode to warm. */
    private const val MAX_SEGMENTS = 4

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun fetchText(url: String): String {
        val req = Request.Builder().url(url).get()
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) Nflix/1.0")
            .header("Referer", "https://phimapi.com/")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("prefetch HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    private fun cacheUrl(cache: Cache, url: String) {
        // CacheWriter stores the whole resource under the default cache
        // key (the URI string) — the same key CacheDataSource looks up
        // during playback, so warmed bytes are real playback hits.
        CacheWriter(cache, DataSpec(Uri.parse(url)), null, null).cache()
    }

    /**
     * Warm [playlistUrl] and the first [MAX_SEGMENTS] media segments of
     * its best variant. Best-effort: any failure is swallowed — a failed
     * prefetch must never disturb the episode actually playing.
     */
    suspend fun prefetchHls(cache: Cache, playlistUrl: String) {
        withContext(Dispatchers.IO) {
            try {
                val head = fetchText(playlistUrl)
                cacheUrl(cache, playlistUrl)
                val variantUrl = bestVariantUrl(head, playlistUrl)
                val mediaPlaylistUrl = variantUrl ?: playlistUrl
                val mediaPlaylist = if (variantUrl != null) {
                    val text = fetchText(variantUrl)
                    cacheUrl(cache, variantUrl)
                    text
                } else {
                    head
                }
                val segments = mediaPlaylist.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .take(MAX_SEGMENTS)
                    .toList()
                for (seg in segments) {
                    runCatching { cacheUrl(cache, resolve(mediaPlaylistUrl, seg)) }
                }
            } catch (e: Exception) {
                // Prefetch is invisible when it works and silent when it
                // doesn't — playback of the next episode simply falls
                // back to the network, exactly like today.
            }
        }
    }

    /** Highest-BANDWIDTH variant of a master playlist (null when it is a media playlist). */
    private fun bestVariantUrl(master: String, base: String): String? {
        if (!master.contains("#EXT-X-STREAM-INF")) return null
        var bestBandwidth = -1L
        var best: String? = null
        val lines = master.lines()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val bw = Regex("""BANDWIDTH=(\d+)""").find(line)
                ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val uri = lines.getOrNull(i + 1)?.trim().orEmpty()
            if (uri.isNotEmpty() && !uri.startsWith("#") && bw > bestBandwidth) {
                bestBandwidth = bw
                best = resolve(base, uri)
            }
        }
        return best
    }

    private fun resolve(base: String, ref: String): String = try {
        java.net.URI(base).resolve(ref).toString()
    } catch (e: Exception) {
        ref
    }
}
