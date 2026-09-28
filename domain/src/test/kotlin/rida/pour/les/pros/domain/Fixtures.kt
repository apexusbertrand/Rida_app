package rida.pour.les.pros.domain

import java.time.LocalDate

object F {
    val TODAY: LocalDate = LocalDate.of(2026, 9, 28)
    val DERET = Client("c-deret", "DERET")
    val CIM = Client("c-cim", "CIM")
    val ACME = Client("c-acme", "ACME-CONSEIL", aliases = listOf("Acme Conseil", "Akmé"))
    val NON_ID = Client("c-nonid", Rida.NON_IDENTIFIE, isSystem = true)
    val CLIENTS = listOf(DERET, CIM, ACME, NON_ID)
    val BY_ID = CLIENTS.associateBy { it.id }

    fun line(
        id: Long,
        client: Client = DERET,
        statut: RidaStatus = RidaStatus.A_FAIRE,
        echeance: LocalDate? = null,
        realisation: LocalDate? = null,
        commentaire: String = "",
        sujet: String = "Sujet $id",
        hidden: Boolean = false,
    ) = RidaLine(
        uuid = "u$id", id = id, clientId = client.id, createdDate = TODAY.minusDays(10),
        interlocuteur = "Bertrand", type = RidaType.ACTION, sujet = sujet, action = "Action $id",
        statut = statut, echeance = echeance, realisation = realisation, commentaire = commentaire,
        history = listOf(HistoryEntry(TODAY.minusDays(10), "création — $sujet", HistoryOrigin.AGENT)),
        recul = "", hidden = hidden,
    )
}
