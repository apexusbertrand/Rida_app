package rida.pour.les.pros.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "chat_message", indices = [Index("createdAt")])
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val origin: String,
    val text: String,
    val detail: String,
    val createdAt: Long,
    /** Fichier joint (ex. synthèse xlsx), chemin interne à l'app. */
    val attachmentPath: String?,
    val mailTo: String?,
    val mailSubject: String?,
    val mailBody: String?,
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_message ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_message ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<ChatMessageEntity>

    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

    @Query("DELETE FROM chat_message")
    suspend fun clear()
}

/** v1 → v2 : ajout de l'historique du chat. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `chat_message` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`role` TEXT NOT NULL, `origin` TEXT NOT NULL, `text` TEXT NOT NULL, `detail` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `attachmentPath` TEXT, `mailTo` TEXT, `mailSubject` TEXT, `mailBody` TEXT)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_message_createdAt` ON `chat_message` (`createdAt`)")
    }
}
