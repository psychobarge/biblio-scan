package app.biblioscan.data

import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM Book ORDER BY createdAt DESC")
    fun all(): Flow<List<Book>>

    @Query("SELECT * FROM Book WHERE id = :id")
    fun watch(id: String): Flow<Book?>

    @Query("SELECT COUNT(*) FROM Book WHERE status IN ('CAPTURED', 'OCR_DONE')")
    fun pendingCount(): Flow<Int>

    @Query("SELECT * FROM Book WHERE id = :id")
    suspend fun get(id: String): Book?

    /** Tab badge (SPEC-8 §2.2): books waiting for the user, not those still being read. */
    @Query("SELECT COUNT(*) FROM Book WHERE status IN ('MATCHED', 'NEEDS_REVIEW', 'NOT_FOUND', 'ERROR')")
    fun toValidateCount(): Flow<Int>

    /** Review queue, oldest first. Skipped books stay behind the cursor, so review always moves forward. MATCHED too: SPEC-7 §4.2. */
    @Query("SELECT * FROM Book WHERE status IN ('MATCHED', 'NEEDS_REVIEW', 'NOT_FOUND') AND createdAt > :after ORDER BY createdAt LIMIT 1")
    suspend fun nextToReview(after: Long): Book?

    @Query("SELECT * FROM Book WHERE status = :status ORDER BY createdAt")
    suspend fun byStatus(status: Status): List<Book>

    @Query("SELECT * FROM Book ORDER BY createdAt")
    suspend fun snapshot(): List<Book>

    @Query("SELECT id FROM Book")
    suspend fun ids(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(books: List<Book>)

    @Update
    suspend fun update(book: Book)

    @Update
    suspend fun update(books: List<Book>)

    @Query("UPDATE Book SET placeId = :placeId WHERE id IN (:ids)")
    suspend fun moveTo(ids: List<String>, placeId: String?)

    /** Bulk validation (SPEC-7 §4.3): only sure matches; returns how many were validated. */
    @Query("UPDATE Book SET status = 'CONFIRMED', placeId = :placeId, lastError = NULL, coverUrl = NULL WHERE id IN (:ids) AND status = 'MATCHED'")
    suspend fun validate(ids: List<String>, placeId: String?): Int

    @Query("DELETE FROM Book WHERE id = :id")
    suspend fun delete(id: String)
}

// Never use fallbackToDestructiveMigration: schema changes need real migrations (SPEC §4).
@Database(entities = [Book::class, Place::class], version = 2, autoMigrations = [AutoMigration(from = 1, to = 2)])
abstract class AppDb : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun places(): PlaceDao
}
