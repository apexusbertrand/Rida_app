package rida.pour.les.pros.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import rida.pour.les.pros.agent.llm.AnthropicProvider
import rida.pour.les.pros.agent.llm.Block
import rida.pour.les.pros.agent.llm.LlmException
import rida.pour.les.pros.agent.llm.LlmMessage
import rida.pour.les.pros.agent.llm.LlmRequest
import rida.pour.les.pros.agent.llm.ToolSpec

class AnthropicProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: AnthropicProvider

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        provider = AnthropicProvider(server.url("").toString().trimEnd('/'), OkHttpClient())
    }

    @After fun tearDown() = server.shutdown()

    private val request = LlmRequest(
        model = "claude-sonnet-5",
        system = "système",
        tools = listOf(ToolSpec("repondre", "fin", JsonObject(mapOf("type" to Json.parseToJsonElement("\"object\""))))),
        messages = listOf(LlmMessage.user("bonjour")),
    )

    @Test fun envoie_les_en_tetes_et_lit_un_appel_d_outil() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"m","type":"message","role":"assistant","stop_reason":"tool_use",
                "content":[{"type":"text","text":"Je note."},{"type":"tool_use","id":"tu_1","name":"repondre","input":{"resume":"ok"}}],
                "usage":{"input_tokens":120,"output_tokens":30}}""",
            ),
        )
        val r = provider.complete("sk-test", request)
        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.path)
        assertEquals("sk-test", recorded.getHeader("x-api-key"))
        assertEquals(AnthropicProvider.API_VERSION, recorded.getHeader("anthropic-version"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("claude-sonnet-5", body["model"]!!.jsonPrimitive.content)
        assertEquals("ephemeral", body["system"]!!.jsonArray[0].jsonObject["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("user", body["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)

        assertEquals("tool_use", r.stopReason)
        assertEquals(120, r.inputTokens)
        val use = r.content.filterIsInstance<Block.ToolUse>().single()
        assertEquals("repondre", use.name)
        assertEquals("ok", use.input["resume"]!!.jsonPrimitive.content)
    }

    @Test fun erreur_401_lisible() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        try {
            provider.complete("mauvaise", request)
            fail("exception attendue")
        } catch (e: LlmException) {
            assertTrue(e.message!!, e.message!!.contains("Clé API Claude refusée"))
        }
    }
}
