package rida.pour.les.pros.xlsx

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate

class XlsxRoundTripTest {
    @Test fun ecriture_puis_lecture_multi_onglets() {
        val out = ByteArrayOutputStream()
        XlsxWriter.write(
            listOf(
                XlsxSheet(
                    "DERET",
                    listOf(
                        listOf(XlsxCell.text("Date", bold = true), XlsxCell.text("Sujet", bold = true), XlsxCell.text("ID", bold = true)),
                        listOf(XlsxCell.date(LocalDate.of(2026, 9, 7)), XlsxCell.text("Devis <A&B> \"x\"\nligne 2", wrap = true), XlsxCell.number(12)),
                        listOf(XlsxCell.text(null), XlsxCell.text("Accentué é à ç", fillHex = "#FFA500"), XlsxCell.number(13)),
                    ),
                    columnWidths = listOf(12.0, 40.0, 6.0),
                ),
                XlsxSheet("Nom/interdit:[trop long pour un onglet excel]", listOf(listOf(XlsxCell.text("x")))),
                XlsxSheet("DERET", listOf(listOf(XlsxCell.text("doublon")))),
            ),
            out,
        )
        val sheets = XlsxReader.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(3, sheets.size)
        assertEquals("DERET", sheets[0].name)
        assertEquals("46272", sheets[0].cell(1, 0))
        assertEquals("Devis <A&B> \"x\"\nligne 2", sheets[0].cell(1, 1))
        assertEquals("12", sheets[0].cell(1, 2))
        assertEquals("", sheets[0].cell(2, 0))
        assertEquals("Accentué é à ç", sheets[0].cell(2, 1))
        assertEquals(31, sheets[1].name.length)
        assertEquals("DERET (2)", sheets[2].name)
    }

    @Test fun references_de_colonnes() {
        assertEquals("A", XlsxWriter.colName(0))
        assertEquals("Z", XlsxWriter.colName(25))
        assertEquals("AA", XlsxWriter.colName(26))
        assertEquals(26, XlsxReader.colFromRef("AA12"))
        assertEquals(11, XlsxReader.colFromRef("L3"))
    }
}
