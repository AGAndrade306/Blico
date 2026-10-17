package app.blico

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import app.blico.brain.HttpCloudTransport
import app.blico.brain.LiteRtLocalProvider
import app.blico.brain.ModelStore
import app.blico.core.AgentRunner
import app.blico.core.Assistant
import app.blico.core.CloudProvider
import app.blico.core.DirectActions
import app.blico.core.ExecContext
import app.blico.core.OptionalProvider
import app.blico.core.RedactingProvider
import app.blico.core.RuleRouter
import app.blico.core.ToolRegistry
import app.blico.core.tools.builtinTools
import app.blico.executor.AndroidDeviceGateway
import app.blico.executor.BlicoAccessibilityService
import app.blico.executor.MediaStoreFileIndex
import app.blico.memory.AgentStore
import app.blico.memory.AndroidContactDirectory
import app.blico.memory.PrefsPlaceBook
import app.blico.memory.SecretStore
import app.blico.voice.AndroidSpeechOutput
import app.blico.voice.VoicePrefs
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.File
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

    /** Se a nuvem entra quando o modelo local não sabe. Desligada, nada sai do celular. */
    var cloudFallback: Boolean
        get() = cloudPrefs.getBoolean("usar", true)
        set(v) = cloudPrefs.edit().putBoolean("usar", v).apply()

    val cloudConfigured: Boolean get() = cloudEndpoint.startsWith("https://") && cloudToken.isNotEmpty()

    private val transport by lazy { HttpCloudTransport({ cloudEndpoint }, { cloudToken }) }

    val models by lazy { ModelStore(this) }
    val local by lazy { LiteRtLocalProvider({ models.selectedFile() }, File(cacheDir, "litertlm")) }

    /** O Gemma no aparelho e, se configurada e ligada, a nuvem, sempre mascarada. */
    private val brains by lazy {
        listOf(
            OptionalProvider(local) { local.isReady() },
            OptionalProvider(
                RedactingProvider(CloudProvider(transport::post)) {
                    RedactingProvider.entitiesFrom(contacts.names(), places.all())
                },
            ) { cloudFallback && cloudConfigured },
        )
    }

    val registry by lazy { ToolRegistry(builtinTools()) }

    /**
     * Primeiro as regras (instantâneas); depois o Gemma no aparelho; só o que os dois não
     * resolvem vai para a nuvem, sempre mascarado, e só se ela estiver configurada e ligada.
     */
    val assistant by lazy { Assistant(providers = listOf(RuleRouter()) + brains, registry = registry) }

    /** Agentes em arquivo. Rodam só com os modelos: as regras repetiriam o mesmo pedido a cada passo. */
    val agents by lazy { AgentStore(this) }
    val agentRunner by lazy { AgentRunner(brains, assistant, directory = { agents.agents() }) }

    val benchmark by lazy { BenchmarkRunner(this, scope) }

    /** Carrega o modelo local enquanto a pessoa fala, para a resposta não esperar por isso. */
    fun warmUpLocal() {
        scope.launch { runCatching { local.warmUp() } }
    }

    fun say(text: String) {
        scope.launch { speech.speak(text) }
    }

    private val directPrefs by lazy { getSharedPreferences("direto", MODE_PRIVATE) }

    /** Enviar no WhatsApp até o fim, depois do Confirmar. Vale quando a acessibilidade está ligada. */
    var directWhatsApp: Boolean
        get() = directPrefs.getBoolean("whatsapp", true)
        set(v) = directPrefs.edit().putBoolean("whatsapp", v).apply()

    /** Completar a ligação depois do Confirmar. Vale quando a permissão de ligar foi dada. */
    var directCalls: Boolean
        get() = directPrefs.getBoolean("ligar", true)
        set(v) = directPrefs.edit().putBoolean("ligar", v).apply()

    val accessibilityOn: Boolean get() = BlicoAccessibilityService.isEnabled(this)
    val canCall: Boolean get() = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    fun directActions() = DirectActions(whatsapp = directWhatsApp && accessibilityOn, calls = directCalls && canCall)

    fun execContext(launcher: (Intent) -> Unit = { startActivity(it) }) = ExecContext(
        requestId = UUID.randomUUID().toString(),
        device = AndroidDeviceGateway(this, launcher),
        contacts = contacts,
        places = places,
        files = files,
        direct = directActions(),
    )
}
