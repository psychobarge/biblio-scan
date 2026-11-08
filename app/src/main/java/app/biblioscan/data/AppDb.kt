package app.biblioscan.data

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

    @Query("DELETE FROM Book WHERE id = :id")
    suspend fun delete(id: String)
}

// Never use fallbackToDestructiveMigration: schema changes need real migrations (SPEC §4).
@Database(entities = [Book::class], version = 1)
abstract class AppDb : RoomDatabase() {
    abstract fun books(): BookDao
}
