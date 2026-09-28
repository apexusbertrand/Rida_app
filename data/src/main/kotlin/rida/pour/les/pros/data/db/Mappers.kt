package rida.pour.les.pros.data.db

import rida.pour.les.pros.domain.Client
import rida.pour.les.pros.domain.HistoryEntry
import rida.pour.les.pros.domain.HistoryOrigin
import rida.pour.les.pros.domain.RidaLine
import rida.pour.les.pros.domain.RidaStatus
import rida.pour.les.pros.domain.RidaType
import java.time.LocalDate

internal fun ClientWithAliases.toDomain() = Client(
    id = client.id,
    code = client.code,
    isSystem = client.isSystem,
    aliases = aliases.map { it.aliasRaw },
)

internal fun LineWithHistory.toDomain(): RidaLine = RidaLine(
    uuid = line.uuid,
    id = line.businessId,
    clientId = line.clientId,
    createdDate = LocalDate.ofEpochDay(line.createdDate),
    interlocuteur = line.interlocuteur,
    type = RidaType.valueOf(line.type),
    sujet = line.sujet,
    action = line.action,
    statut = RidaStatus.valueOf(line.statut),
    echeance = line.echeance?.let(LocalDate::ofEpochDay),
    realisation = line.realisation?.let(LocalDate::ofEpochDay),
    commentaire = line.commentaire,
    history = history.sortedBy { it.position }.map {
        HistoryEntry(LocalDate.ofEpochDay(it.date), it.text, HistoryOrigin.valueOf(it.origin))
    },
    recul = line.recul,
    hidden = line.hidden,
    hiddenReason = line.hiddenReason,
    mergedInto = line.mergedInto,
    originalSujet = line.originalSujet,
)

internal fun RidaLine.toEntity(now: Long) = RidaLineEntity(
    uuid = uuid,
    businessId = id,
    clientId = clientId,
    createdDate = createdDate.toEpochDay(),
    interlocuteur = interlocuteur,
    type = type.name,
    sujet = sujet,
    action = action,
    statut = statut.name,
    echeance = echeance?.toEpochDay(),
    realisation = realisation?.toEpochDay(),
    commentaire = commentaire,
    recul = recul,
    hidden = hidden,
    hiddenReason = hiddenReason,
    mergedInto = mergedInto,
    originalSujet = originalSujet,
    updatedAt = now,
)

internal fun HistoryEntry.toEntity(lineUuid: String, position: Int, now: Long) = HistoryEntryEntity(
    lineUuid = lineUuid,
    position = position,
    date = date.toEpochDay(),
    text = text,
    origin = origin.name,
    createdAt = now,
)
