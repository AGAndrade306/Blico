package app.blico.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.blico.core.Transcriber
import app.blico.core.TranscriptionEvent
import app.blico.core.TranscriptionEvent.Reason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

/**
 * Transcrição pelo reconhecedor on-device do Android. O áudio não sai do aparelho:
 * se o reconhecedor local não existir, falha em vez de cair para o reconhecedor em nuvem.
 */
class OnDeviceTranscriber(private val context: Context) : Transcriber {

    fun isAvailable(): Boolean = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun listen(languageTag: String): Flow<TranscriptionEvent> = callbackFlow {
        if (!isAvailable()) {
            trySend(TranscriptionEvent.Failed(Reason.UNAVAILABLE, "Reconhecimento de voz no aparelho indisponível"))
            close()
            awaitClose()
            return@callbackFlow
        }

        val requestedAt = SystemClock.elapsedRealtime()
        var speechEndedAt: Long? = null
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(TranscriptionEvent.Listening(SystemClock.elapsedRealtime() - requestedAt))
            }

            override fun onBeginningOfSpeech() {
                trySend(TranscriptionEvent.SpeechStarted)
            }

            override fun onEndOfSpeech() {
                speechEndedAt = SystemClock.elapsedRealtime()
                trySend(TranscriptionEvent.SpeechEnded)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults.firstResult()?.let { trySend(TranscriptionEvent.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                val latency = speechEndedAt?.let { SystemClock.elapsedRealtime() - it }
                val text = results.firstResult()
                if (text.isNullOrBlank()) {
                    trySend(TranscriptionEvent.Failed(Reason.NO_SPEECH, "Não ouvi nada"))
                } else {
                    trySend(TranscriptionEvent.Final(text, latency))
                }
                close()
            }

            override fun onError(error: Int) {
                trySend(TranscriptionEvent.Failed(error.toReason(), "Erro do reconhecedor: $error"))
                close()
            }

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        recognizer.startListening(intent)

        awaitClose {
            recognizer.cancel()
            recognizer.destroy()
        }
    }.flowOn(Dispatchers.Main) // SpeechRecognizer exige a thread principal.

    /** Pede ao sistema para baixar o pacote de voz do idioma, se faltar. */
    fun requestLanguageDownload(languageTag: String = "pt-BR") {
        if (!isAvailable()) return
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        }
        // Sem destroy() aqui: destruir logo depois cancelaria o pedido antes de o sistema recebê-lo.
        recognizer.triggerModelDownload(intent)
    }

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun Int.toReason(): Reason = when (this) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Reason.NO_PERMISSION
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Reason.NO_SPEECH
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> Reason.LANGUAGE_MISSING
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> Reason.BUSY
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> Reason.UNAVAILABLE
        else -> Reason.OTHER
    }
}
