package com.example.nflix.data

import com.example.nflix.data.model.Family
import com.example.nflix.data.model.TitleItem

/**
 * Public web URLs for sharing (đợt 3, mục B4). Each pattern is the exact
 * detail URL the matching adapter fetches:
 *  - NetTruyen: <base>/truyen-tranh/<slug>
 *  - TruyenQQ:  <host>/<slug> (primary host; the twin host serves the same path)
 *  - HhKungfu:  <base>/<slug>
 *  - PhimCobe:  <base>/phim/<slug> (1.3.5 — items saved under the
 *    retired "cobe"/"kkphim" ids share this URL: they re-home onto
 *    PhimCobe by slug.)
 */
object ShareLinks {

    fun publicUrl(item: TitleItem): String = when (item.sourceId) {
        "nettruyen" -> "https://nettruyen.gg/truyen-tranh/${item.slug}"
        "truyenqq" -> "https://nettruyenqq.org/${item.slug}"
        "hhkungfu" -> "https://hhkungfu.ee/${item.slug}"
        "cobe", "kkphim" -> "https://www.phimcobe.com/phim/${item.slug}"
        else -> ""
    }

    /**
     * Share-sheet text: title + family, the unit the user last opened (or
     * the newest one when they haven't started), and the source's public
     * URL when one really exists.
     */
    fun shareText(item: TitleItem, lastUnitLabel: String): String {
        val isComic = item.family == Family.TRUYEN || item.family == Family.MANHWA
        val sb = StringBuilder()
        sb.append(item.name)
        if (item.originName.isNotBlank() && item.originName != item.name) {
            sb.append(" (${item.originName})")
        }
        sb.append(" · ${item.family.label}")
        if (lastUnitLabel.isNotBlank()) {
            sb.append("\n${if (isComic) "Đang đọc" else "Đang xem"}: $lastUnitLabel")
        } else if (item.latestLabel.isNotBlank()) {
            sb.append("\nMới nhất: ${item.latestLabel}")
        }
        val url = publicUrl(item)
        if (url.isNotBlank()) sb.append("\n$url")
        sb.append("\n— xem trên app Nflix")
        return sb.toString()
    }
}
