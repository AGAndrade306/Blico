package app.blico.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import app.blico.core.NeuralVoices
import app.blico.core.SpeechOutput
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Fala com uma voz neural do sherpa-onnx, toda no aparelho. Gera frase por frase: a primeira já toca
 * enquanto as outras são geradas. Um motor carregado por vez, numa thread só dele.
 */
class NeuralSpeechOutput(
    private val packs: VoicePackStore,
    /** Voz escolhida e velocidade; relidos a cada fala. */
    private val choice: () -> Pair<NeuralVoices.Voice, Float>?,
) : SpeechOutput {
    private val engineThread = Executors.newSingleThreadExecutor { Thread(it, "blico-voz").apply { priority = Thread.MAX_PRIORITY } }
        .asCoroutineDispatcher()
    private val lock = Mutex()
    private var loaded: Pair<String, OfflineTts>? = null

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var stopped = false

    /** Há voz neural escolhida e baixada. */
    fun isReady(): Boolean = choice()?.let { packs.isReady(it.first.pack) } == true

    /** Carrega o motor antes da primeira fala, enquanto a pessoa ainda fala. */
    suspend fun warmUp() {
        val (voice, _) = choice() ?: return
        if (!packs.isReady(voice.pack)) return
        withContext(engineThread) { engine(voice.pack) }
    }

    override suspend fun speak(text: String) {
        val (voice, speed) = choice() ?: return
        val pieces = NeuralVoices.chunks(text)
        if (pieces.isEmpty() || !packs.isReady(voice.pack)) return
        lock.withLock {
            stopped = false
            val tts = withContext(engineThread) { engine(voice.pack) }
            val audio = openTrack(tts.sampleRate())
            try {
                coroutineScope {
                    // Duas frases de folga: gera a próxima enquanto toca a atual.
                    val ready = Channel<FloatArray>(capacity = 2)
                    launch(engineThread) {
                        try {
                            for (p in pieces) {
                                if (stopped) break
                                ready.send(tts.generate(p, voice.speaker, speed).samples)
                            }
                        } finally {
                            ready.close()
                        }
                    }
                    withContext(Dispatchers.IO) {
                        audio.play()
                        for (samples in ready) {
                            if (stopped) break
                            audio.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                        }
                        if (!stopped) drain(audio)
                    }
                }
            } catch (e: CancellationException) {
                audio.pause()
                throw e
            } finally {
                track = null
                audio.release()
            }
        }
    }

    override fun stop() {
        stopped = true
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
        }
    }

    /** Solta o motor (cerca de 200 MB no Kokoro) quando a voz muda. */
    suspend fun unload() = withContext(engineThread) {
        loaded?.second?.release()
        loaded = null
    }

    private fun engine(pack: NeuralVoices.Pack): OfflineTts {
        loaded?.let { (id, tts) -> if (id == pack.id) return tts }
        loaded?.second?.release()
        loaded = null
        val dir = packs.folder(pack)
        val model = when (pack.family) {
            NeuralVoices.Family.KOKORO -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(dir, pack.model).path,
                    voices = File(dir, "voices.bin").path,
                    tokens = File(dir, "tokens.txt").path,
                    dataDir = File(dir, "espeak-ng-data").path,
                    lang = "pt-br",
                ),
                numThreads = THREADS,
            )
            NeuralVoices.Family.PIPER -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = File(dir, pack.model).path,
                    tokens = File(dir, "tokens.txt").path,
                    dataDir = File(dir, "espeak-ng-data").path,
                ),
                numThreads = THREADS,
            )
        }
        // Sem AssetManager: os arquivos estão no disco, não dentro do APK.
        val tts = OfflineTts(config = OfflineTtsConfig(model = model, maxNumSentences = 1))
        loaded = pack.id to tts
        return tts
    }

    private fun openTrack(rate: Int): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min, rate * 4 / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { track = it }
    }

    /** Espera o fim do que já foi escrito: a posição de reprodução para de andar. */
    private suspend fun drain(audio: AudioTrack) {
        var last = -1
        var still = 0
        while (!stopped && still < 3) {
            delay(60)
            val now = audio.playbackHeadPosition
            if (now == last) still++ else still = 0
            last = now
        }
    }

    private companion object {
        const val THREADS = 4
    }
}
