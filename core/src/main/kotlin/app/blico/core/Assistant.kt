package app.blico.core

import kotlinx.coroutines.CancellationException

/** O que o Blico mostra e fala depois de um pedido. */
sealed interface Outcome {
    val spoken: String

    data class Done(override val spoken: String, val call: ToolCall) : Outcome

    data class Choose(override val spoken: String, val options: List<ToolResult.Choice>) : Outcome

    /** Classe 2: o cartão mostra [summary] e só executa [call] depois do toque em Confirmar. */
    data class Confirm(override val spoken: String, val summary: ConfirmationSummary, val call: ToolCall) : Outcome

    data class Failed(override val spoken: String, val reason: String) : Outcome

    /** Resposta a uma pergunta: o Blico só fala, não executa nada. */
    data class Answer(override val spoken: String) : Outcome

    data class NotUnderstood(override val spoken: String) : Outcome

    /** Arquivos encontrados; o cartão lista e oferece abrir, compartilhar e renomear. */
    data class Files(override val spoken: String, val items: List<FileItem>) : Outcome
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
    /** [onRoute] recebe o id do provedor que decidiu: regras, local ou nuvem. */
    suspend fun handle(text: String, ctx: ExecContext, onRoute: (String) -> Unit = {}): Outcome {
        val request = LlmRequest(text, modelTools())
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
            decision.calls.firstOrNull()?.let { onRoute(provider.id); return execute(it, ctx) }
            decision.answer?.let { onRoute(provider.id); return Outcome.Answer(it) }
        }
        return when (failure) {
            is CloudUnavailable -> Outcome.Failed("Não consegui falar com a nuvem. ${failure.message}", failure.toString())
            null -> Outcome.NotUnderstood("Ainda não sei fazer isso")
            else -> Outcome.Failed("Não consegui entender agora, tente de novo", failure.toString())
        }
    }

    /** Ferramentas que os modelos podem escolher. */
    fun modelTools(): List<Tool> = registry.all.filter { it.exposedToModels }

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
            is ToolResult.Files -> Outcome.Files(r.spoken, r.items)
        }
    }
}
