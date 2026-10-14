package app.blico.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.blico.core.SpeechOutput
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import java.util.UUID

/**
 * Fala em português com o TTS do Android, offline com a voz instalada.
 * Usa um único motor por processo: crie uma vez e reaproveite.
 */
class AndroidSpeechOutput(context: Context) : SpeechOutput {
    private val ready = CompletableDeferred<Boolean>()
    private val pending = mutableMapOf<String, CompletableDeferred<Unit>>()

    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }.apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit

            override fun onDone(utteranceId: String) = finish(utteranceId)

            override fun onStop(utteranceId: String, interrupted: Boolean) = finish(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finish(utteranceId)

            override fun onError(utteranceId: String, errorCode: Int) = finish(utteranceId)
        })
    }

    private fun finish(id: String) {
        synchronized(pending) { pending.remove(id) }?.complete(Unit)
    }

    /** Fala e volta quando terminar (ou for interrompida). */
    override suspend fun speak(text: String) {
        if (!ready.await() || text.isBlank()) return
        tts.language = Locale.forLanguageTag("pt-BR")
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        synchronized(pending) { pending[id] = done }
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) finish(id)
        done.await()
    }

    override fun stop() {
        tts.stop()
    }

    fun shutdown() = tts.shutdown()
}
