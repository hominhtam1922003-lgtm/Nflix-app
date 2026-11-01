package com.example.nflix.data

import com.example.nflix.data.local.TitleEntity
import com.example.nflix.data.model.Family
import com.example.nflix.data.model.TitleItem

fun TitleItem.toEntity(): TitleEntity = TitleEntity(
    id = id,
    family = family.name,
    sourceId = sourceId,
    slug = slug,
    name = name,
    originName = originName,
    posterUrl = posterUrl,
    thumbUrl = thumbUrl,
    quality = quality,
    versionBadge = versionBadge,
    latestLabel = latestLabel,
    episodeCount = episodeCount,
    year = year,
    updatedEpochSec = updatedEpochSec
)

fun TitleEntity.toItem(): TitleItem = TitleItem(
    id = id,
    family = runCatching { Family.valueOf(family) }.getOrDefault(Family.PHIM),
    sourceId = sourceId,
    slug = slug,
    name = name,
    originName = originName,
    posterUrl = posterUrl,
    thumbUrl = thumbUrl,
    quality = quality,
    versionBadge = versionBadge,
    latestLabel = latestLabel,
    episodeCount = episodeCount,
    year = year,
    updatedEpochSec = updatedEpochSec
)

/** Unit word ("tập"/"chap") for progress labels. */
fun Family.unitWord(): String = when (this) {
    Family.TRUYEN, Family.MANHWA -> "chap"
    else -> "tập"
}
