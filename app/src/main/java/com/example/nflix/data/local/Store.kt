package com.example.nflix.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Cached title metadata so library screens render instantly and offline. */
@Entity(tableName = "titles")
data class TitleEntity(
    @PrimaryKey val id: String,
    val family: String,
    val sourceId: String,
    val slug: String,
    val name: String,
    val originName: String = "",
    val posterUrl: String = "",
    val thumbUrl: String = "",
    val quality: String = "",
    val versionBadge: String = "",
    val latestLabel: String = "",
    val episodeCount: Int = 0,
    val year: Int = 0,
    val updatedEpochSec: Long = 0
)

/**
 * The single shared state book for every title, across all four families:
 * library flags + playback/reading progress + new-episode tracking.
 */
@Entity(tableName = "user_states")
data class UserStateEntity(
    @PrimaryKey val titleId: String,
    val favorite: Boolean = false,
    val watchLater: Boolean = false,
    val following: Boolean = false,
    val notifyOn: Boolean = false,
    // progress (video: episode + position; comic: chapter + page)
    val lastUnitLabel: String = "",
    val lastUnitNumber: Float = 0f,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    // reader bookmark (Màn 11): a marked chapter + page, independent of progress
    val bookmarkChapter: Float = 0f,
    val bookmarkPage: Int = 0,
    val progressFraction: Float = 0f,
    val finished: Boolean = false,
    // new-episode tracking for followed titles
    val seenCount: Int = 0,
    val latestCount: Int = 0,
    // units the user ACTUALLY opened in player/reader, comma-separated numbers ("1,2,5.5,132")
    val watchedUnits: String = "",
    // last time the user opened/watched/read this title (Activity screen)
    val lastTouchedEpochSec: Long = 0
) {
    val newCount: Int get() = (latestCount - seenCount).coerceAtLeast(0)
}

@Dao
interface TitleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(titles: List<TitleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(title: TitleEntity)

    @Query("SELECT * FROM titles WHERE id = :id")
    fun observe(id: String): Flow<TitleEntity?>

    @Query("SELECT * FROM titles WHERE id IN (:ids)")
    fun observeByIds(ids: List<String>): Flow<List<TitleEntity>>
}

@Dao
interface UserStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: UserStateEntity)

    @Query("SELECT * FROM user_states WHERE titleId = :titleId")
    fun observe(titleId: String): Flow<UserStateEntity?>

    @Query("SELECT * FROM user_states WHERE titleId = :titleId")
    suspend fun get(titleId: String): UserStateEntity?

    @Query("SELECT * FROM user_states WHERE favorite = 1")
    fun favorites(): Flow<List<UserStateEntity>>

    @Query("SELECT * FROM user_states WHERE watchLater = 1")
    fun watchLater(): Flow<List<UserStateEntity>>

    @Query("SELECT * FROM user_states WHERE following = 1 ORDER BY (latestCount - seenCount) DESC, lastTouchedEpochSec DESC")
    fun following(): Flow<List<UserStateEntity>>

    @Query("SELECT * FROM user_states WHERE progressFraction > 0 AND finished = 0 AND lastTouchedEpochSec > 0 ORDER BY lastTouchedEpochSec DESC")
    fun continueWatching(): Flow<List<UserStateEntity>>

    @Query("SELECT * FROM user_states WHERE lastTouchedEpochSec > 0 ORDER BY lastTouchedEpochSec DESC LIMIT :limit")
    fun history(limit: Int): Flow<List<UserStateEntity>>

    @Query("SELECT * FROM user_states WHERE following = 1")
    suspend fun followingOnce(): List<UserStateEntity>

    @Query("SELECT COUNT(*) FROM user_states WHERE following = 1")
    fun followingCount(): Flow<Int>

    /**
     * Tracked titles (user rule 2026-11-01): everything the user favorited,
     * turned notifications on for, OR started watching/reading. New-episode
     * counting applies to all of these, baselined at tracking time.
     */
    @Query("SELECT * FROM user_states WHERE following = 1 OR notifyOn = 1 OR favorite = 1 OR lastUnitNumber > 0 ORDER BY (latestCount - seenCount) DESC, lastTouchedEpochSec DESC")
    fun tracked(): Flow<List<UserStateEntity>>

    @Query("SELECT * FROM user_states WHERE following = 1 OR notifyOn = 1 OR favorite = 1 OR lastUnitNumber > 0")
    suspend fun trackedOnce(): List<UserStateEntity>

    @Query("SELECT COUNT(*) FROM user_states WHERE following = 1 OR notifyOn = 1 OR favorite = 1 OR lastUnitNumber > 0")
    fun trackedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM user_states WHERE favorite = 1")
    fun favoriteCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM user_states WHERE watchLater = 1")
    fun watchLaterCount(): Flow<Int>

    @Query("UPDATE user_states SET lastTouchedEpochSec = 0")
    suspend fun clearHistory()
}

@Database(entities = [TitleEntity::class, UserStateEntity::class], version = 3, exportSchema = false)
abstract class NflixDatabase : RoomDatabase() {
    abstract fun titleDao(): TitleDao
    abstract fun userStateDao(): UserStateDao
}

/** v2 → v3: per-unit watched/read marks (only units actually opened count). */
val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE user_states ADD COLUMN watchedUnits TEXT NOT NULL DEFAULT ''")
    }
}

/** Parse the stored "1,2,5.5" watched-units string. */
fun parseWatchedUnits(raw: String): Set<Float> =
    raw.split(",").mapNotNull { it.trim().toFloatOrNull() }.toSet()

fun formatWatchedUnits(units: Set<Float>): String =
    units.sorted().joinToString(",") { n -> if (n % 1f == 0f) n.toInt().toString() else n.toString() }

fun UserStateEntity.watchedSet(): Set<Float> = parseWatchedUnits(watchedUnits)

fun UserStateEntity.withWatched(number: Float): UserStateEntity =
    copy(watchedUnits = formatWatchedUnits(watchedSet() + number))
