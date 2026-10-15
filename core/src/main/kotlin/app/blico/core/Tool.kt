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

    /** Ambíguo: o usuário escolhe uma das [options], cada uma já é a chamada completa. */
    data class NeedsChoice(val question: String, val options: List<Choice>) : ToolResult

    data class Choice(val label: String, val call: ToolCall)

    /** Resultado de uma busca: a lista aparece no cartão, e cada item tem as próprias ações. */
    data class Files(val spoken: String, val items: List<FileItem>) : ToolResult

    /**
     * A ferramenta preparou tudo e o próximo passo é outra chamada, por exemplo enviar de fato
     * depois de achar o número. A [call] passa pela política de risco como qualquer outra.
     */
    data class Then(val call: ToolCall) : ToolResult
}

/**
 * O que a pessoa liberou para o Blico fazer até o fim, em vez de deixar pronto.
 * Cada ação direta ainda passa pelo cartão Confirmar (classe 2).
 */
data class DirectActions(
    /** Tocar em enviar no WhatsApp, pelo serviço de acessibilidade. */
    val whatsapp: Boolean = false,
    /** Completar a ligação, com a permissão de fazer chamadas. */
    val calls: Boolean = false,
)

/** O que uma ferramenta precisa do mundo lá fora. No app são implementações Android; nos testes, fakes. */
data class ExecContext(
    val requestId: String,
    val device: DeviceGateway,
    val contacts: ContactDirectory,
    val places: PlaceBook,
    val files: FileIndex = FileIndex.None,
    val clock: java.time.Clock = java.time.Clock.systemDefaultZone(),
    val direct: DirectActions = DirectActions(),
)

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
     * Se a ferramenta é oferecida aos modelos. As que agem sobre um item já mostrado
     * (um arquivo da lista, por id) só nascem de um toque no cartão.
     */
    val exposedToModels: Boolean get() = true

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
