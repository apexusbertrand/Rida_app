package rida.pour.les.pros.data.io

import rida.pour.les.pros.domain.Client
import rida.pour.les.pros.domain.ColorRules
import rida.pour.les.pros.domain.Rida
import rida.pour.les.pros.domain.RidaLine
import rida.pour.les.pros.domain.SortRules
import rida.pour.les.pros.domain.Synthese
import rida.pour.les.pros.domain.SyntheseRow
import rida.pour.les.pros.xlsx.XlsxCell
import rida.pour.les.pros.xlsx.XlsxSheet
import java.time.LocalDate

/** Export au format du classeur actuel : REFERENTIEL + un onglet par client, 12 colonnes A→L. */
object RidaWorkbookExporter {
    private val WIDTHS = listOf(11.0, 16.0, 13.0, 40.0, 40.0, 10.0, 11.0, 11.0, 40.0, 60.0, 50.0, 6.0)

    fun workbook(clients: List<Client>, lines: List<RidaLine>, nextId: Long, today: LocalDate): List<XlsxSheet> {
        val byClient = lines.groupBy { it.clientId }
        val referentiel = XlsxSheet(
            "REFERENTIEL",
            buildList {
                add(listOf(XlsxCell.text("CLIENT", bold = true), XlsxCell.text("ID suivant", bold = true), XlsxCell.number(nextId), XlsxCell.text("ALIAS", bold = true)))
                clients.filter { !it.isSystem }.forEach { c ->
                    add(listOf(XlsxCell.text(c.code), XlsxCell.text(null), XlsxCell.text(null), XlsxCell.text(c.aliases.joinToString("; "))))
                }
            },
            columnWidths = listOf(24.0, 12.0, 8.0, 40.0),
        )
        val clientSheets = clients
            .filter { !it.isSystem || byClient[it.id].orEmpty().isNotEmpty() }
            .map { c -> XlsxSheet(c.code, clientRows(SortRules.sort(byClient[c.id].orEmpty()), today), WIDTHS) }
        return listOf(referentiel) + clientSheets
    }

    private fun clientRows(lines: List<RidaLine>, today: LocalDate): List<List<XlsxCell>> {
        val header = Rida.COLUMNS.map { XlsxCell.text(it, bold = true) }
        return listOf(header) + lines.map { l ->
            val colors = ColorRules.compute(l, today)
            listOf(
                XlsxCell.date(l.createdDate),
                XlsxCell.text(l.interlocuteur),
                XlsxCell.text(l.type.label),
                XlsxCell.text(l.sujet, wrap = true),
                XlsxCell.text(l.action, wrap = true),
                XlsxCell.text(l.statut.label, fillHex = colors.statut.hex),
                XlsxCell.date(l.echeance, fillHex = colors.echeance.hex),
                XlsxCell.date(l.realisation, fillHex = colors.realisation.hex),
                XlsxCell.text(l.commentaire, wrap = true),
                XlsxCell.text(l.historiqueText, wrap = true),
                XlsxCell.text(l.recul, wrap = true),
                XlsxCell.number(l.id),
            )
        }
    }

    /** Fichier de synthèse (une feuille, ID en première colonne). */
    fun synthese(rows: List<SyntheseRow>): List<XlsxSheet> {
        val table = Synthese.table(rows)
        val cells = table.mapIndexed { i, r -> r.map { XlsxCell.text(it, bold = i == 0, wrap = i > 0) } }
        return listOf(XlsxSheet("Synthèse", cells, listOf(7.0, 16.0, 40.0, 40.0, 13.0, 16.0, 11.0, 10.0, 10.0)))
    }
}
