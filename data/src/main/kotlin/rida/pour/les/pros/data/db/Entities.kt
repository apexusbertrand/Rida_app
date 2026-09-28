package rida.pour.les.pros.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "client", indices = [Index(value = ["codeKey"], unique = true)])
data class ClientEntity(
    @PrimaryKey val id: String,
    val code: String,
    /** Clé normalisée (sans accents ni ponctuation) : unicité et recherche. */
    val codeKey: String,
    val isSystem: Boolean,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Entity(
    tableName = "client_alias",
    indices = [Index(value = ["aliasKey"], unique = true), Index("clientId")],
    foreignKeys = [ForeignKey(ClientEntity::class, ["id"], ["clientId"], onDelete = ForeignKey.CASCADE)],
)
data class ClientAliasEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientId: String,
    val aliasRaw: String,
    val aliasKey: String,
)

@Entity(
    tableName = "rida_line",
    indices = [Index(value = ["businessId"], unique = true), Index("clientId")],
    foreignKeys = [ForeignKey(ClientEntity::class, ["id"], ["clientId"], onDelete = ForeignKey.RESTRICT)],
)
data class RidaLineEntity(
    @PrimaryKey val uuid: String,
    val businessId: Long,
    val clientId: String,
    /** Dates stockées en jour epoch (LocalDate.toEpochDay). */
    val createdDate: Long,
    val interlocuteur: String,
    val type: String,
    val sujet: String,
    val action: String,
    val statut: String,
    val echeance: Long?,
    val realisation: Long?,
    val commentaire: String,
    val recul: String,
    val hidden: Boolean,
    val hiddenReason: String?,
    val mergedInto: Long?,
    val originalSujet: String?,
    val updatedAt: Long,
)

@Entity(
    tableName = "history_entry",
    indices = [Index("lineUuid")],
    foreignKeys = [ForeignKey(RidaLineEntity::class, ["uuid"], ["lineUuid"], onDelete = ForeignKey.CASCADE)],
)
data class HistoryEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lineUuid: String,
    val position: Int,
    val date: Long,
    val text: String,
    val origin: String,
    val createdAt: Long,
)

/** Compteur d'ID métier global : jamais décrémenté. */
@Entity(tableName = "id_sequence")
data class IdSequenceEntity(
    @PrimaryKey val name: String,
    val nextValue: Long,
)

data class LineWithHistory(
    @Embedded val line: RidaLineEntity,
    @Relation(parentColumn = "uuid", entityColumn = "lineUuid")
    val history: List<HistoryEntryEntity>,
)

data class ClientWithAliases(
    @Embedded val client: ClientEntity,
    @Relation(parentColumn = "id", entityColumn = "clientId")
    val aliases: List<ClientAliasEntity>,
)
