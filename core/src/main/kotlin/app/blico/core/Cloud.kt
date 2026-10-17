package app.blico.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Formato da conversa com o servidor do Blico (server/src/plan.ts). */
object CloudProtocol {
    @Serializable
    data class Param(val type: String = "string", val description: String)

    @Serializable
    data class Parameters(val properties: Map<String, Param>, val required: List<String> = emptyList())

    @Serializable
    data class ToolSpec(val name: String, val description: String, val parameters: Parameters)

    /** Um passo já dado: a chamada e o que ela devolveu (já embrulhado como dado não confiável). */
    @Serializable
    data class Step(val tool: String, val args: Map<String, String> = emptyMap(), val result: String)

    @Serializable
    data class Request(
        val text: String,
        val tools: List<ToolSpec>,
        val context: List<String> = emptyList(),
        val instructions: String? = null,
        val history: List<Step> = emptyList(),
    )

    @Serializable
    data class Call(val tool: String, val args: Map<String, String> = emptyMap())

    @Serializable
    data class Response(val calls: List<Call> = emptyList(), val answer: String? = null, val confidence: Double = 0.0, val model: String? = null)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(request: LlmRequest): String = json.encodeToString(
        Request.serializer(),
        Request(
            text = request.text,
            tools = request.tools.map { t ->
                ToolSpec(
                    name = t.name,
                    description = t.description,
                    parameters = Parameters(
                        properties = t.parameters.properties.mapValues { Param(it.value.type, it.value.description) },
                        required = t.parameters.required.sorted(),
                    ),
                )
            },
            context = request.context,
            instructions = request.instructions,
            history = request.history.map { Step(it.call.tool, it.call.args, it.result) },
        ),
    )

    fun decode(body: String): LlmDecision {
        val r = json.decodeFromString(Response.serializer(), body)
        return LlmDecision(r.calls.map { ToolCall(it.tool, it.args) }, r.confidence, r.answer?.takeIf { it.isNotBlank() })
    }
}

/** Erro de rede ou do servidor. A mensagem é curta e pode ser falada. */
class CloudUnavailable(message: String) : Exception(message)

/**
 * Cérebro na nuvem: manda o pedido (já mascarado) e o catálogo de ferramentas para o servidor próprio.
 * [transport] faz o POST e devolve o corpo da resposta; no app é HTTP, nos testes é um fake.
 */
class CloudProvider(private val transport: suspend (body: String) -> String) : LlmProvider {
    override val id = "nuvem"

    override suspend fun chooseTools(request: LlmRequest): LlmDecision =
        CloudProtocol.decode(transport(CloudProtocol.encode(request)))
}

/**
 * Embrulha um provedor que sai do aparelho: mascara nomes, telefones, e-mails e endereços
 * no pedido e desfaz a troca nos argumentos e na resposta que voltam.
 */
class RedactingProvider(
    private val inner: LlmProvider,
    private val knownEntities: suspend () -> Map<String, String>,
) : LlmProvider {
    override val id = inner.id

    override suspend fun chooseTools(request: LlmRequest): LlmDecision {
        val redactor = Redactor(knownEntities())
        // Tudo numa passada só, para o mesmo dado ter o mesmo apelido no pedido, no contexto e nos passos.
        val history = request.history
        val pieces = buildList {
            add(request.text)
            addAll(request.context)
            add(request.instructions.orEmpty())
            history.forEach { step ->
                add(step.result)
                step.call.args.toSortedMap().values.forEach(::add)
            }
        }
        val all = redactor.redactAll(pieces)
        val masked = all.texts.iterator()
        val text = masked.next()
        val context = request.context.map { masked.next() }
        val instructions = masked.next().takeIf { request.instructions != null }
        val maskedHistory = history.map { step ->
            val result = masked.next()
            val args = step.call.args.toSortedMap().mapValues { masked.next() }
            AgentStep(step.call.copy(args = args), result)
        }
        val mapping = all.mapping
        val decision = inner.chooseTools(
            request.copy(text = text, context = context, instructions = instructions, history = maskedHistory),
        )
        return decision.copy(
            calls = decision.calls.map { c -> c.copy(args = c.args.mapValues { redactor.restore(it.value, mapping) }) },
            answer = decision.answer?.let { redactor.restore(it, mapping) },
        )
    }

    companion object {
        /** Apelidos para nomes da agenda (nome completo e primeiro nome) e endereços cadastrados. */
        fun entitiesFrom(contactNames: List<String>, places: Map<String, String>): Map<String, String> {
            val out = linkedMapOf<String, String>()
            places.forEach { (alias, address) -> if (address.isNotBlank()) out[address] = "endereco_$alias" }
            var n = 0
            contactNames.flatMap { listOf(it, it.substringBefore(' ')) }
                .filter { it.length >= 3 }
                .distinctBy { it.lowercase() }
                .forEach { name -> out[name] = "contato_${++n}" }
            return out
        }
    }
}
