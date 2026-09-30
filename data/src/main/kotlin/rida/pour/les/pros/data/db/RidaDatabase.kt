package rida.pour.les.pros.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ClientEntity::class,
        ClientAliasEntity::class,
        RidaLineEntity::class,
        HistoryEntryEntity::class,
        IdSequenceEntity::class,
        ChatMessageEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class RidaDatabase : RoomDatabase() {
    abstract fun clientDao(): ClientDao
    abstract fun lineDao(): LineDao
    abstract fun sequenceDao(): SequenceDao
    abstract fun adminDao(): AdminDao
    abstract fun chatDao(): ChatDao

    companion object {
        const val NAME = "rida.db"
    }
}
