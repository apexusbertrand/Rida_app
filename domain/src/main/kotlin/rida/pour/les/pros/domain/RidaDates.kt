package rida.pour.les.pros.domain

import java.time.DateTimeException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object RidaDates {
    private val FR: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val EXCEL_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)

    fun format(date: LocalDate?): String = date?.format(FR) ?: ""

    /**
     * Lit une date "JJ/MM/AAAA" (tolère J/M/AAAA, séparateurs - ou .), ou "AAAA-MM-JJ",
     * ou un numéro de série Excel/Sheets. Renvoie null si illisible.
     */
    fun parse(raw: String?): LocalDate? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        Regex("^(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})$").find(s)?.let { m ->
            val (d, mo, y) = m.destructured
            return safe { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }
        }
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(s)?.let { m ->
            val (y, mo, d) = m.destructured
            return safe { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }
        }
        s.replace(',', '.').toDoubleOrNull()?.let { serial ->
            if (serial in 1.0..2958465.0) return EXCEL_EPOCH.plusDays(serial.toLong())
        }
        return null
    }

    fun defaultEcheance(created: LocalDate): LocalDate = created.plusDays(Rida.DEFAULT_ECHEANCE_DAYS)

    private inline fun safe(block: () -> LocalDate): LocalDate? =
        try { block() } catch (_: DateTimeException) { null }
}
