package app.blico.core.capability

import app.blico.core.ExecContext
import app.blico.core.Tool
import app.blico.core.ToolCall
import app.blico.core.ToolResult

/**
 * Uma funcionalidade do Blico empacotada: as ferramentas, as frases que as regras entendem e o que
 * ela precisa do aparelho. Cada área do catálogo (sistema, memória, agenda, notificações) vira uma,
 * em arquivo próprio, sem mexer nas outras. O que é do Android fica atrás de uma porta (interface):
 * a que depende do pedido (quem abre as telas) chega em [app.blico.core.ExecContext.ports]; a que não
 * depende pode vir no construtor, como a [app.blico.core.tools.GoogleAccount] das ferramentas do Google.
 */
interface Capability {
    /** Identificador estável, guardado nas preferências de quem desligou. */
    val id: String

    /** Nome na tela de Ajustes. */
    val title: String

    /** O que ela faz, em uma frase, para a pessoa decidir se liga. */
    val summary: String

    val tools: List<Tool>

    /** Frases entendidas sem modelo. Rodam antes das regras de base (ver [app.blico.core.RuleRouter]). */
    val rules: List<SpeechRule> get() = emptyList()

    /**
     * Pedaços de palavra (sem acento, minúsculas) que indicam que o pedido é com ela. Sem nenhum no pedido,
     * as ferramentas dela ficam fora do prompt dos modelos: o Gemma erra mais com dezenas de ferramentas.
     * Vazio: sempre oferecida.
     */
    val keywords: List<String> get() = emptyList()

    /**
     * Acessos que liberam partes dela, para a tela de Ajustes mostrar e pedir. Sem eles a funcionalidade
     * segue, e a ferramenta que precisar diz qual falta.
     */
    val permissions: List<Permission> get() = emptyList()

    /** Se ela funciona agora: falta permissão, o aparelho não tem, ou está pronta. */
    fun availability(): Availability = Availability.Ready
}

/** O estado de uma funcionalidade no aparelho, para a tela e para a fala explicarem a limitação. */
sealed interface Availability {
    data object Ready : Availability

    /** Funciona depois que a pessoa der [permission]. */
    data class NeedsPermission(val permission: Permission) : Availability

    /** O aparelho ou o Android não deixam. [why] é dito à pessoa. */
    data class Unsupported(val why: String) : Availability
}

/**
 * Permissões que as funcionalidades pedem, com o porquê em linguagem de gente. O app traduz cada uma
 * para o pedido do Android (diálogo ou tela de acesso especial).
 */
enum class Permission(val label: String, val why: String) {
    DO_NOT_DISTURB("Acesso ao Não perturbe", "para ligar e desligar o Não perturbe e o modo silencioso"),
    WRITE_SETTINGS("Modificar configurações do sistema", "para mudar o brilho da tela"),
    CALENDAR("Agenda", "para ver e criar compromissos na agenda do celular"),
    NOTIFICATIONS("Notificações", "para avisar dos lembretes na hora"),
    SMS("SMS", "para enviar SMS depois do seu toque em Confirmar"),
}

/**
 * O conjunto de funcionalidades do app. Junta as ferramentas e as regras de todas e respeita as que a
 * pessoa desligou ([disabled]): desligada, a ferramenta some dos modelos e, pedida pelas regras,
 * responde que está desligada em vez de cair em outra regra.
 */
class Capabilities(
    val all: List<Capability>,
    private val disabled: () -> Set<String> = { emptySet() },
) {
    init {
        val ids = all.map { it.id }
        require(ids.size == ids.toSet().size) { "Funcionalidade duplicada: $ids" }
    }

    fun isOn(id: String): Boolean = id !in disabled()

    fun tools(): List<Tool> = all.flatMap { c -> c.tools.map { GatedTool(it, c, this) } }

    fun rules(): List<SpeechRule> = all.flatMap { it.rules }

    /**
     * As ferramentas que valem a pena mostrar aos modelos para [text]: tira as das funcionalidades
     * cujas palavras-chave não aparecem. As de fora das funcionalidades ficam sempre.
     */
    fun toolsFor(text: String, tools: List<Tool>): List<Tool> {
        val t = " " + simplify(text) + " "
        val drop = all.filter { c -> c.keywords.isNotEmpty() && c.keywords.none { it in t } }
            .flatMap { c -> c.tools.map { it.name } }
            .toSet()
        return tools.filter { it.name !in drop }
    }

    /** Estado de cada uma, na ordem de [all], para a tela de Ajustes. */
    fun status(): List<Pair<Capability, Availability>> = all.map { it to it.availability() }
}

/**
 * Uma ferramenta dentro de uma funcionalidade. Antes de rodar, confere se a pessoa não desligou e se
 * o aparelho deixa; sem isso, fala o que falta em vez de falhar calada (degradação elegante).
 */
internal class GatedTool(
    private val inner: Tool,
    private val owner: Capability,
    private val set: Capabilities,
) : Tool by inner {
    override val exposedToModels: Boolean
        get() = inner.exposedToModels && set.isOn(owner.id) && owner.availability() !is Availability.Unsupported

    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
        if (!set.isOn(owner.id)) {
            return ToolResult.Failure("funcionalidade desligada: ${owner.id}", "${owner.title} está desligado nos Ajustes do Blico")
        }
        return when (val a = owner.availability()) {
            is Availability.Unsupported -> ToolResult.Failure("sem suporte: ${a.why}", a.why)
            // A permissão de uma ferramenta só vale para ela: as outras da mesma funcionalidade seguem.
            else -> inner.run(call, ctx)
        }
    }
}
