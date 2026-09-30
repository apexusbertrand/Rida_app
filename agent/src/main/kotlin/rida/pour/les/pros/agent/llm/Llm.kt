package rida.pour.les.pros.agent.llm

import kotlinx.serialization.json.JsonObject

/** Bloc de contenu d'un message (format proche de l'API Messages d'Anthropic). */
sealed interface Block {
    data class Text(val text: String) : Block
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : Block
    data class ToolResult(val toolUseId: String, val content: String, val isError: Boolean = false) : Block
}

data class LlmMessage(val role: Role, val content: List<Block>) {
    enum class Role { USER, ASSISTANT }

    companion object {
        fun user(text: String) = LlmMessage(Role.USER, listOf(Block.Text(text)))
        fun assistant(text: String) = LlmMessage(Role.ASSISTANT, listOf(Block.Text(text)))
    }
}

data class ToolSpec(val name: String, val description: String, val inputSchema: JsonObject)

data class LlmRequest(
    val model: String,
    val system: String,
    val tools: List<ToolSpec>,
    val messages: List<LlmMessage>,
    val maxTokens: Int = 4096,
)

data class LlmResponse(
    val content: List<Block>,
    val stopReason: String?,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
)

/** Erreur du fournisseur, avec un message lisible par l'utilisateur. */
class LlmException(message: String, val retryable: Boolean = false) : Exception(message)

/** Fournisseur de modèle : Anthropic aujourd'hui, modèle embarqué plus tard. */
interface LlmProvider {
    suspend fun complete(apiKey: String, request: LlmRequest): LlmResponse
}
