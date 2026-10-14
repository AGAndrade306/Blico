package app.blico.core

/** Uma chamada de ferramenta, no mesmo formato para o modelo local e para a nuvem. */
data class ToolCall(val tool: String, val args: Map<String, String> = emptyMap())

/** Subconjunto de JSON Schema que o Blico usa para descrever parâmetros. */
data class JsonSchema(
    val properties: Map<String, Param> = emptyMap(),
    val required: Set<String> = emptySet(),
) {
    data class Param(val type: String = "string", val description: String)
}

sealed interface ToolResult {
    /** [spoken] é a frase curta que o Blico fala ao terminar. */
    data class Success(val spoken: String) : ToolResult

    data class Failure(val reason: String, val spoken: String) : ToolResult
}

/** Contexto de uma execução. Cresce conforme os módulos entram. */
data class ExecContext(val requestId: String)

interface Tool {
    val name: String

    /** Entra no prompt do modelo. */
    val description: String

    /** O mesmo schema serve ao modelo local e à nuvem. */
    val parameters: JsonSchema
    val risk: RiskClass

    /** Pacotes Android que esta ferramenta pode tocar. O Executor recusa o resto. */
    val apps: Set<String>

    /**
     * Resumo do cartão de confirmação, gerado a partir da chamada estruturada.
     * Nunca usa texto livre do modelo.
     */
    fun summarize(call: ToolCall): ConfirmationSummary =
        ConfirmationSummary(
            title = name,
            fields = call.args.entries
                .sortedBy { it.key }
                .map { it.key to it.value },
        )

    suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult
}
