package app.blico

import android.app.Application
import android.content.Intent
import app.blico.brain.HttpCloudTransport
import app.blico.core.Assistant
import app.blico.core.CloudProvider
import app.blico.core.ExecContext
import app.blico.core.RedactingProvider
import app.blico.core.RuleRouter
import app.blico.core.ToolRegistry
import app.blico.core.tools.builtinTools
import app.blico.executor.AndroidDeviceGateway
import app.blico.executor.MediaStoreFileIndex
import app.blico.memory.AndroidContactDirectory
import app.blico.memory.PrefsPlaceBook
import app.blico.memory.SecretStore
import app.blico.voice.AndroidSpeechOutput
import app.blico.voice.VoicePrefs
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.util.UUID

/** Peças compartilhadas pelo processo. A fala vive aqui para não ser cortada quando o cartão fecha. */
class BlicoApp : Application() {
    val speech by lazy { AndroidSpeechOutput(this, voicePrefs) }
    private val voiceStore by lazy { getSharedPreferences("voz", MODE_PRIVATE) }

    /** Motor, voz, velocidade e tom escolhidos na tela inicial. */
    var voicePrefs: VoicePrefs
        get() = VoicePrefs(
            engine = voiceStore.getString("motor", null),
            voice = voiceStore.getString("voz", null),
            rate = voiceStore.getFloat("velocidade", 1f),
            pitch = voiceStore.getFloat("tom", 1f),
        )
        set(v) {
            voiceStore.edit()
                .putString("motor", v.engine)
                .putString("voz", v.voice)
                .putFloat("velocidade", v.rate)
                .putFloat("tom", v.pitch)
                .apply()
            speech.configure(v)
        }
    val files by lazy { MediaStoreFileIndex(this) }

    /** Escopo do processo, para a fala continuar depois que o cartão fecha. */
    val scope = MainScope()
    val places by lazy { PrefsPlaceBook(this) }
    private val contacts by lazy { AndroidContactDirectory(this) }
    val secrets by lazy { SecretStore(this) }
    private val cloudPrefs by lazy { getSharedPreferences("nuvem", MODE_PRIVATE) }

    /** Endereço do servidor (Cloudflare Worker) e token do aparelho. */
    var cloudEndpoint: String
        get() = cloudPrefs.getString("endpoint", null).orEmpty()
        set(v) = cloudPrefs.edit().putString("endpoint", v.trim()).apply()
    var cloudToken: String
        get() = secrets.get("device_token").orEmpty()
        set(v) = secrets.set("device_token", v)

    private val transport by lazy { HttpCloudTransport({ cloudEndpoint }, { cloudToken }) }

    /**
     * Primeiro as regras (instantâneas, offline); o que elas não entendem vai para a nuvem,
     * sempre mascarado. O modelo local entra entre os dois numa etapa seguinte.
     */
    val assistant by lazy {
        Assistant(
            providers = listOf(
                RuleRouter(),
                RedactingProvider(CloudProvider(transport::post)) {
                    RedactingProvider.entitiesFrom(contacts.names(), places.all())
                },
            ),
            registry = ToolRegistry(builtinTools()),
        )
    }

    fun say(text: String) {
        scope.launch { speech.speak(text) }
    }

    fun execContext(launcher: (Intent) -> Unit = { startActivity(it) }) = ExecContext(
        requestId = UUID.randomUUID().toString(),
        device = AndroidDeviceGateway(this, launcher),
        contacts = contacts,
        places = places,
        files = files,
    )
}
