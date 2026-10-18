package app.blico.core.capability

import app.blico.core.ConfirmationSummary
import app.blico.core.ExecContext
import app.blico.core.LlmDecision
import app.blico.core.LlmProvider
import app.blico.core.LlmRequest
import app.blico.core.Redactor
import app.blico.core.RiskClass
import app.blico.core.Tool
import app.blico.core.ToolCall
import app.blico.core.ToolResult
import app.blico.core.tools.BaseTool
import app.blico.core.tools.arg
import java.time.Instant

/** Um fato que a pessoa pediu para o Blico lembrar e confirmou no cartão. */
data class Fact(
    val id: String,
    val text: String,
    val at: Long,
    /** Documento, telefone, saúde, senha: guardado, mas marcado, e nunca sai do aparelho. */
    val sensitive: Boolean = false,
)

/**
 * Onde os fatos ficam. No app é um arquivo cifrado com chave do Keystore; nos testes, uma lista.
 * Nada daqui vai para a nuvem: só o modelo do celular lê (ver [MemoryAwareProvider]).
 */
interface MemoryStore {
    suspend fun all(): List<Fact>

    suspend fun add(text: String, sensitive: Boolean): Fact

    suspend fun remove(id: String): Boolean
}

/** Regras de memória que não dependem do Android: busca por palavras e o que conta como sensível. */
object MemoryText {
    private val STOP = setOf(
        "a", "o", "as", "os", "de", "do", "da", "dos", "das", "e", "é", "que", "um", "uma", "em", "no", "na",
        "nos", "nas", "pra", "para", "por", "com", "meu", "minha", "meus", "minhas", "eu", "me", "se", "sobre",
        "você", "voce", "sabe", "lembra", "isso", "ele", "ela", "qual", "quem", "onde", "quando", "como",
    )

    private val SENSITIVE = Regex(
        "\\b(senha|pin|token|código de acesso|codigo de acesso|cpf|rg|cnpj|cartão|cartao|conta bancária|conta bancaria|" +
            "agência|agencia|pix|diagnóstico|diagnostico|doença|doenca|remédio|remedio|medicamento|exame|hiv|" +
            "gravidez|grávida|gravida|religião|religiao|partido|orientação sexual|orientacao sexual|salário|salario)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun words(text: String): Set<String> =
        simplify(text).split(Regex("[^\\p{L}\\d]+")).filter { it.length >= 2 && it !in STOP }.toSet()

    /** Fatos que dividem alguma palavra com [query], os que mais dividem primeiro. */
    fun search(facts: List<Fact>, query: String, limit: Int = 5): List<Fact> {
        val q = words(query).map(::simplify).toSet()
        if (q.isEmpty()) return emptyList()
        return facts.map { it to words(it.text).count { w -> w in q } }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Fact, Int>> { it.second }.thenByDescending { it.first.at })
            .take(limit)
            .map { it.first }
    }

    /** Documento, telefone, e-mail (pelo [Redactor]) ou um assunto delicado (saúde, senha, dinheiro, crença). */
    fun isSensitive(text: String): Boolean = SENSITIVE.containsMatchIn(text) || Redactor().redact(text).mapping.isNotEmpty()

    /** "que a Ana é minha irmã" vira "A Ana é minha irmã". */
    fun clean(text: String): String {
        val t = text.trim().trimEnd('.', '!').removePrefix("que ").removePrefix("Que ").trim()
        return t.replaceFirstChar { it.uppercase() }
    }
}

/** Memória pessoal: lembrar, consultar e esquecer por voz. Guardar e esquecer passam pelo Confirmar. */
class MemoriaCapability(private val store: MemoryStore) : Capability {
    override val id = "memoria"
    override val title = "Memória"
    override val summary = "Lembra o que você pedir (pessoas, preferências, combinados), só no celular e cifrado"

    override val keywords = listOf("lembr", "memoria", "esquec", "guard", "anot", "sabe", "salv", "grav")

    override val tools: List<Tool> = listOf(MemoriaGuardar(store), MemoriaConsultar(store), MemoriaEsquecer(store), MemoriaApagar(store))

    override val rules: List<SpeechRule> = listOf(
        // "lembra que a Ana é minha irmã", "guarda que eu prefiro corredor no avião", "anota aí que a senha do wi-fi é X"
        SpeechRule("^(?:lembra|lembre|lembrar|grava|grave|guarda|guarde|memoriza|memorize|anota|anote|salva|salve)(?: (?:aí|ai|na memória|na memoria))?{F}(?: (?:isso|isto))?:? que (?<fato>.+)$") { m ->
            ToolCall("memoria_guardar", mapOf("fato" to MemoryText.clean(m.raw("fato")!!)))
        },
        SpeechRule("^(?:o que|que) (?:você|voce) (?:sabe|lembra)(?: de mim)?$") { ToolCall("memoria_consultar", mapOf("assunto" to "tudo")) },
        // "o que você sabe sobre a Ana", "o que você lembra de mim"
        SpeechRule("^(?:o que|que) (?:você|voce) (?:sabe|lembra|guardou|tem anotado)(?: (?:sobre|de|do|da|dos|das))? (?<assunto>.+)$") { m ->
            ToolCall("memoria_consultar", mapOf("assunto" to m["assunto"]!!))
        },
        SpeechRule("^(?:mostra|mostre|lista|liste|quais são|quais sao)(?: (?:a|as|os))? (?:minhas |suas )?(?:memória|memoria|memórias|memorias|lembranças|lembrancas|coisas que você lembra)$") {
            ToolCall("memoria_consultar", mapOf("assunto" to "tudo"))
        },
        // "esquece que eu moro em Pinheiros", "esquece o que você sabe sobre a Ana", "apaga da memória o endereço antigo"
        SpeechRule("^(?:esquece|esqueça|esqueca|esquecer|(?:apaga|apague|tira|tire|remove) da (?:memória|memoria)){F}(?: que| o que (?:você|voce) sabe (?:sobre|de|do|da)| (?:sobre|de|do|da))? (?<assunto>.+)$") { m ->
            ToolCall("memoria_esquecer", mapOf("assunto" to m["assunto"]!!))
        },
    )
}

/**
 * Guarda um fato. Classe 2: o cartão mostra o texto exato antes de guardar, para nada entrar na memória
 * sem a pessoa ver (nem um fato plantado por um e-mail que um agente leu).
 */
internal class MemoriaGuardar(private val store: MemoryStore) : BaseTool(
    name = "memoria_guardar",
    description = "Guarda na memória do Blico um fato que a pessoa pediu para lembrar (quem é quem, preferências, combinados)",
    risk = RiskClass.COMMIT,
    apps = emptySet(),
    params = mapOf("fato" to "o fato, em uma frase, em terceira pessoa ou como a pessoa disse"),
    required = setOf("fato"),
) {
    override fun summarize(call: ToolCall): ConfirmationSummary {
        val fato = call.arg("fato").orEmpty()
        val fields = listOf("Fato" to fato) +
            if (MemoryText.isSensitive(fato)) listOf("Atenção" to "Parece dado sensível. Fica só no celular, cifrado.") else emptyList()
        return ConfirmationSummary("Guardar na memória", fields)
    }

    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
        val fato = call.arg("fato")!!
        store.add(fato, MemoryText.isSensitive(fato))
        return ToolResult.Success("Guardei.")
    }
}

internal class MemoriaConsultar(private val store: MemoryStore) : BaseTool(
    name = "memoria_consultar",
    description = "Procura na memória do Blico o que a pessoa já pediu para lembrar sobre um assunto ou pessoa",
    risk = RiskClass.READ,
    apps = emptySet(),
    params = mapOf("assunto" to "pessoa ou assunto, ou tudo"),
    required = setOf("assunto"),
    title = "Memória",
) {
    // O que sai daqui é dado pessoal: depois disso, uma ferramenta de saída para no Confirmar.
    override val readsPrivate = true

    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
        val assunto = call.arg("assunto")!!
        val all = store.all()
        val found = if (simplify(assunto) in setOf("tudo", "todas", "todos", "mim", "eu")) {
            all.sortedByDescending { it.at }.take(10)
        } else {
            MemoryText.search(all, assunto)
        }
        if (found.isEmpty()) {
            val spoken = if (all.isEmpty()) "Ainda não guardei nada. Diga \"lembra que…\"." else "Não tenho nada guardado sobre $assunto."
            return ToolResult.Answer(spoken)
        }
        return ToolResult.Answer(found.joinToString(" ") { it.text.trimEnd('.') + "." })
    }
}

/**
 * Acha o fato a esquecer. Um só: o próximo passo é apagar, que passa pelo Confirmar com o texto exato.
 * Vários: a pessoa escolhe qual, e a escolha vai ao mesmo cartão.
 */
internal class MemoriaEsquecer(private val store: MemoryStore) : BaseTool(
    name = "memoria_esquecer",
    description = "Apaga da memória do Blico um fato que a pessoa pediu para esquecer",
    risk = RiskClass.PREPARE,
    apps = emptySet(),
    params = mapOf("assunto" to "o que esquecer"),
    required = setOf("assunto"),
    title = "Esquecer",
) {
    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
        val assunto = call.arg("assunto")!!
        val found = MemoryText.search(store.all(), assunto)
        fun apagar(f: Fact) = ToolCall(MemoriaApagar.NAME, mapOf("id" to f.id, "fato" to f.text))
        return when (found.size) {
            0 -> ToolResult.Failure("nada sobre $assunto", "Não tenho nada guardado sobre $assunto")
            1 -> ToolResult.Then(apagar(found[0]))
            else -> ToolResult.NeedsChoice("Qual delas?", found.map { ToolResult.Choice(it.text, apagar(it)) })
        }
    }
}

/** Apaga um fato pelo id. Classe 2, só nasce de [MemoriaEsquecer] ou da tela de memória. */
internal class MemoriaApagar(private val store: MemoryStore) : BaseTool(
    name = NAME,
    description = "Apaga um fato da memória pelo id",
    risk = RiskClass.COMMIT,
    apps = emptySet(),
    params = mapOf("id" to "id do fato", "fato" to "texto do fato"),
    required = setOf("id", "fato"),
) {
    override val exposedToModels = false

    override fun summarize(call: ToolCall) = ConfirmationSummary("Esquecer", listOf("Fato" to call.arg("fato").orEmpty()))

    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult =
        if (store.remove(call.arg("id")!!)) ToolResult.Success("Esqueci.") else ToolResult.Failure("fato não existe", "Já não estava na memória")

    companion object {
        const val NAME = "memoria_apagar"
    }
}

/**
 * Leva ao modelo do celular os fatos que têm a ver com o pedido, para "manda pra minha irmã" achar a Ana.
 * Só envolve o modelo local; a nuvem nunca recebe a memória.
 */
class MemoryAwareProvider(
    private val inner: LlmProvider,
    private val store: MemoryStore,
    private val enabled: () -> Boolean = { true },
) : LlmProvider {
    override val id = inner.id

    override suspend fun chooseTools(request: LlmRequest): LlmDecision {
        if (!enabled()) return inner.chooseTools(request)
        val facts = MemoryText.search(store.all(), request.text, limit = 3)
        if (facts.isEmpty()) return inner.chooseTools(request)
        val note = "Fatos que o dono do celular pediu para lembrar: " + facts.joinToString(" ") { it.text.trimEnd('.') + "." }
        return inner.chooseTools(request.copy(context = listOf(note) + request.context))
    }
}

/** Memória numa lista, para testes e para o app antes de abrir o arquivo. */
class InMemoryStore(initial: List<Fact> = emptyList()) : MemoryStore {
    private val facts = initial.toMutableList()
    private var next = initial.size

    override suspend fun all(): List<Fact> = facts.toList()

    override suspend fun add(text: String, sensitive: Boolean): Fact =
        Fact("f${++next}", text, Instant.now().toEpochMilli(), sensitive).also { facts += it }

    override suspend fun remove(id: String): Boolean = facts.removeIf { it.id == id }
}
