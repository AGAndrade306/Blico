package app.blico.core

import kotlinx.coroutines.flow.Flow

/** Eventos de uma escuta: começa depois do gesto e termina sozinha quando a fala acaba. */
sealed interface TranscriptionEvent {
    /** Microfone aberto, pronto para ouvir. [setupMs] é o tempo desde o pedido até aqui. */
    data class Listening(val setupMs: Long) : TranscriptionEvent

    data object SpeechStarted : TranscriptionEvent

    data object SpeechEnded : TranscriptionEvent

    data class Partial(val text: String) : TranscriptionEvent

    /** [latencyMs] vai do fim da fala até o texto final. */
    data class Final(val text: String, val latencyMs: Long?) : TranscriptionEvent

    data class Failed(val reason: Reason, val detail: String) : TranscriptionEvent

    enum class Reason { NO_PERMISSION, NO_SPEECH, UNAVAILABLE, LANGUAGE_MISSING, BUSY, OTHER }
}

/** Transcrição local. Nunca envia áudio para fora do aparelho. */
interface Transcriber {
    /** Abre o microfone, emite os eventos e fecha ao terminar. Cancelar o fluxo fecha o microfone. */
    fun listen(languageTag: String = "pt-BR"): Flow<TranscriptionEvent>
}
