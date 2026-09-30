package rida.pour.les.pros.agent

import rida.pour.les.pros.domain.Aliases
import rida.pour.les.pros.domain.Client

/** Commandes reconnues directement par l'application, sans passer par le modèle. */
sealed interface Command {
    data object Aide : Command
    data object Recopie : Command
    data object Digest : Command

    /** [complex] : la demande contient d'autres mots que clients / mail / adresses → mieux traitée par l'agent. */
    data class SyntheseCmd(
        val clientCodes: List<String>,
        val byMail: Boolean,
        val recipients: List<String>,
        val complex: Boolean,
    ) : Command
}

object Commands {
    private val RECOPIE = Regex("recopie[^.]{0,20}commentaire", RegexOption.IGNORE_CASE)
    private val DIGEST = Regex("^(digest|[ée]ch[ée]ances?( du jour)?|quoi de neuf ( aujourd'hui)?)\\s*[?!.]*$", RegexOption.IGNORE_CASE)
    private val AIDE = Regex("^(aide|help|\\?|que sais-tu faire ?\\??)$", RegexOption.IGNORE_CASE)
    private val SYNTHESE = Regex("^synth[eèé]se\\b(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val EMAIL = Regex("[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+")
    private val MAIL_WORDS = setOf("MAIL", "EMAIL", "MAILS", "COURRIEL")
    private val STOP_WORDS = setOf(
        "PAR", "DE", "DU", "DES", "POUR", "TOUS", "TOUTES", "LES", "LE", "LA", "CLIENT", "CLIENTS", "ET", "A", "AU",
        "ENVOIE", "ENVOYER", "ENVOIELA", "MOI", "EN", "SUR", "RIDA", "COMPLETE", "GLOBALE", "TOUT",
    )

    fun parse(text: String, clients: List<Client>): Command? {
        val t = text.trim()
        if (AIDE.matches(t)) return Command.Aide
        if (RECOPIE.containsMatchIn(t)) return Command.Recopie
        if (DIGEST.matches(t)) return Command.Digest
        val m = SYNTHESE.find(t) ?: return null
        var rest = m.groupValues[1]
        val emails = EMAIL.findAll(rest).map { it.value.trimEnd('.') }.toList()
        emails.forEach { rest = rest.replace(it, " ") }
        val tokens = rest.split(Regex("[\\s,;:.!?]+")).filter { it.isNotBlank() }
        var byMail = emails.isNotEmpty()
        var complex = false
        val codes = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val key = rida.pour.les.pros.domain.Text.key(tokens[i])
            when {
                key in MAIL_WORDS -> byMail = true
                key in STOP_WORDS || key.isEmpty() -> Unit
                else -> {
                    // Essaie d'abord deux mots (ex. « Absys Cyborg »), puis un seul.
                    val two = if (i + 1 < tokens.size) Aliases.resolve(tokens[i] + " " + tokens[i + 1], clients) else null
                    val one = two ?: Aliases.resolve(tokens[i], clients)
                    if (one != null) {
                        codes += one.code
                        if (two != null) i++
                    } else {
                        complex = true
                    }
                }
            }
            i++
        }
        return Command.SyntheseCmd(codes.distinct(), byMail, emails, complex)
    }

    val HELP = """
Je suis ton assistant RIDA. Écris-moi (ou dicte) comme tu le ferais sur Telegram :
- « Chez DERET, Stéphanie doit envoyer le devis signé pour vendredi » → je crée l'action.
- « Clôture la #17 » / « Passe la #23 en cours, échéance au 15/10 » → je mets à jour.
- « Qu'est-ce qui est en retard chez CIM ? » → je réponds sans rien modifier.
- « Fusionne la #37 dans la #42 » / « Supprime la #51, doublon » → je masque sans rien effacer.

Commandes directes (fonctionnent aussi sans clé API) :
- synthèse [client…] [par mail] [adresse] → points en retard et échéances ≤ 7 jours, avec fichier Excel.
- échéances → échéances du jour et des 2 prochains jours.
- recopie commentaires → recopie tes commentaires dans l'historique (daté du jour).
- aide → ce message.
""".trim()
}
