package com.example.nflix.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local store for REAL user error reports (đợt 3, mục 0.4): the Player's
 * error overlay and the Reader's "Báo lỗi chap" write here instead of
 * pretending. Kept in one JSON file in filesDir — no Room schema change.
 * The list is viewable/shareable/clearable from Settings.
 */
data class ErrorReport(
    val timeEpochSec: Long,
    val family: String,
    val titleId: String,
    val titleName: String,
    val unitLabel: String,
    val server: String,
    val message: String
)

object ReportStore {

    private const val FILE_NAME = "nflix_error_reports.json"
    private const val MAX_ENTRIES = 100

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun load(context: Context): List<ErrorReport> = runCatching {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            ErrorReport(
                timeEpochSec = o.optLong("time"),
                family = o.optString("family"),
                titleId = o.optString("titleId"),
                titleName = o.optString("titleName"),
                unitLabel = o.optString("unitLabel"),
                server = o.optString("server"),
                message = o.optString("message")
            )
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(context: Context, report: ErrorReport) {
        val next = (listOf(report) + load(context)).take(MAX_ENTRIES)
        save(context, next)
    }

    @Synchronized
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun save(context: Context, list: List<ErrorReport>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { r ->
                arr.put(
                    JSONObject()
                        .put("time", r.timeEpochSec)
                        .put("family", r.family)
                        .put("titleId", r.titleId)
                        .put("titleName", r.titleName)
                        .put("unitLabel", r.unitLabel)
                        .put("server", r.server)
                        .put("message", r.message)
                )
            }
            file(context).writeText(arr.toString())
        }
    }

    /** Human-readable one-block text for sharing the whole list out. */
    fun formatText(list: List<ErrorReport>): String {
        if (list.isEmpty()) return "Chưa có báo lỗi nào."
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US)
        val sb = StringBuilder("Báo lỗi từ app Nflix (${list.size}):\n")
        list.forEach { r ->
            sb.append("\n• [${fmt.format(Date(r.timeEpochSec * 1000))}] ${r.titleName}")
            if (r.unitLabel.isNotBlank()) sb.append(" — ${r.unitLabel}")
            if (r.server.isNotBlank()) sb.append(" · Server: ${r.server}")
            sb.append("\n  Lỗi: ${r.message}")
            sb.append("\n  (ID: ${r.titleId})")
        }
        return sb.toString()
    }
}
