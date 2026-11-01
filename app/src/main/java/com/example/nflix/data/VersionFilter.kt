package com.example.nflix.data

import com.example.nflix.data.model.TitleItem

/**
 * V2 (A1): the PHIÊN BẢN (audio version) sheet group for the Phim and
 * Anime tabs. The KKPhim API silently IGNORES its `version=` parameter
 * (measured: identical totalItems for every value), so this filter is
 * purely LOCAL, run over the source's own language descriptor
 * ([TitleItem.languages]) that each adapter already parses. Nothing is
 * inferred: an item whose source published no language info matches
 * only "Tất cả".
 */
object VersionFilter {

    const val ALL = ""
    const val VS = "vs"
    const val TM = "tm"
    const val LT = "lt"
    const val SN = "sn"

    /** Sheet chips in display order: (key, label). */
    val OPTIONS = listOf(
        ALL to "Tất cả",
        VS to "Vietsub",
        TM to "Thuyết minh",
        LT to "Lồng tiếng",
        SN to "Song ngữ"
    )

    fun labelOf(key: String): String =
        OPTIONS.firstOrNull { it.first == key }?.second ?: ""

    /** The version keys one item really carries, from its own descriptor. */
    fun keysOf(item: TitleItem): Set<String> {
        val keys = mutableSetOf<String>()
        val raw = item.languages.lowercase()
        if (raw.isNotBlank()) {
            if (raw.contains("thuyết minh") || raw.contains("thuyet minh") ||
                Regex("""\btm\b""").containsMatchIn(raw)
            ) {
                keys += TM
            }
            if (raw.contains("lồng tiếng") || raw.contains("long tieng") ||
                Regex("""\blt\b""").containsMatchIn(raw)
            ) {
                keys += LT
            }
            if (raw.contains("vietsub") || raw.contains("phụ đề") ||
                raw.contains("phu de") || Regex("""\bvs\b""").containsMatchIn(raw)
            ) {
                keys += VS
            }
            if (raw.contains("song ngữ") || raw.contains("song ngu")) {
                keys += SN
            }
        }
        // Fallback: the single badge the adapters derived when no raw
        // descriptor was kept (older cached records).
        if (keys.isEmpty()) {
            when (item.versionBadge) {
                "TM" -> keys += TM
                "VS" -> keys += VS
                "LT" -> keys += LT
            }
        }
        return keys
    }

    fun matches(item: TitleItem, version: String): Boolean =
        version == ALL || version in keysOf(item)
}

fun TitleItem.matchesVersion(version: String): Boolean =
    VersionFilter.matches(this, version)
