package com.example.nflix.data

/** Vietnamese relative-time labels used across lists ("12 phút trước", "Hôm qua"...). */
object TimeText {
    fun relative(epochSec: Long, nowSec: Long = System.currentTimeMillis() / 1000): String {
        if (epochSec <= 0) return ""
        val diff = (nowSec - epochSec).coerceAtLeast(0)
        return when {
            diff < 60 -> "Vừa xong"
            diff < 3600 -> "${diff / 60} phút trước"
            diff < 86400 -> "${diff / 3600} giờ trước"
            diff < 172800 -> "Hôm qua"
            else -> "${diff / 86400} ngày trước"
        }
    }
}
