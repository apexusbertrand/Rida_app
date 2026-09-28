package rida.pour.les.pros.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LineRulesTest {
    private fun draft(
        type: RidaType = RidaType.ACTION,
        client: Client = F.DERET,
        sujet: String = "Relancer le devis",
        action: String = "Envoyer relance",
        commentaire: String = "",
        statut: RidaStatus = RidaStatus.A_FAIRE,
    ) = LineDraft(client, null, type, sujet, action, statut, commentaire = commentaire, historySummary = "relance")

    @Test fun creation_impose_date_du_jour_et_echeance_J15_pour_action() {
        val l = LineRules.create(draft(), 41, "u", F.TODAY, "Bertrand")
        assertEquals(F.TODAY, l.createdDate)
        assertEquals(F.TODAY.plusDays(15), l.echeance)
        assertEquals("Bertrand", l.interlocuteur)
        assertEquals("", l.commentaire)
        assertEquals("28/09/2026 : création — relance", l.historiqueText)
        assertEquals(41, l.id)
    }

    @Test fun information_sans_echeance_reste_sans_echeance() {
        val l = LineRules.create(draft(type = RidaType.INFORMATION), 1, "u", F.TODAY, "B")
        assertNull(l.echeance)
    }

    @Test fun creation_terminee_renseigne_realisation() {
        val l = LineRules.create(draft(statut = RidaStatus.TERMINE), 1, "u", F.TODAY, "B")
        assertEquals(F.TODAY, l.realisation)
    }

    @Test fun commentaire_refuse_hors_non_identifie() {
        expectError("commentaire") { LineRules.create(draft(commentaire = "x"), 1, "u", F.TODAY, "B") }
    }

    @Test fun commentaire_accepte_en_saisie_manuelle() {
        val l = LineRules.create(draft(commentaire = "ma note").copy(byUser = true), 1, "u", F.TODAY, "B")
        assertEquals("ma note", l.commentaire)
        assertEquals(HistoryOrigin.UTILISATEUR, l.history.single().origin)
    }

    @Test fun commentaire_accepte_pour_non_identifie() {
        val l = LineRules.create(
            draft(client = F.NON_ID, commentaire = "Client mentionné : Dupond"), 1, "u", F.TODAY, "B",
        )
        assertEquals("Client mentionné : Dupond", l.commentaire)
    }

    @Test fun limites_120_et_500_refusees_sans_troncature() {
        expectError("sujet") { LineRules.create(draft(sujet = "a".repeat(121)), 1, "u", F.TODAY, "B") }
        expectError("action") { LineRules.create(draft(action = "a".repeat(121)), 1, "u", F.TODAY, "B") }
        val ok = LineRules.create(draft(sujet = "a".repeat(120)), 1, "u", F.TODAY, "B")
        assertEquals(120, ok.sujet.length)
    }

    @Test fun maj_agent_conserve_date_id_commentaire_et_empile_historique() {
        val existing = F.line(17, commentaire = "note manuelle")
        val u = LineRules.agentUpdate(existing, AgentPatch(statut = RidaStatus.TERMINE), "clôture", F.TODAY)
        assertEquals(existing.createdDate, u.createdDate)
        assertEquals(17, u.id)
        assertEquals("note manuelle", u.commentaire)
        assertEquals(F.TODAY, u.realisation)
        assertEquals(2, u.history.size)
        assertEquals("28/09/2026 : clôture", HistoryFormat.line(u.history.last()))
        assertEquals(existing.history.first(), u.history.first())
    }

    @Test fun maj_agent_sans_entree_historique_refusee() {
        expectError("historique") { LineRules.agentUpdate(F.line(1), AgentPatch(), " ", F.TODAY) }
    }

    @Test fun edition_utilisateur_action_sans_echeance_refusee() {
        val l = F.line(1, echeance = F.TODAY)
        expectError("echeance") {
            LineRules.userEdit(
                l, UserEdit("B", RidaType.ACTION, "s", "a", RidaStatus.A_FAIRE, null, null, "", ""), F.TODAY,
            )
        }
    }

    @Test fun masquage_conserve_les_donnees_et_remplace_le_sujet() {
        val l = F.line(37, echeance = F.TODAY, sujet = "Ancien sujet")
        val h = LineRules.hide(l, null, mergedInto = 42)
        assertTrue(h.hidden)
        assertEquals("Fusionnée dans #42", h.sujet)
        assertEquals("Ancien sujet", h.originalSujet)
        assertEquals(l.action, h.action)
        assertEquals(l.id, h.id)
        assertEquals(l.history, h.history)
        val s = LineRules.hide(l, "doublon", null)
        assertEquals("Supprimée à la demande : doublon", s.sujet)
    }

    @Test fun reclassement_garde_l_id() {
        val l = F.line(5, client = F.NON_ID)
        val r = LineRules.reclassify(l, F.CIM)
        assertEquals(5, r.id)
        assertEquals(F.CIM.id, r.clientId)
    }

    @Test fun shorten_coupe_sur_un_mot() {
        val s = LineRules.shorten("Supprimée à la demande : une raison vraiment très longue", 30)
        assertTrue(s.length <= 30)
        assertTrue(s.endsWith("…"))
        assertTrue(!s.contains("vraim…"))
    }

    private fun expectError(field: String, block: () -> Unit) {
        try {
            block()
            fail("erreur attendue sur $field")
        } catch (e: RidaValidationException) {
            assertTrue("champ $field attendu dans ${e.errors}", e.errors.any { it.field == field })
        }
    }
}
