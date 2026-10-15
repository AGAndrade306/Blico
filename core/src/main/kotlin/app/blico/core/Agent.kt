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
) {
    init {
        require(maxSteps in 1..MAX_STEPS) { "max_passos precisa estar entre 1 e $MAX_STEPS" }
    }

    companion object {
        const val DEFAULT_MAX_STEPS = 5
        const val MAX_STEPS = 10
    }
}

/** Um agente parado esperando um toque, no Confirmar ou numa escolha. Imutável: retomar não altera este. */
data class AgentRun(val text: String, val agent: AgentProfile, val steps: List<AgentStep> = emptyList())

/**
 * Onde o agente parou. [outcome] é o que o Blico mostra e fala. Se [paused] não é nulo, o cartão
 * espera um toque e o agente continua com [AgentRunner.resume]; recusar o cartão encerra o agente.
 */
data class AgentResult(val outcome: Outcome, val steps: List<AgentStep>, val paused: AgentRun? = null)

/**
 * O laço de um agente: pede ao cérebro o próximo passo, executa pela mesma política de risco do
 * [Assistant] e devolve o resultado ao cérebro, até ele responder, falhar ou chegar ao limite de passos.
 *
 * Um passo de cada vez. Uma ação de classe 2 pausa o laço no cartão Confirmar. [providers] são só os
 * modelos (local, nuvem): as regras entenderiam o mesmo pedido de novo a cada passo.
 */
class AgentRunner(
    private val providers: List<LlmProvider>,
    private val assistant: Assistant,
    private val minConfidence: Double = 0.6,
) {
    suspend fun start(text: String, agent: AgentProfile, ctx: ExecContext, onRoute: (String) -> Unit = {}): AgentResult =
        next(AgentRun(text, agent), ctx, onRoute)

    /** Continua depois do toque: [call] é a chamada confirmada ou a opção escolhida. */
    suspend fun resume(run: AgentRun, call: ToolCall, ctx: ExecContext, confirmed: Boolean, onRoute: (String) -> Unit = {}): AgentResult =
        after(run, call, act(run, call, ctx, confirmed), ctx, onRoute)

    private suspend fun next(run: AgentRun, ctx: ExecContext, onRoute: (String) -> Unit): AgentResult {
        if (run.steps.size >= run.agent.maxSteps) {
            return AgentResult(Outcome.Answer("Parei depois de ${run.steps.size} passos. ${summary(run)}"), run.steps)
        }
        val request = LlmRequest(run.text, tools(run.agent), instructions = run.agent.instructions, history = run.steps)
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
                onRoute(provider.id)
                // O mesmo passo duas vezes seguidas é o modelo andando em círculo.
                if (run.steps.lastOrNull()?.call == call) return AgentResult(Outcome.Answer(summary(run)), run.steps)
                return after(run, call, act(run, call, ctx, confirmed = false), ctx, onRoute)
            }
            decision.answer?.let { onRoute(provider.id); return AgentResult(Outcome.Answer(it), run.steps) }
        }
        val out = when {
            run.steps.isNotEmpty() -> Outcome.Answer(summary(run))
            failure is CloudUnavailable -> Outcome.Failed("Não consegui falar com a nuvem. ${failure.message}", failure.toString())
            failure != null -> Outcome.Failed("Não consegui entender agora, tente de novo", failure.toString())
            else -> Outcome.NotUnderstood("Ainda não sei fazer isso")
        }
        return AgentResult(out, run.steps)
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

    private suspend fun after(run: AgentRun, call: ToolCall, out: Outcome, ctx: ExecContext, onRoute: (String) -> Unit): AgentResult =
        when (out) {
            is Outcome.Done -> next(run.copy(steps = run.steps + step(out.call, out.spoken)), ctx, onRoute)
            is Outcome.Confirm, is Outcome.Choose -> AgentResult(out, run.steps, paused = run)
            // Falha, lista de arquivos ou resposta encerram: a pessoa vê o resultado e decide.
            is Outcome.Failed, is Outcome.Files, is Outcome.Answer, is Outcome.NotUnderstood ->
                AgentResult(out, if (out is Outcome.Failed) run.steps else run.steps + step(call, out.spoken))
        }

    private fun step(call: ToolCall, spoken: String) = AgentStep(call, UntrustedContent.wrap("ferramenta:${call.tool}", spoken))

    private fun tools(agent: AgentProfile): List<Tool> =
        assistant.modelTools().filter { agent.tools.isEmpty() || it.name in agent.tools }

    /** O que foi feito, para falar quando o cérebro não fecha com uma resposta própria. */
    private fun summary(run: AgentRun): String =
        if (run.steps.isEmpty()) "Não fiz nada." else "Fiz ${run.steps.size} ${if (run.steps.size == 1) "passo" else "passos"}."
}
