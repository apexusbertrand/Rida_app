package rida.pour.les.pros.agent.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Appel direct de l'API Messages d'Anthropic (sans SDK), avec cache du prompt système et des outils. */
@Singleton
class AnthropicProvider(
    private val baseUrl: String,
    private val client: OkHttpClient,
) : LlmProvider {

    @Inject constructor() : this(
        DEFAULT_BASE_URL,
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(apiKey: String, request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        val body = encode(request).toString()
        val http = Request.Builder()
            .url("$baseUrl/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .header("content-type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val (code, text) = try {
            client.newCall(http).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: IOException) {
            throw LlmException("Pas de connexion au service Claude (${e.message ?: "réseau indisponible"}).", retryable = true)
        }
        if (code !in 200..299) throw httpError(code, text)
        decode(text)
    }

    fun encode(r: LlmRequest): JsonObject = buildJsonObject {
        put("model", r.model)
        put("max_tokens", r.maxTokens)
        putJsonArray("system") {
            addJsonObject {
                put("type", "text")
                put("text", r.system)
                putJsonObject("cache_control") { put("type", "ephemeral") }
            }
        }
        if (r.tools.isNotEmpty()) {
            putJsonArray("tools") {
                r.tools.forEachIndexed { i, t ->
                    addJsonObject {
                        put("name", t.name)
                        put("description", t.description)
                        put("input_schema", t.inputSchema)
                        if (i == r.tools.lastIndex) putJsonObject("cache_control") { put("type", "ephemeral") }
                    }
                }
            }
        }
        putJsonArray("messages") {
            r.messages.forEach { m ->
                addJsonObject {
                    put("role", if (m.role == LlmMessage.Role.USER) "user" else "assistant")
                    put("content", buildJsonArray { m.content.forEach { add(blockJson(it)) } })
                }
            }
        }
    }

    private fun blockJson(b: Block): JsonObject = when (b) {
        is Block.Text -> buildJsonObject { put("type", "text"); put("text", b.text) }
        is Block.ToolUse -> buildJsonObject { put("type", "tool_use"); put("id", b.id); put("name", b.name); put("input", b.input) }
        is Block.ToolResult -> buildJsonObject {
            put("type", "tool_result")
            put("tool_use_id", b.toolUseId)
            put("content", b.content)
            if (b.isError) put("is_error", true)
        }
    }

    fun decode(text: String): LlmResponse {
        val root = json.parseToJsonElement(text).jsonObject
        val content = (root["content"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el.jsonObject
            when (o["type"]?.jsonPrimitive?.content) {
                "text" -> Block.Text(o["text"]?.jsonPrimitive?.content.orEmpty())
                "tool_use" -> Block.ToolUse(
                    o["id"]!!.jsonPrimitive.content,
                    o["name"]!!.jsonPrimitive.content,
                    (o["input"] as? JsonObject) ?: JsonObject(emptyMap()),
                )
                else -> null
            }
        }
        val usage = root["usage"]?.jsonObject
        return LlmResponse(
            content = content,
            stopReason = root["stop_reason"]?.jsonPrimitive?.content,
            inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    private fun httpError(code: Int, body: String): LlmException {
        val apiMessage = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
        }.getOrNull().orEmpty()
        val detail = if (apiMessage.isNotBlank()) " ($apiMessage)" else ""
        return when (code) {
            401 -> LlmException("Clé API Claude refusée : vérifie-la dans les Paramètres.$detail")
            403 -> LlmException("Accès refusé par le service Claude.$detail")
            404 -> LlmException("Modèle introuvable : vérifie le nom du modèle dans les Paramètres.$detail")
            400 -> LlmException("Requête refusée par le service Claude.$detail")
            413 -> LlmException("Demande trop volumineuse.$detail")
            429 -> LlmException("Limite d'utilisation atteinte côté Claude, réessaie dans un instant.$detail", retryable = true)
            500, 502, 503, 504, 529 -> LlmException("Service Claude momentanément indisponible ($code), réessaie dans un instant.", retryable = true)
            else -> LlmException("Erreur du service Claude ($code).$detail")
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        const val API_VERSION = "2023-06-01"
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
