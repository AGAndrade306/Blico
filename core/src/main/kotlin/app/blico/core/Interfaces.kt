package app.blico.core

/** Pedido ao cérebro: o texto transcrito e o catálogo de ferramentas. */
data class LlmRequest(
    val text: String,
    val tools: List<Tool>,
    val context: List<String> = emptyList(),
)

/**
 * Resposta do cérebro. [confidence] vai de 0 a 1 e é o que o roteador híbrido usa
 * para decidir se escala para a nuvem.
 */
data class LlmDecision(
    val calls: List<ToolCall>,
    val confidence: Double,
)

/** Implementado pelo modelo local e pelo da nuvem. */
interface LlmProvider {
    val id: String

    suspend fun chooseTools(request: LlmRequest): LlmDecision
}

interface ConfirmationGate {
    suspend fun confirm(summary: ConfirmationSummary): Boolean
}

/** TTS do Android agora, Piper depois. */
interface SpeechOutput {
    suspend fun speak(text: String)

    /** Chamado pelo gesto e antes de abrir o microfone. */
    fun stop()
}

/** Ação de baixo nível que um backend executa (abrir intent, tocar, digitar). */
sealed interface UiAction {
    data class OpenUri(val uri: String, val packageName: String? = null) : UiAction

    data class Tap(val text: String? = null, val viewId: String? = null) : UiAction

    data class Type(val text: String) : UiAction
}

sealed interface ActionResult {
    data object Done : ActionResult

    data class Failed(val reason: String) : ActionResult
}

/** Intents, AppFunctions, Accessibility, Shizuku. */
interface ExecutorBackend {
    val id: String

    suspend fun isAvailable(): Boolean

    suspend fun perform(action: UiAction): ActionResult
}
