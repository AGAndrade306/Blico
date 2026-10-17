package app.blico.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import app.blico.core.SpeechOutput
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import java.util.UUID

/** Uma voz que dá para escolher: só as que rodam no aparelho, para o texto falado não sair dele. */
data class VoiceOption(val name: String, val label: String, val quality: Int)

/** Um motor de TTS instalado (Samsung, Google…). */
data class EngineOption(val packageName: String, val label: String)

/**
 * O que a pessoa escolheu na tela de configuração. Vazio é o padrão do sistema.
 * [neural] é o id de uma voz de [app.blico.core.NeuralVoices]; quando ela está baixada, tem a preferência.
 */
data class VoicePrefs(
    val engine: String? = null,
    val voice: String? = null,
    val rate: Float = 1f,
    val pitch: Float = 1f,
    val neural: String? = null,
)

/**
 * Fala em português com o TTS do Android, offline com a voz instalada.
 * Usa um único motor por processo: crie uma vez e reaproveite; [configure] troca motor e voz.
 */
class AndroidSpeechOutput(context: Context, initial: VoicePrefs = VoicePrefs()) : SpeechOutput {
    private val appContext = context.applicationContext
    private val pending = mutableMapOf<String, CompletableDeferred<Unit>>()
    private var prefs = initial
    private var ready = CompletableDeferred<Boolean>()
    private var tts = create(initial.engine)

    private fun create(engine: String?): TextToSpeech {
        val done = CompletableDeferred<Boolean>().also { ready = it }
        val listener = TextToSpeech.OnInitListener { status -> done.complete(status == TextToSpeech.SUCCESS) }
        val engineOrNull = engine?.takeIf { it.isNotBlank() }
        return (if (engineOrNull != null) TextToSpeech(appContext, listener, engineOrNull) else TextToSpeech(appContext, listener)).apply {
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
    }

    private fun finish(id: String) {
        synchronized(pending) { pending.remove(id) }?.complete(Unit)
    }

    /** Troca as preferências; recria o motor só se ele mudou. */
    fun configure(newPrefs: VoicePrefs) {
        val engineChanged = newPrefs.engine.orEmpty() != prefs.engine.orEmpty()
        prefs = newPrefs
        if (engineChanged) {
            tts.stop()
            tts.shutdown()
            tts = create(newPrefs.engine)
        }
    }

    fun engines(): List<EngineOption> = tts.engines.map { EngineOption(it.name, it.label) }

    fun defaultEngine(): String? = tts.defaultEngine

    /** Vozes em português do motor atual que funcionam sem internet, das melhores para as piores. */
    suspend fun voices(): List<VoiceOption> {
        if (!ready.await()) return emptyList()
        val all = tts.voices.orEmpty()
            .filter { it.locale.language == "pt" && !it.isNetworkConnectionRequired && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareByDescending<Voice> { it.locale.country == "BR" }.thenByDescending { it.quality }.thenBy { it.name })
        return all.mapIndexed { i, v ->
            val country = if (v.locale.country == "BR") "Brasil" else v.locale.displayCountry.ifEmpty { "Portugal" }
            VoiceOption(v.name, "Voz ${i + 1} · $country${qualityLabel(v.quality)}", v.quality)
        }
    }

    private fun qualityLabel(q: Int) = when {
        q >= Voice.QUALITY_VERY_HIGH -> " · muito alta"
        q >= Voice.QUALITY_HIGH -> " · alta"
        else -> ""
    }

    /** Fala e volta quando terminar (ou for interrompida). */
    override suspend fun speak(text: String) {
        if (!ready.await() || text.isBlank()) return
        val engine = tts
        val chosen = prefs.voice?.let { name -> engine.voices.orEmpty().firstOrNull { it.name == name } }
        if (chosen != null) engine.voice = chosen else engine.language = Locale.forLanguageTag("pt-BR")
        engine.setSpeechRate(prefs.rate)
        engine.setPitch(prefs.pitch)
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        synchronized(pending) { pending[id] = done }
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) finish(id)
        done.await()
    }

    override fun stop() {
        tts.stop()
    }

    fun shutdown() = tts.shutdown()
}
