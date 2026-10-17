package app.blico.core

import kotlinx.coroutines.CancellationException

/**
 * Um passo que o agente já deu: a chamada e o que ela devolveu.
 * [result] já vem embrulhado por [UntrustedContent], porque pode trazer dado lido do aparelho.
 */
data class AgentStep(val call: ToolCall, val result: String)

/**
 * Um agente configurado pelo dono do aparelho: o que ele faz e até onde pode ir.
 * [tools] vazio quer dizer todas as ferramentas oferecidas aos modelos.
 */
data class AgentProfile(
    val name: String,
    val instructions: String,
    val tools: Set<String> = emptySet(),
    val maxSteps: Int = DEFAULT_MAX_STEPS,
    val description: String = "",
    /** Frases que, ditas no pedido, chamam este agente. */
    val triggers: List<String> = emptyList(),
    /** Agentes a quem este pode pedir ajuda: a equipe dele. Eles não pedem a mais ninguém. */
    val team: List<String> = emptyList(),
) {
    init {
        require(maxSteps in 1..MAX_STEPS) { "max_passos precisa estar entre 1 e $MAX_STEPS" }
        require(name !in team) { "Um agente não pode estar na própria equipe" }
    }

    companion object {
        const val DEFAULT_MAX_STEPS = 5
        const val MAX_STEPS = 10
    }
}

/**
 * Um agente parado esperando um toque, no Confirmar ou numa escolha. Imutável: retomar não altera este.
 * Se quem parou foi um membro da equipe, [delegation] guarda o pedido feito a ele e onde ele parou.
 */
data class AgentRun(
    val text: String,
    val agent: AgentProfile,
    val steps: List<AgentStep> = emptyList(),
    /** 0 para o agente chamado pela pessoa, 1 para um membro da equipe dele. */
    val depth: Int = 0,
    val delegation: Delegation? = null,
)

/** O pedido que o agente fez a um membro da equipe ([call]) e o membro parado no cartão. */
data class Delegation(val call: ToolCall, val member: AgentRun)

/**
 * Onde o agente parou. [outcome] é o que o Blico mostra e fala. Se [paused] não é nulo, o cartão
 * espera um toque e o agente continua com [AgentRunner.resume]; recusar o cartão encerra o agente.
 */
data class AgentResult(val outcome: Outcome, val steps: List<AgentStep>, val paused: AgentRun? = null)

/** O que acontece numa conversa entre agentes, na ordem, para a tela mostrar como mensagens. */
sealed interface AgentEvent {
    /** Quem fala. */
    val agent: String

    /** [agent] pediu algo a [to], um membro da equipe. */
    data class Asked(override val agent: String, val to: String, val text: String) : AgentEvent

    /** [agent] usou uma ferramenta; [spoken] é o que ela devolveu. */
    data class Acted(override val agent: String, val call: ToolCall, val spoken: String) : AgentEvent

    /** [agent] parou esperando o seu toque. */
    data class Waiting(override val agent: String, val outcome: Outcome) : AgentEvent

    /** [agent] terminou e respondeu [text]: ao agente que pediu, ou a você. */
    data class Said(override val agent: String, val text: String, val failed: Boolean = false) : AgentEvent

    /** O cérebro que decidiu o passo: regras, local ou nuvem. */
    data class Routed(override val agent: String, val provider: String) : AgentEvent
}

/**
 * O laço de um agente: pede ao cérebro o próximo passo, executa pela mesma política de risco do
 * [Assistant] e devolve o resultado ao cérebro, até ele responder, falhar ou chegar ao limite de passos.
 *
 * Um passo de cada vez. Uma ação de classe 2 pausa o laço no cartão Confirmar. [providers] são só os
 * modelos (local, nuvem): as regras entenderiam o mesmo pedido de novo a cada passo.
 *
 * Equipe: um agente pode pedir ajuda aos agentes que lista em [AgentProfile.team]. Cada membro aparece
 * para ele como uma ferramenta `agente_<nome>`; o membro roda o próprio laço, com as próprias
 * ferramentas, e a resposta dele volta como resultado do passo. Membros não pedem a ninguém (um nível).
 * Se o membro para no Confirmar, a equipe inteira para ali.
 */
class AgentRunner(
    private val providers: List<LlmProvider>,
    private val assistant: Assistant,
    private val minConfidence: Double = 0.6,
    /** Todos os agentes configurados, para achar os membros de uma equipe. */
    private val directory: suspend () -> List<AgentProfile> = { emptyList() },
) {
    suspend fun start(text: String, agent: AgentProfile, ctx: ExecContext, onEvent: (AgentEvent) -> Unit = {}): AgentResult =
        next(AgentRun(text, agent), ctx, onEvent)

    /** Continua depois do toque: [call] é a chamada confirmada ou a opção escolhida. */
    suspend fun resume(run: AgentRun, call: ToolCall, ctx: ExecContext, confirmed: Boolean, onEvent: (AgentEvent) -> Unit = {}): AgentResult {
        val d = run.delegation
        if (d != null) {
            val sub = resume(d.member, call, ctx, confirmed, onEvent)
            return afterDelegation(run.copy(delegation = null), d.call, d.member.agent, sub, ctx, onEvent)
        }
        return after(run, call, act(run, call, ctx, confirmed), ctx, onEvent)
    }

    private suspend fun next(run: AgentRun, ctx: ExecContext, onEvent: (AgentEvent) -> Unit): AgentResult {
        if (run.steps.size >= run.agent.maxSteps) {
            return finish(run, Outcome.Answer("Parei depois de ${run.steps.size} passos. ${summary(run)}"), onEvent)
        }
        val members = members(run)
        val request = LlmRequest(run.text, tools(run.agent) + members.map(::DelegateTool), instructions = run.agent.instructions, history = run.steps)
        var failure: Throwable? = null
        for (provider in providers) {
            val decision = try {
                provider.chooseTools(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
                continue
            }
            if (decision.confidence < minConfidence) continue
            val call = decision.calls.firstOrNull()
            if (call != null) {
                onEvent(AgentEvent.Routed(run.agent.name, provider.id))
                // O mesmo passo duas vezes seguidas é o modelo andando em círculo.
                if (run.steps.lastOrNull()?.call == call) return finish(run, Outcome.Answer(summary(run)), onEvent)
                members.firstOrNull { DelegateTool.nameFor(it) == call.tool }?.let { member ->
                    return delegate(run, call, member, ctx, onEvent)
                }
                return after(run, call, act(run, call, ctx, confirmed = false), ctx, onEvent)
            }
            decision.answer?.let {
                onEvent(AgentEvent.Routed(run.agent.name, provider.id))
                return finish(run, Outcome.Answer(it), onEvent)
            }
        }
        val out = when {
            run.steps.isNotEmpty() -> Outcome.Answer(summary(run))
            failure is CloudUnavailable -> Outcome.Failed("Não consegui falar com a nuvem. ${failure.message}", failure.toString())
            failure != null -> Outcome.Failed("Não consegui entender agora, tente de novo", failure.toString())
            else -> Outcome.NotUnderstood("Ainda não sei fazer isso")
        }
        return finish(run, out, onEvent)
    }

    /** O membro roda o próprio laço com o pedido do agente; a resposta vira o resultado do passo. */
    private suspend fun delegate(run: AgentRun, call: ToolCall, member: AgentProfile, ctx: ExecContext, onEvent: (AgentEvent) -> Unit): AgentResult {
        val text = call.args["pedido"].orEmpty().ifBlank { run.text }
        onEvent(AgentEvent.Asked(run.agent.name, member.name, text))
        val sub = next(AgentRun(text, member, depth = run.depth + 1), ctx, onEvent)
        return afterDelegation(run, call, member, sub, ctx, onEvent)
    }

    private suspend fun afterDelegation(
        run: AgentRun,
        call: ToolCall,
        member: AgentProfile,
        sub: AgentResult,
        ctx: ExecContext,
        onEvent: (AgentEvent) -> Unit,
    ): AgentResult {
        sub.paused?.let { return AgentResult(sub.outcome, run.steps, paused = run.copy(delegation = Delegation(call, it))) }
        // A resposta do membro é dado para o agente, como qualquer resultado: ele decide o que fazer com ela.
        val said = if (sub.outcome is Outcome.Failed) "Não conseguiu: ${sub.outcome.spoken}" else sub.outcome.spoken
        return next(run.copy(steps = run.steps + AgentStep(call, UntrustedContent.wrap("agente:${member.name}", said))), ctx, onEvent)
    }

    /** Executa pela política do [Assistant], recusando o que está fora do agente. */
    private suspend fun act(run: AgentRun, call: ToolCall, ctx: ExecContext, confirmed: Boolean): Outcome {
        val allowed = run.agent.tools
        // Ferramentas que só nascem de outra (whatsapp_enviar depois de whatsapp_preparar) vêm pelo cartão e já foram liberadas.
        if (allowed.isNotEmpty() && call.tool !in allowed && !confirmed) {
            return Outcome.Failed("Não posso fazer isso", "fora do agente ${run.agent.name}: ${call.tool}")
        }
        return assistant.execute(call, ctx, confirmed)
    }

    private suspend fun after(run: AgentRun, call: ToolCall, out: Outcome, ctx: ExecContext, onEvent: (AgentEvent) -> Unit): AgentResult =
        when (out) {
            is Outcome.Done -> {
                onEvent(AgentEvent.Acted(run.agent.name, out.call, out.spoken))
                next(run.copy(steps = run.steps + step(out.call, out.spoken)), ctx, onEvent)
            }
            is Outcome.Confirm, is Outcome.Choose -> {
                onEvent(AgentEvent.Waiting(run.agent.name, out))
                AgentResult(out, run.steps, paused = run)
            }
            // Falha, lista de arquivos ou resposta encerram: a pessoa vê o resultado e decide.
            is Outcome.Failed, is Outcome.Files, is Outcome.Answer, is Outcome.NotUnderstood -> {
                val steps = if (out is Outcome.Failed) run.steps else run.steps + step(call, out.spoken)
                finish(run.copy(steps = steps), out, onEvent)
            }
        }

    private fun finish(run: AgentRun, out: Outcome, onEvent: (AgentEvent) -> Unit): AgentResult {
        onEvent(AgentEvent.Said(run.agent.name, out.spoken, failed = out is Outcome.Failed || out is Outcome.NotUnderstood))
        return AgentResult(out, run.steps)
    }

    private fun step(call: ToolCall, spoken: String) = AgentStep(call, UntrustedContent.wrap("ferramenta:${call.tool}", spoken))

    private fun tools(agent: AgentProfile): List<Tool> =
        assistant.modelTools().filter { agent.tools.isEmpty() || it.name in agent.tools }

    /** Membros da equipe que existem. Um membro não tem equipe: só o agente chamado pela pessoa delega. */
    private suspend fun members(run: AgentRun): List<AgentProfile> {
        if (run.depth > 0 || run.agent.team.isEmpty()) return emptyList()
        val all = directory().associateBy { it.name }
        return run.agent.team.mapNotNull { all[it] }.filter { it.name != run.agent.name }
    }

    /** O que foi feito, para falar quando o cérebro não fecha com uma resposta própria. */
    private fun summary(run: AgentRun): String =
        if (run.steps.isEmpty()) "Não fiz nada." else "Fiz ${run.steps.size} ${if (run.steps.size == 1) "passo" else "passos"}."
}

/** Um membro da equipe como ferramenta: o agente pede em texto e recebe a resposta do membro. */
internal class DelegateTool(member: AgentProfile) : Tool {
    override val name = nameFor(member)
    override val description = "Pede ao agente ${member.name}" + member.description.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty() +
        ". Ele faz e responde o que fez."
    override val parameters = JsonSchema(mapOf("pedido" to JsonSchema.Param(description = "o que pedir a ele, em uma frase")), setOf("pedido"))
    override val risk = RiskClass.READ
    override val apps = emptySet<String>()

    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult = error("A equipe é resolvida pelo AgentRunner")

    companion object {
        fun nameFor(member: AgentProfile) = "agente_" + member.name.replace('-', '_')
    }
}
