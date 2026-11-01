package com.example.nflix.data

import com.example.nflix.data.model.EpisodeItem
import com.example.nflix.data.model.ServerGroup
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * One server's last REAL measurement (đợt 1, mục 0.1 + 0.2). UNKNOWN means
 * "never measured" — the UI must say "Chưa kiểm tra", never fake a green dot.
 *
 * 1.1.1 (N3/N6) — the audit found the old probe lying twice:
 * - It timed EMBED pages and called a fast-loading page "đang tốt", so
 *   auto-pick could select a streamc embed whose page loads in 200 ms
 *   while the stream inside is dead. Embeds are now state EMBED: never
 *   measured, never "alive", never auto-picked.
 * - It failed every m3u8 with one label after a 2.5 s ranged GET. The
 *   probe now fetches exactly like the player (plain GET, ~8 s timeouts)
 *   and separates "the host answered with an HTTP error" (ERROR) from
 *   "no answer in time" (UNRESPONSIVE) — only the first is a verdict.
 */
data class ServerStatus(
    val state: State = State.UNKNOWN,
    val ms: Long? = null
) {
    enum class State { UNKNOWN, OK, SLOW, ERROR, UNRESPONSIVE, EMBED }

    val isAlive: Boolean get() = state == State.OK || state == State.SLOW

    /** Short truthful label for the server bar and the server sheet. */
    fun describe(): String = when (state) {
        State.UNKNOWN -> "Chưa kiểm tra"
        State.OK -> "đang tốt · nhanh" + (ms?.let { " · $it ms" } ?: "")
        State.SLOW -> "đang chậm" + (ms?.let { " · $it ms" } ?: "")
        State.ERROR -> "đang lỗi"
        State.UNRESPONSIVE -> "chậm · chưa đo được"
        State.EMBED -> "embed · không đo được luồng"
    }
}

/**
 * What a probe would actually hit for one episode. Callers resolve the
 * episode first (a cobe locator becomes HLS or Embed, a Donghua locator
 * becomes its embed iframe) and hand the probe a typed target, so the
 * probe never has to guess whether a URL is a stream or a web page.
 */
sealed class ProbeTarget {
    /** Direct HLS/playlist URL — measurable exactly like the player fetches it. */
    data class Stream(val url: String) : ProbeTarget()

    /** Web embed page — a fast page load says nothing about the stream inside. */
    data class Embed(val url: String) : ProbeTarget()

    /** Nothing resolvable for this episode. */
    data object None : ProbeTarget()
}

/**
 * Lightweight parallel server measurement behind the "Tự chọn server sống
 * nhanh nhất" setting (the toggle used to be dead code — nothing measured
 * anything). Only [ProbeTarget.Stream] targets are fetched, with a plain
 * GET and player-grade timeouts; per-server budgets keep the sweep
 * bounded and it never blocks the UI (callers run it in a coroutine and
 * publish results into state when done).
 */
object ServerProbe {

    // Player-grade timeouts (Media3's DefaultHttpDataSource sits at ~8 s):
    // the old 2.5 s budget convicted slow-but-alive CDNs of being dead.
    private const val TIMEOUT_MS = 8_000L

    /** Outer cap per server: resolve time + the fetch itself. */
    private const val BUDGET_MS = TIMEOUT_MS + 2_000L
    private const val SLOW_MS = 1_200L

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(TIMEOUT_MS + 1_000, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Probe every server in parallel. [targetNumber] picks which episode
     * represents the server (the one about to play); servers lacking it
     * fall back to their newest episode. [targetFor] resolves an episode
     * to its typed probe target; embed targets come back as
     * [ServerStatus.State.EMBED] without any network traffic.
     */
    suspend fun probeAll(
        servers: List<ServerGroup>,
        targetNumber: Float,
        targetFor: suspend (EpisodeItem) -> ProbeTarget
    ): Map<Int, ServerStatus> {
        if (servers.isEmpty()) return emptyMap()
        return coroutineScope {
            val jobs = servers.mapIndexed { index, server ->
                async { index to probeOne(server, targetNumber, targetFor) }
            }
            jobs.map { it.await() }.toMap()
        }
    }

    private suspend fun probeOne(
        server: ServerGroup,
        targetNumber: Float,
        targetFor: suspend (EpisodeItem) -> ProbeTarget
    ): ServerStatus {
        val episode = server.episodes.firstOrNull { it.number == targetNumber }
            ?: server.episodes.maxByOrNull { it.number }
            ?: return ServerStatus(ServerStatus.State.ERROR)
        val probed = withTimeoutOrNull(BUDGET_MS) {
            val start = System.nanoTime()
            val target = try {
                targetFor(episode)
            } catch (e: Exception) {
                ProbeTarget.None
            }
            when (target) {
                is ProbeTarget.Embed -> ServerStatus(ServerStatus.State.EMBED)
                ProbeTarget.None -> ServerStatus(ServerStatus.State.UNRESPONSIVE)
                is ProbeTarget.Stream -> {
                    if (target.url.isBlank()) {
                        ServerStatus(ServerStatus.State.UNRESPONSIVE)
                    } else {
                        try {
                            // Plain GET, no Range header — byte-for-byte
                            // the request the player itself makes (N6).
                            val req = Request.Builder()
                                .url(target.url)
                                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) Nflix/1.0")
                                .header("Referer", "https://phimapi.com/")
                                .get()
                                .build()
                            client.newCall(req).execute().use { resp ->
                                val ms = (System.nanoTime() - start) / 1_000_000
                                when {
                                    resp.isSuccessful || resp.code in 300..399 -> ServerStatus(
                                        if (ms <= SLOW_MS) ServerStatus.State.OK else ServerStatus.State.SLOW,
                                        ms
                                    )
                                    // The host ANSWERED with 4xx/5xx: that is
                                    // a real verdict on this stream URL.
                                    else -> ServerStatus(ServerStatus.State.ERROR)
                                }
                            }
                        } catch (e: Exception) {
                            // Timeout / DNS / TLS: no answer in time is
                            // NOT proof of a dead stream (N6).
                            ServerStatus(ServerStatus.State.UNRESPONSIVE)
                        }
                    }
                }
            }
        }
        return probed ?: ServerStatus(ServerStatus.State.UNRESPONSIVE)
    }
}
