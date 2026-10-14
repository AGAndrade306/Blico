package app.blico.core

/** O que o Blico mostra e fala depois de um pedido. */
sealed interface Outcome {
    val spoken: String

    data class Done(override val spoken: String, val call: ToolCall) : Outcome

    data class Choose(override val spoken: String, val options: List<ToolResult.Choice>) : Outcome

    /** Classe 2: o cartão mostra [summary] e só executa [call] depois do toque em Confirmar. */
    data class Confirm(override val spoken: String, val summary: ConfirmationSummary, val call: ToolCall) : Outcome

    data class Failed(override val spoken: String, val reason: String) : Outcome

    data class NotUnderstood(override val spoken: String) : Outcome
}

/**
 * O caminho de um pedido: provedores em ordem (regras, depois modelos), política de risco, execução.
 * Sem Android, para ser testado de ponta a ponta.
 */
class Assistant(
    private val providers: List<LlmProvider>,
    private val registry: ToolRegistry,
    private val policy: RiskPolicy = RiskPolicy(registry),
    private val minConfidence: Double = 0.6,
) {
    suspend fun handle(text: String, ctx: ExecContext): Outcome {
        val request = LlmRequest(text, registry.all.toList())
        val call = providers.firstNotNullOfOrNull { provider ->
            val decision = runCatching { provider.chooseTools(request) }.getOrNull()
            decision?.takeIf { it.confidence >= minConfidence }?.calls?.firstOrNull()
        } ?: return Outcome.NotUnderstood("Ainda não sei fazer isso")
        return execute(call, ctx)
    }

    /** Executa uma chamada já decidida: depois de uma escolha ou do toque em Confirmar ([confirmed]). */
    suspend fun execute(call: ToolCall, ctx: ExecContext, confirmed: Boolean = false): Outcome =
        when (val decision = policy.decide(call)) {
            is Decision.Refuse -> Outcome.Failed("Não posso fazer isso", decision.reason)
            is Decision.Confirm -> if (confirmed) run(call, ctx) else Outcome.Confirm(decision.summary.title + "?", decision.summary, call)
            Decision.Execute -> run(call, ctx)
        }

    private suspend fun run(call: ToolCall, ctx: ExecContext): Outcome {
        val tool = registry[call.tool]!!
        return when (val r = runCatching { tool.run(call, ctx) }.getOrElse { ToolResult.Failure(it.toString(), "Não consegui, algo deu errado") }) {
            is ToolResult.Success -> Outcome.Done(r.spoken, call)
            is ToolResult.Failure -> Outcome.Failed(r.spoken, r.reason)
            is ToolResult.NeedsChoice -> Outcome.Choose(r.question, r.options)
        }
    }
}
