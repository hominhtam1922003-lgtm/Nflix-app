package com.example.nflix.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** One newer release found on the repo's GitHub Releases (1.3.4). */
data class UpdateInfo(
    val versionName: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val changelog: String,
    val force: Boolean
)

/**
 * 1.3.4: in-app updates (design approved by the user 2026-11-03).
 *
 * The app is not on Google Play, so Android can never install an
 * update silently: the honest flow is check → dialog → in-app
 * download with progress → the SYSTEM installer screen. The one tap
 * on that last screen is the platform's hard requirement — the
 * dialog says so instead of promising magic. Data survives because
 * every build is signed with the same fixed keystore.
 *
 * Releases are read from the public GitHub repo (no token needed).
 * A release whose body carries the [FORCE] marker is a forced update
 * (the dialog loses its "Để sau"); the marker is stripped from the
 * changelog shown to the user.
 */
object UpdateManager {

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/hominhtam1922003-lgtm/Nflix-app/releases/latest"

    /** A release whose body carries this marker is a forced update. */
    private const val FORCE_MARKER = "[FORCE]"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun currentVersion(context: Context): String =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }

    /** Dotted-numeric compare: is [candidate] strictly newer than [current]? */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.trim().trimStart('v', 'V').split('.')
        val b = current.trim().trimStart('v', 'V').split('.')
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(i)?.toIntOrNull() ?: 0
            val y = b.getOrNull(i)?.toIntOrNull() ?: 0
            if (x != y) return x > y
        }
        return false
    }

    /** The newest release, when it is newer than the installed build. */
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val current = currentVersion(context)
        if (current.isBlank()) return@withContext null
        val req = Request.Builder()
            .url(LATEST_RELEASE_API)
            .header("User-Agent", "Nflix-Android")
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            resp.body?.string() ?: return@withContext null
        }
        val root = JSONObject(body)
        val remote = root.optString("tag_name").ifBlank { root.optString("name") }
        if (!isNewer(remote, current)) return@withContext null
        val assets = root.optJSONArray("assets") ?: return@withContext null
        var apkUrl = ""
        var apkSize = 0L
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                apkUrl = a.optString("browser_download_url")
                apkSize = a.optLong("size")
                break
            }
        }
        if (apkUrl.isBlank()) return@withContext null
        val rawBody = root.optString("body")
        UpdateInfo(
            versionName = remote.trim().trimStart('v', 'V'),
            apkUrl = apkUrl,
            apkSizeBytes = apkSize,
            changelog = rawBody.replace(FORCE_MARKER, "").trim(),
            force = rawBody.contains(FORCE_MARKER)
        )
    }

    /** Download the release APK into the cache, reporting 0..1 progress. */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "nflix-${info.versionName}.apk")
        val req = Request.Builder().url(info.apkUrl).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Tải bản cập nhật thất bại (HTTP ${resp.code})")
            }
            val body = resp.body
                ?: throw IllegalStateException("Tải bản cập nhật thất bại")
            val total = if (info.apkSizeBytes > 0) info.apkSizeBytes else body.contentLength()
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        }
        out
    }

    /** True when Android allows this app to hand an APK to the installer. */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** The one-time system grant screen for "allow installs from Nflix". */
    fun installPermissionIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Hand the downloaded APK to the system installer (the user's one tap). */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
