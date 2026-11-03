package com.example.nflix.data.model

/** Sheet "Trạng thái" group (1.2.0): single-select, none = all. */
const val STATUS_ALL = 0
const val STATUS_ONGOING = 1
const val STATUS_COMPLETED = 2

fun TitleItem.matchesStatus(status: Int): Boolean = when (status) {
    STATUS_ONGOING -> !completed
    STATUS_COMPLETED -> completed
    else -> true
}

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
    val sourceId: String,           // "cobe", "hhkungfu", "nettruyen", ... ("kkphim" = legacy rows, pre-1.3.4)
    val slug: String,
    val name: String,
    val originName: String = "",
    val posterUrl: String = "",     // vertical art
    val thumbUrl: String = "",      // horizontal art
    /**
     * Country-law fix (2026-11-01): last-resort cover URL for a Cobe
     * item whose own art is dead at the source (measured:
     * naruto-shippuden's Cobe poster AND thumb 404). Filled from
     * AnimeCatalog's persisted fallback cache (the fetcher that built
     * it died with KKPhim in 1.3.4 — cached entries still apply),
     * never persisted on the item itself; the display chain tries
     * the item's own art first, always.
     */
    val posterFallbackUrl: String = "",
    val quality: String = "",       // "FHD" / "HD" / "4K" / "" (no badge)
    val versionBadge: String = "",  // "TM" / "VS" / ""
    /**
     * V2 (A1): the source's own language descriptor, verbatim — KKPhim's
     * lang_key set joined ("tm,vs") or Cobe's `language` string
     * ("Vietsub + Thuyết Minh"). The Phim/Anime PHIÊN BẢN sheet group
     * filters LOCALLY on this (the KKPhim API ignores its version=
     * param — measured). Empty = the source said nothing; never guessed.
     */
    val languages: String = "",
    /**
     * V2 (A2): the source's own view counter for the Cobe view charts
     * (view_day / view_week of the sorted search it came from). 0 = this
     * item did not come from a view chart — the UI shows no number then.
     */
    val viewCount: Long = 0,
    val latestLabel: String = "",   // "Tập 19" / "Chap 88" / "Full"
    val episodeCount: Int = 0,      // newest episode/chapter number known
    val year: Int = 0,
    val genres: List<String> = emptyList(),
    /** 1.2.0: source-side genre slugs parallel to [genres] (local genre filtering). */
    val genreSlugs: List<String> = emptyList(),
    /**
     * 1.2.0: completion derived from EPISODE COUNTS (never the raw status
     * string — Cobe marks airing shows "completed"). Powers the
     * "Đang ra tiếp | Trọn bộ" sheet group for Phim/Anime.
     */
    val completed: Boolean = false,
    val updatedEpochSec: Long = 0,  // for "x phút trước"
    val updatedLabel: String = "",  // pre-formatted relative time text from the source, when it gives text
    val isHot: Boolean = false,
    val isNew: Boolean = false,
    /**
     * 1.3.5: country slugs of the record ("nhat-ban", "han-quoc", ...)
     * when the source publishes them — PhimCobe records do; Cobe
     * records never did, which is why the Anime country gate used to
     * need harvested slug pools. Empty = the source said nothing.
     */
    val countrySlugs: List<String> = emptyList(),
    /** 1.3.5: the source's own animation flag (PhimCobe's `isAnime`). */
    val isAnime: Boolean = false
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
    val episodes: List<EpisodeItem> = emptyList(),
    /**
     * V2 (A7): Donghua stream-variant key — the player.php `type` of
     * this group ("tiktik" / "pro" / "vip4k" / "vip4kv2"). The group's
     * NAME carries the source's own label verbatim ("1080P V1",
     * "4K V1", ...). Empty for every other source.
     */
    val variantKey: String = ""
)

/**
 * V2 (A5): one cast member as the SOURCE publishes them — name plus the
 * Cobe person slug and TMDB thumbnail when the record carries them.
 * slug blank = no Cobe actor page is known for this name, so the UI
 * keeps it as plain text (never a dead link).
 */
data class ActorRef(
    val name: String,
    val slug: String = "",
    val thumb: String = ""
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
    /** V2 (A5): actors with their Cobe page data when the record has it. */
    val actorRefs: List<ActorRef> = emptyList(),
    val directors: List<String> = emptyList(),
    val servers: List<ServerGroup> = emptyList(),   // video families
    val chapters: List<ChapterItem> = emptyList(),  // comic families
    val trailerUrl: String = "",                    // YouTube trailer, "" when none
    val genreLinks: List<Pair<String, String>> = emptyList(), // (label, slug) — powers "Cùng thể loại"
    /**
     * 1.3.1: honest load note from the source adapter — set when the
     * detail was recovered through a fallback path, or when the page
     * loaded but yielded no units at all (Donghua). The Detail screen
     * shows it verbatim instead of the generic "check your network"
     * line. Empty = the load was normal; sources that never set it
     * behave exactly as before.
     */
    val loadNote: String = ""
)
