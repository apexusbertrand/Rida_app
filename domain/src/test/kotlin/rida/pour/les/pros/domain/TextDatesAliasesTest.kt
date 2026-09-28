package rida.pour.les.pros.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TextDatesAliasesTest {
    @Test fun dates_formats_acceptes() {
        assertEquals(LocalDate.of(2026, 9, 7), RidaDates.parse("07/09/2026"))
        assertEquals(LocalDate.of(2026, 9, 7), RidaDates.parse("7/9/2026"))
        assertEquals(LocalDate.of(2026, 9, 7), RidaDates.parse("2026-09-07"))
        assertEquals(LocalDate.of(2026, 9, 7), RidaDates.parse("46272"))
        assertNull(RidaDates.parse("31/02/2026"))
        assertNull(RidaDates.parse("demain"))
        assertEquals("07/09/2026", RidaDates.format(LocalDate.of(2026, 9, 7)))
    }

    @Test fun statuts_et_types_tolerants() {
        assertEquals(RidaStatus.TERMINE, RidaStatus.fromLabel("Termine"))
        assertEquals(RidaStatus.A_FAIRE, RidaStatus.fromLabel("à faire"))
        assertEquals(RidaType.DECISION, RidaType.fromLabel("décision"))
    }

    @Test fun historique_parse_et_join() {
        val raw = "01/09/2026 : création — x\n05/09/2026 : relance\nsuite sans date"
        val e = HistoryFormat.parse(raw, F.TODAY, HistoryOrigin.IMPORT)
        assertEquals(2, e.size)
        assertEquals("relance\nsuite sans date", e[1].text)
        assertEquals("01/09/2026 : création — x", HistoryFormat.line(e[0]))
        assertEquals(1, HistoryFormat.parse("texte libre", F.TODAY, HistoryOrigin.IMPORT).size)
    }

    @Test fun resolution_client_par_code_ou_alias() {
        assertEquals(F.ACME, Aliases.resolve("acme conseil", F.CLIENTS))
        assertEquals(F.ACME, Aliases.resolve("AKME", F.CLIENTS))
        assertEquals(F.DERET, Aliases.resolve("Déret", F.CLIENTS))
        assertNull(Aliases.resolve("Dupond", F.CLIENTS))
    }

    @Test fun normalisation_du_texte_par_alias() {
        val map = mapOf("Absys Cyborg" to "Absys-Cyborg", "Absisses" to "Absys-Cyborg", "Apsys Cyborg" to "Absys-Cyborg")
        assertEquals(
            "Point avec Absys-Cyborg et Absys-Cyborg, pas Absyssesx",
            Aliases.normalizeText("Point avec absys  cyborg et Absisses, pas Absyssesx", map),
        )
        assertEquals("Absys-Cyborg reste Absys-Cyborg", Aliases.normalizeText("Absys-Cyborg reste Apsys-Cyborg", map))
    }
}
