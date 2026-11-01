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
 */
data class ServerStatus(
    val state: State = State.UNKNOWN,
    val ms: Long? = null
) {
    enum class State { UNKNOWN, OK, SLOW, ERROR }

    val isAlive: Boolean get() = state == State.OK || state == State.SLOW

    /** Short truthful label for the server bar and the server sheet. */
    fun describe(): String = when (state) {
        State.UNKNOWN -> "Chưa kiểm tra"
        State.OK -> "đang tốt · nhanh" + (ms?.let { " · $it ms" } ?: "")
        State.SLOW -> "đang chậm" + (ms?.let { " · $it ms" } ?: "")
        State.ERROR -> "không phản hồi · đang lỗi"
    }
}

/**
 * Lightweight parallel server measurement behind the "Tự chọn server sống
 * nhanh nhất" setting (the toggle used to be dead code — nothing measured
 * anything). Each server is probed with ONE small ranged GET to the stream
 * or embed URL of the episode about to play; a hard per-server budget keeps
 * the whole sweep to a few seconds and it never blocks the UI (callers run
 * it in a coroutine and publish results into state when done).
 */
object ServerProbe {

    private const val TIMEOUT_MS = 2_500L
    private const val SLOW_MS = 1_200L

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(TIMEOUT_MS + 500, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Probe every server in parallel. [targetNumber] picks which episode's
     * URL represents the server (the one about to play); servers lacking it
     * fall back to their newest episode. [urlFor] resolves an episode to the
     * URL worth timing (direct stream first, embed second; for Donghua it
     * resolves the embed iframe, whose lookup time counts into the total).
     */
    suspend fun probeAll(
        servers: List<ServerGroup>,
        targetNumber: Float,
        urlFor: suspend (EpisodeItem) -> String?
    ): Map<Int, ServerStatus> {
        if (servers.isEmpty()) return emptyMap()
        return coroutineScope {
            val jobs = servers.mapIndexed { index, server ->
                async { index to probeOne(server, targetNumber, urlFor) }
            }
            jobs.map { it.await() }.toMap()
        }
    }

    private suspend fun probeOne(
        server: ServerGroup,
        targetNumber: Float,
        urlFor: suspend (EpisodeItem) -> String?
    ): ServerStatus {
        val episode = server.episodes.firstOrNull { it.number == targetNumber }
            ?: server.episodes.maxByOrNull { it.number }
            ?: return ServerStatus(ServerStatus.State.ERROR)
        val probed = withTimeoutOrNull(TIMEOUT_MS + 2_000) {
            val start = System.nanoTime()
            val url = try {
                urlFor(episode)
            } catch (e: Exception) {
                null
            }
            if (url.isNullOrBlank()) {
                ServerStatus(ServerStatus.State.ERROR)
            } else {
                try {
                    val req = Request.Builder()
                        .url(url)
                        .header("Range", "bytes=0-1023")
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
                            else -> ServerStatus(ServerStatus.State.ERROR)
                        }
                    }
                } catch (e: Exception) {
                    ServerStatus(ServerStatus.State.ERROR)
                }
            }
        }
        return probed ?: ServerStatus(ServerStatus.State.ERROR)
    }
}
