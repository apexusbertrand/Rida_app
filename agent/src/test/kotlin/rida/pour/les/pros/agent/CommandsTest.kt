package rida.pour.les.pros.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rida.pour.les.pros.domain.Client

class CommandsTest {
    private val clients = listOf(
        Client("1", "DERET"),
        Client("2", "CIM"),
        Client("3", "Absys-Cyborg", aliases = listOf("Absys Cyborg")),
    )

    @Test fun commandes_simples() {
        assertEquals(Command.Aide, Commands.parse("aide", clients))
        assertEquals(Command.Recopie, Commands.parse("Recopie les commentaires stp", clients))
        assertEquals(Command.Digest, Commands.parse("échéances du jour", clients))
        assertNull(Commands.parse("Chez DERET, Stéphanie envoie le devis vendredi", clients))
    }

    @Test fun synthese_tous_clients() {
        assertEquals(Command.SyntheseCmd(emptyList(), false, emptyList(), false), Commands.parse("Synthèse", clients))
    }

    @Test fun synthese_client_mail_et_adresse() {
        val c = Commands.parse("synthèse DERET et Absys Cyborg par mail à jean@exemple.fr", clients) as Command.SyntheseCmd
        assertEquals(listOf("DERET", "Absys-Cyborg"), c.clientCodes)
        assertTrue(c.byMail)
        assertEquals(listOf("jean@exemple.fr"), c.recipients)
        assertTrue(!c.complex)
    }

    @Test fun synthese_complexe_pour_l_agent() {
        val c = Commands.parse("synthèse des actions de Stéphanie en retard", clients) as Command.SyntheseCmd
        assertTrue(c.complex)
    }
}
