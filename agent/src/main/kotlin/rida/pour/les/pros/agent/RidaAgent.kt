package rida.pour.les.pros.agent

import rida.pour.les.pros.agent.llm.Block
import rida.pour.les.pros.agent.llm.LlmMessage
import rida.pour.les.pros.agent.llm.LlmProvider
import rida.pour.les.pros.agent.llm.LlmRequest

data class AgentResult(
    val answer: FinalAnswer,
    val synthese: SyntheseOutput?,
    val touchedIds: Set<Long>,
    val iterations: Int,
    val inputTokens: Int,
    val outputTokens: Int,
)

/**
 * Boucle agent : appels du modèle et exécution des outils jusqu'à l'appel de `repondre`.
 * Garde-fous : 10 itérations maximum ; arrêt après une erreur identique répétée sur un même outil.
 */
class RidaAgent(
    private val llm: LlmProvider,
    private val tools: RidaTools,
    private val maxIterations: Int = 10,
) {
    suspend fun run(apiKey: String, model: String, history: List<LlmMessage>, userMessage: String): AgentResult {
        val state = RunState()
        val messages = history.toMutableList()
        messages += LlmMessage.user(userMessage)
        var lastError: String? = null
        var repeatedErrors = 0
        var tokensIn = 0
        var tokensOut = 0

        for (iteration in 1..maxIterations) {
            val response = llm.complete(apiKey, LlmRequest(model, AgentPrompt.SYSTEM, tools.specs, messages))
            tokensIn += response.inputTokens
            tokensOut += response.outputTokens
            messages += LlmMessage(LlmMessage.Role.ASSISTANT, response.content.ifEmpty { listOf(Block.Text("…")) })

            val uses = response.content.filterIsInstance<Block.ToolUse>()
            if (uses.isEmpty()) {
                // Le modèle a répondu en texte libre sans appeler repondre : on l'utilise comme résumé.
                val text = response.content.filterIsInstance<Block.Text>().joinToString("\n") { it.text }.trim()
                val answer = state.final ?: FinalAnswer(text.ifEmpty { "(réponse vide)" }, "", false, emptyList())
                return AgentResult(answer, state.synthese, state.touchedIds, iteration, tokensIn, tokensOut)
            }

            val results = mutableListOf<Block>()
            // Les outils d'action d'abord, repondre en dernier.
            for (use in uses.sortedBy { if (it.name == "repondre") 1 else 0 }) {
                val result = try {
                    Block.ToolResult(use.id, tools.execute(use.name, use.input, state))
                } catch (e: ToolError) {
                    val signature = use.name + "|" + e.message
                    repeatedErrors = if (signature == lastError) repeatedErrors + 1 else 1
                    lastError = signature
                    if (repeatedErrors >= 3) {
                        return AgentResult(
                            FinalAnswer(
                                "Je n'ai pas pu aller au bout : l'outil ${use.name} échoue de façon répétée.",
                                "Erreur : ${e.message}",
                                false, emptyList(),
                            ),
                            state.synthese, state.touchedIds, iteration, tokensIn, tokensOut,
                        )
                    }
                    val hint = if (repeatedErrors == 2) "\nDeuxième échec identique : n'essaie plus, explique le blocage via repondre." else ""
                    Block.ToolResult(use.id, "ERREUR : ${e.message}$hint", isError = true)
                }
                results += result
            }
            state.final?.let { return AgentResult(it, state.synthese, state.touchedIds, iteration, tokensIn, tokensOut) }
            messages += LlmMessage(LlmMessage.Role.USER, results)
        }
        return AgentResult(
            FinalAnswer(
                "Je n'ai pas terminé dans la limite de $maxIterations étapes. Reformule ou découpe ta demande.",
                if (state.touchedIds.isNotEmpty()) "Lignes déjà modifiées : ${state.touchedIds.joinToString(", ") { "#$it" }}" else "",
                false, emptyList(),
            ),
            state.synthese, state.touchedIds, maxIterations, tokensIn, tokensOut,
        )
    }
}
