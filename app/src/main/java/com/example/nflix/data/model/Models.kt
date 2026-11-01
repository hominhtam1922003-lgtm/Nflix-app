package com.example.nflix.data.model

enum class Family(val label: String) {
    PHIM("Phim"),
    ANIME("Anime"),
    DONGHUA("Donghua"),
    MANHWA("Manhwa"),
    TRUYEN("Truyện")
}

/** One row in any list/shelf across the app. */
data class TitleItem(
    val id: String,                 // "<family>:<source>:<slug>"
    val family: Family,
    val sourceId: String,           // "kkphim", "hhkungfu", "nettruyen", ...
    val slug: String,
    val name: String,
    val originName: String = "",
    val posterUrl: String = "",     // vertical art
    val thumbUrl: String = "",      // horizontal art
    val quality: String = "",       // "FHD" / "HD" / "4K" / "" (no badge)
    val versionBadge: String = "",  // "TM" / "VS" / ""
    val latestLabel: String = "",   // "Tập 19" / "Chap 88" / "Full"
    val episodeCount: Int = 0,      // newest episode/chapter number known
    val year: Int = 0,
    val genres: List<String> = emptyList(),
    val updatedEpochSec: Long = 0,  // for "x phút trước"
    val updatedLabel: String = "",  // pre-formatted relative time text from the source, when it gives text
    val isHot: Boolean = false,
    val isNew: Boolean = false
)

data class EpisodeItem(
    val label: String,              // "Tập 01" / "Chap 88"
    val number: Float,              // numeric position for sorting/ranges
    val slug: String,
    val streamUrl: String = "",     // direct m3u8 (video)
    val embedUrl: String = ""       // fallback embed player
)

data class ServerGroup(
    val name: String,               // "Thuyết Minh" / "Vietsub" / "Lồng Tiếng"
    val langKey: String = "",       // "tm" / "vs" / "lt"
    val episodes: List<EpisodeItem> = emptyList()
)

data class ChapterItem(
    val label: String,              // "Chap 88"
    val number: Float,
    val id: String,                 // source chapter id/slug
    val updatedLabel: String = ""   // relative time text from the source
)

data class TitleDetail(
    val item: TitleItem,
    val description: String = "",
    val status: String = "",        // "Đang tiến hành" / "Hoàn thành" / ""
    val totalCount: Int = 0,        // total episodes/chapters when known
    val durationLabel: String = "",
    val actors: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val servers: List<ServerGroup> = emptyList(),   // video families
    val chapters: List<ChapterItem> = emptyList(),  // comic families
    val trailerUrl: String = "",                    // YouTube trailer (KKPhim trailer_url), "" when none
    val genreLinks: List<Pair<String, String>> = emptyList(), // (label, slug) — powers "Cùng thể loại"
    // 1.1.1 (N1): true when the merged front TRIED CobePhim for this title
    // and the source itself was unreachable (not merely "does not carry
    // it"). The Detail screen must say so out loud — a dead source used to
    // vanish silently, leaving the user staring at one KKPhim server.
    val cobeFailed: Boolean = false
)
