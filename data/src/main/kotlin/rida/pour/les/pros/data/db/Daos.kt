package rida.pour.les.pros.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ClientDao {
    @Transaction
    @Query("SELECT * FROM client WHERE deleted = 0 ORDER BY isSystem ASC, sortOrder ASC, code COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ClientWithAliases>>

    @Transaction
    @Query("SELECT * FROM client WHERE deleted = 0 ORDER BY isSystem ASC, sortOrder ASC, code COLLATE NOCASE ASC")
    suspend fun getAll(): List<ClientWithAliases>

    @Query("SELECT * FROM client WHERE id = :id")
    suspend fun getById(id: String): ClientEntity?

    @Query("SELECT * FROM client WHERE codeKey = :key")
    suspend fun getByKey(key: String): ClientEntity?

    @Insert
    suspend fun insert(client: ClientEntity)

    @Update
    suspend fun update(client: ClientEntity)

    @Query("SELECT COUNT(*) FROM rida_line WHERE clientId = :clientId")
    suspend fun lineCount(clientId: String): Int

    @Query("DELETE FROM client WHERE id = :id")
    suspend fun delete(id: String)

    @Insert
    suspend fun insertAlias(alias: ClientAliasEntity)

    @Query("SELECT * FROM client_alias WHERE aliasKey = :key")
    suspend fun aliasByKey(key: String): ClientAliasEntity?

    @Query("DELETE FROM client_alias WHERE id = :id")
    suspend fun deleteAlias(id: Long)
}

@Dao
interface LineDao {
    @Transaction
    @Query("SELECT * FROM rida_line WHERE clientId = :clientId")
    fun observeByClient(clientId: String): Flow<List<LineWithHistory>>

    @Transaction
    @Query("SELECT * FROM rida_line")
    fun observeAll(): Flow<List<LineWithHistory>>

    @Transaction
    @Query("SELECT * FROM rida_line")
    suspend fun getAll(): List<LineWithHistory>

    @Transaction
    @Query("SELECT * FROM rida_line WHERE businessId = :id")
    suspend fun getByBusinessId(id: Long): LineWithHistory?

    @Transaction
    @Query("SELECT * FROM rida_line WHERE businessId = :id")
    fun observeByBusinessId(id: Long): Flow<LineWithHistory?>

    @Query("SELECT MAX(businessId) FROM rida_line")
    suspend fun maxBusinessId(): Long?

    @Insert
    suspend fun insert(line: RidaLineEntity)

    @Update
    suspend fun update(line: RidaLineEntity)

    @Insert
    suspend fun insertHistory(entries: List<HistoryEntryEntity>)

    @Query("SELECT COUNT(*) FROM history_entry WHERE lineUuid = :uuid")
    suspend fun historyCount(uuid: String): Int
}

@Dao
interface SequenceDao {
    @Query("SELECT nextValue FROM id_sequence WHERE name = :name")
    suspend fun get(name: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(seq: IdSequenceEntity)
}

@Dao
interface AdminDao {
    @Query("DELETE FROM history_entry") suspend fun clearHistory()
    @Query("DELETE FROM rida_line") suspend fun clearLines()
    @Query("DELETE FROM client_alias") suspend fun clearAliases()
    @Query("DELETE FROM client") suspend fun clearClients()
    @Query("DELETE FROM id_sequence") suspend fun clearSequences()
}
