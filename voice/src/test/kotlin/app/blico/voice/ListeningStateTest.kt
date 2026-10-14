package app.blico.voice

import app.blico.core.TranscriptionEvent
import app.blico.core.TranscriptionEvent.Reason
import app.blico.voice.ListeningState.Phase
import kotlin.test.Test
import kotlin.test.assertEquals

class ListeningStateTest {
    private fun run(vararg events: TranscriptionEvent) =
        events.fold(ListeningState()) { s, e -> s.reduce(e) }

    @Test
    fun `fluxo completo termina com o texto final e as medidas`() {
        val s = run(
            TranscriptionEvent.Listening(setupMs = 180),
            TranscriptionEvent.SpeechStarted,
            TranscriptionEvent.Partial("manda pro"),
            TranscriptionEvent.SpeechEnded,
            TranscriptionEvent.Partial("manda pro João"),
            TranscriptionEvent.Final("manda pro João que eu chego já", latencyMs = 420),
        )
        assertEquals(Phase.DONE, s.phase)
        assertEquals("manda pro João que eu chego já", s.text)
        assertEquals(180, s.setupMs)
        assertEquals(420, s.latencyMs)
    }

    @Test
    fun `parcial depois do fim da fala continua processando`() {
        val s = run(TranscriptionEvent.SpeechEnded, TranscriptionEvent.Partial("oi"))
        assertEquals(Phase.PROCESSING, s.phase)
    }

    @Test
    fun `falta de pacote de idioma vira mensagem acionavel`() {
        val s = run(TranscriptionEvent.Failed(Reason.LANGUAGE_MISSING, "x"))
        assertEquals(Phase.FAILED, s.phase)
        assertEquals("Falta o pacote de voz em português. Abra o Blico para baixar.", s.label)
    }
}
