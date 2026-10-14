package app.blico.voice

import app.blico.core.TranscriptionEvent
import app.blico.core.TranscriptionEvent.Reason

/** O que o overlay mostra. Puro, para ser testado sem aparelho. */
data class ListeningState(
    val phase: Phase = Phase.STARTING,
    val text: String = "",
    val setupMs: Long? = null,
    val latencyMs: Long? = null,
    val error: String? = null,
) {
    enum class Phase { STARTING, LISTENING, HEARING, PROCESSING, DONE, FAILED }

    val label: String
        get() = when (phase) {
            Phase.STARTING -> "Preparando…"
            Phase.LISTENING -> "Ouvindo"
            Phase.HEARING -> "Ouvindo"
            Phase.PROCESSING -> "Entendendo…"
            Phase.DONE -> "Entendi"
            Phase.FAILED -> error ?: "Não deu certo"
        }

    fun reduce(event: TranscriptionEvent): ListeningState = when (event) {
        is TranscriptionEvent.Listening -> copy(phase = Phase.LISTENING, setupMs = event.setupMs)
        TranscriptionEvent.SpeechStarted -> copy(phase = Phase.HEARING)
        TranscriptionEvent.SpeechEnded -> copy(phase = Phase.PROCESSING)
        is TranscriptionEvent.Partial -> copy(phase = if (phase == Phase.PROCESSING) phase else Phase.HEARING, text = event.text)
        is TranscriptionEvent.Final -> copy(phase = Phase.DONE, text = event.text, latencyMs = event.latencyMs)
        is TranscriptionEvent.Failed -> copy(phase = Phase.FAILED, error = event.reason.message())
    }

    private fun Reason.message(): String = when (this) {
        Reason.NO_PERMISSION -> "Preciso da permissão do microfone. Abra o Blico para liberar."
        Reason.NO_SPEECH -> "Não ouvi nada"
        Reason.UNAVAILABLE -> "Reconhecimento de voz no aparelho indisponível"
        Reason.LANGUAGE_MISSING -> "Falta o pacote de voz em português. Abra o Blico para baixar."
        Reason.BUSY -> "O reconhecedor está ocupado, tente de novo"
        Reason.OTHER -> "Não consegui ouvir, tente de novo"
    }
}
