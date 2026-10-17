package app.blico.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A resposta HTTP de um servidor MCP; [sessionId] vem do cabeçalho Mcp-Session-Id. */
data class McpHttpResponse(val status: Int, val body: String, val contentType: String = "application/json", val sessionId: String? = null)

/** Faz o POST. No app é HTTP de verdade; nos testes, um fake. */
fun interface McpTransport {
    suspend fun post(url: String, body: String, headers: Map<String, String>): McpHttpResponse
}

class McpUnauthorized : Exception("O login no serviço venceu ou foi recusado")

class McpError(message: String) : Exception(message)

/**
 * Cliente MCP mínimo pelo transporte Streamable HTTP: initialize, tools/list e tools/call.
 * É o que os agentes precisam; recursos, prompts e amostragem ficam de fora.
 */
class McpClient(
    private val url: String,
    private val transport: McpTransport,
    /** Acesso atual do login; nulo quando o servidor não pede login. */
    private val token: suspend () -> String? = { null },
) {
    data class RemoteTool(
        val name: String,
        val title: String?,
        val description: String,
        val schema: JsonObject,
        val readOnly: Boolean,
    )

    data class CallResult(val text: String, val isError: Boolean)

    private var session: String? = null
    private var nextId = 1L
    private var ready = false

    suspend fun listTools(): List<RemoteTool> {
        ensureReady()
        val out = mutableListOf<RemoteTool>()
        var cursor: String? = null
        // Um servidor que devolve cursores sem fim não prende o Blico.
        repeat(MAX_PAGES) {
            val params = buildJsonObject { cursor?.let { put("cursor", it) } }
            val result = request("tools/list", params)
            result["tools"]?.jsonArray?.forEach { out += parseTool(it.jsonObject) }
            cursor = result["nextCursor"]?.jsonPrimitive?.contentOrNull ?: return out
        }
        return out
    }

    suspend fun callTool(name: String, args: JsonObject): CallResult {
        ensureReady()
        val result = request("tools/call", buildJsonObject { put("name", name); put("arguments", args) })
        val text = result["content"]?.jsonArray.orEmpty().mapNotNull { part ->
            val o = part.jsonObject
            when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> o["text"]?.jsonPrimitive?.contentOrNull
                "resource_link" -> o["uri"]?.jsonPrimitive?.contentOrNull
                else -> null
            }
        }.joinToString("\n").ifBlank { result["structuredContent"]?.toString().orEmpty() }
        return CallResult(text, result["isError"]?.jsonPrimitive?.booleanOrNull == true)
    }

    private suspend fun ensureReady() {
        if (ready) return
        request(
            "initialize",
            buildJsonObject {
                put("protocolVersion", PROTOCOL)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") { put("name", "blico"); put("version", "1") }
            },
        )
        notify("notifications/initialized")
        ready = true
    }

    private suspend fun request(method: String, params: JsonObject): JsonObject {
        val id = nextId++
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val r = send(body.toString())
        val message = messages(r).firstOrNull { it["id"]?.jsonPrimitive?.longOrNull == id }
            ?: throw McpError("O serviço não respondeu a $method")
        message["error"]?.let { e ->
            throw McpError(e.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: "Erro do serviço em $method")
        }
        return message["result"]?.jsonObject ?: JsonObject(emptyMap())
    }

    private suspend fun notify(method: String) {
        send(buildJsonObject { put("jsonrpc", "2.0"); put("method", method) }.toString())
    }

    private suspend fun send(body: String): McpHttpResponse {
        val headers = buildMap {
            put("Content-Type", "application/json")
            put("Accept", "application/json, text/event-stream")
            put("MCP-Protocol-Version", PROTOCOL)
            session?.let { put("Mcp-Session-Id", it) }
            token()?.let { put("Authorization", "Bearer $it") }
        }
        val r = transport.post(url, body, headers)
        if (r.status == 401 || r.status == 403) throw McpUnauthorized()
        if (r.status == 404 && session != null) {
            // A sessão acabou do lado do servidor; a próxima chamada recomeça.
            session = null
            ready = false
            throw McpError("A sessão com o serviço acabou. Tente de novo.")
        }
        if (r.status !in 200..299) throw McpError("O serviço respondeu ${r.status}")
        r.sessionId?.let { session = it }
        return r
    }

    private fun parseTool(o: JsonObject): RemoteTool {
        val annotations = o["annotations"]?.jsonObject
        return RemoteTool(
            name = o["name"]!!.jsonPrimitive.content,
            title = o["title"]?.jsonPrimitive?.contentOrNull ?: annotations?.get("title")?.jsonPrimitive?.contentOrNull,
            description = o["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            schema = o["inputSchema"]?.jsonObject ?: JsonObject(emptyMap()),
            readOnly = annotations?.get("readOnlyHint")?.jsonPrimitive?.booleanOrNull == true,
        )
    }

    companion object {
        const val PROTOCOL = "2025-06-18"
        private const val MAX_PAGES = 10
        private val json = Json { ignoreUnknownKeys = true }

        /** A resposta pode vir em JSON ou como eventos SSE, um JSON por linha data:. */
        internal fun messages(r: McpHttpResponse): List<JsonObject> {
            if (r.body.isBlank()) return emptyList()
            val texts = if (r.contentType.startsWith("text/event-stream")) {
                r.body.lineSequence().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }.toList()
            } else {
                listOf(r.body)
            }
            return texts.flatMap { t ->
                when (val e = runCatching { json.parseToJsonElement(t) }.getOrNull()) {
                    is JsonObject -> listOf(e)
                    is JsonArray -> e.filterIsInstance<JsonObject>()
                    else -> emptyList()
                }
            }
        }
    }
}

/**
 * Uma ferramenta de um serviço conectado, vista pelos agentes como qualquer outra.
 *
 * Só é leitura (classe 0) o que o servidor marca como readOnlyHint; o resto, criar, enviar ou
 * apagar, é classe 2 e para no Confirmar. A descrição vem de fora e pode tentar dar ordens ao
 * modelo; por isso é cortada, e o resultado entra no laço como dado não confiável.
 */
class McpTool(
    val service: String,
    private val remote: McpClient.RemoteTool,
    private val client: McpClient,
) : Tool {
    override val name: String = nameFor(service, remote.name)

    override val description: String = "[$service] " + clean(remote.description).take(MAX_DESCRIPTION)

    private val types: Map<String, String> = remote.schema["properties"]?.jsonObject.orEmpty().mapValues { (_, v) ->
        v.jsonObject["type"]?.let { t -> if (t is JsonArray) t.firstOrNull()?.jsonPrimitive?.contentOrNull else t.jsonPrimitive.contentOrNull } ?: "string"
    }

    override val parameters: JsonSchema = JsonSchema(
        properties = remote.schema["properties"]?.jsonObject.orEmpty().mapValues { (k, v) ->
            JsonSchema.Param(
                type = types[k] ?: "string",
                description = clean(v.jsonObject["description"]?.jsonPrimitive?.contentOrNull ?: k).take(MAX_PARAM),
            )
        },
        required = remote.schema["required"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.toSet(),
    )

    override val risk: RiskClass = if (remote.readOnly) RiskClass.READ else RiskClass.COMMIT

    override val apps: Set<String> = emptySet()

    override fun summarize(call: ToolCall) = ConfirmationSummary(
        title = "$service: ${clean(remote.title ?: remote.name).take(MAX_PARAM)}",
        fields = parameters.properties.keys.mapNotNull { k -> call.args[k]?.let { k to it } },
    )

    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult = try {
        val r = client.callTool(remote.name, arguments(call.args))
        val text = r.text.take(MAX_RESULT).ifBlank { "Feito." }
        if (r.isError) ToolResult.Failure(text, "O $service recusou: ${text.take(120)}") else ToolResult.Success(text)
    } catch (e: McpUnauthorized) {
        ToolResult.Failure("login vencido: $service", "Preciso que você entre de novo no $service")
    } catch (e: McpError) {
        ToolResult.Failure(e.message ?: "erro", "O $service não respondeu")
    }

    /** Os argumentos chegam como texto; voltam ao tipo que o servidor pediu no schema. */
    internal fun arguments(args: Map<String, String>): JsonObject = JsonObject(
        args.mapValues { (k, v) -> typed(types[k] ?: "string", v) },
    )

    companion object {
        private const val MAX_DESCRIPTION = 300
        private const val MAX_PARAM = 120
        private const val MAX_RESULT = 4000
        private val json = Json

        /** Os nomes de ferramenta aceitam só letras, números e _ e no máximo 64 caracteres. */
        fun nameFor(service: String, tool: String): String =
            (AgentFile.normalize(service) + "_" + tool).replace(Regex("[^A-Za-z0-9_]"), "_").take(64)

        private fun clean(s: String) = s.replace(Regex("\\s+"), " ").replace("<<<", "").replace(">>>", "").trim()

        internal fun typed(type: String, v: String): JsonElement = when (type) {
            "integer" -> v.trim().toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(v)
            "number" -> v.trim().replace(',', '.').toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(v)
            "boolean" -> when (v.trim().lowercase()) {
                "true", "sim" -> JsonPrimitive(true)
                "false", "não", "nao" -> JsonPrimitive(false)
                else -> JsonPrimitive(v)
            }
            "array", "object" -> runCatching { json.parseToJsonElement(v) }.getOrElse {
                if (type == "array") JsonArray(v.split(',').map { JsonPrimitive(it.trim()) }) else JsonPrimitive(v)
            }
            "null" -> JsonNull
            else -> JsonPrimitive(v)
        }
    }
}
