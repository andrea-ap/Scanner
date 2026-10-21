package com.scanner.app

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val notes: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "photos",
    foreignKeys = [ForeignKey(Session::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class Photo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val filePath: String,
    val ocrText: String = "",
    val createdAt: Long,
)

val Photo.isPdf: Boolean get() = filePath.endsWith(".pdf", ignoreCase = true)

/** Per i PDF l'anteprima è l'immagine della prima pagina; per le foto è la foto stessa. */
val Photo.displayPath: String get() = if (isPdf) "$filePath.png" else filePath

@Dao
interface ScannerDao {
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<Session>>

    @Query(
        "SELECT DISTINCT s.* FROM sessions s LEFT JOIN photos p ON p.sessionId = s.id " +
            "WHERE s.name LIKE :q OR s.notes LIKE :q OR p.ocrText LIKE :q ORDER BY s.updatedAt DESC"
    )
    fun search(q: String): Flow<List<Session>>

    @Query("SELECT * FROM sessions")
    suspend fun allSessions(): List<Session>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeSession(id: Long): Flow<Session?>

    @Query("SELECT * FROM photos WHERE sessionId = :id ORDER BY createdAt")
    fun observePhotos(id: Long): Flow<List<Photo>>

    @Query("SELECT * FROM photos WHERE sessionId = :id ORDER BY createdAt")
    suspend fun photosOf(id: Long): List<Photo>

    @Query("SELECT * FROM photos ORDER BY createdAt")
    fun observeAllPhotos(): Flow<List<Photo>>

    @Insert suspend fun insertSession(s: Session): Long
    @Insert suspend fun insertPhoto(p: Photo): Long
    @Update suspend fun updatePhoto(p: Photo)
    @Delete suspend fun deletePhoto(p: Photo)

    @Query("UPDATE sessions SET name = :name, updatedAt = :t WHERE id = :id")
    suspend fun rename(id: Long, name: String, t: Long)

    @Query("UPDATE sessions SET notes = :notes, updatedAt = :t WHERE id = :id")
    suspend fun setNotes(id: Long, notes: String, t: Long)

    @Query("UPDATE sessions SET updatedAt = :t WHERE id = :id")
    suspend fun touch(id: Long, t: Long)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)
}

@Database(entities = [Session::class, Photo::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): ScannerDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "scanner.db")
                .build().also { instance = it }
        }
    }
}
