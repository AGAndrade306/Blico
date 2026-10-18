package app.blico

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import app.blico.accounts.GoogleLogin
import app.blico.brain.HttpCloudTransport
import app.blico.brain.HttpWebFetch
import app.blico.brain.LiteRtLocalProvider
import app.blico.brain.ModelStore
import app.blico.core.ActivityLog
import app.blico.core.AgentRunner
import app.blico.core.GroupRunner
import app.blico.core.Assistant
import app.blico.core.CloudProvider
import app.blico.core.DeviceKnowledge
import app.blico.core.DirectActions
import app.blico.core.ExecContext
import app.blico.core.NeuralVoices
import app.blico.core.OptionalProvider
import app.blico.core.Ports
import app.blico.core.capability.AgendaCapability
import app.blico.core.capability.Capabilities
import app.blico.core.capability.MemoriaCapability
import app.blico.core.capability.MemoryAwareProvider
import app.blico.core.capability.SistemaCapability
import app.blico.core.capability.SystemPort
import app.blico.core.Origin
import app.blico.core.Outcome
import app.blico.core.RedactingProvider
import app.blico.core.RuleRouter
import app.blico.core.SpeechOutput
import app.blico.core.ToolRegistry
import app.blico.core.tools.builtinTools
import app.blico.core.tools.googleTools
import app.blico.executor.AndroidCalendarPort
import app.blico.executor.AndroidDeviceGateway
import app.blico.executor.AndroidReminders
import app.blico.executor.AndroidSystemPort
import app.blico.executor.BlicoAccessibilityService
import app.blico.executor.MediaStoreFileIndex
import app.blico.memory.AgentStore
import app.blico.memory.AndroidContactDirectory
import app.blico.memory.EncryptedMemoryStore
import app.blico.memory.PrefsPlaceBook
import app.blico.memory.SecretStore
import app.blico.voice.AndroidSpeechOutput
import app.blico.voice.NeuralSpeechOutput
import app.blico.voice.VoicePackStore
import app.blico.voice.VoicePrefs
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** Peças compartilhadas pelo processo. A fala vive aqui para não ser cortada quando o cartão fecha. */
class BlicoApp : Application() {
    /** Voz do Android (Samsung, Google), usada quando não há voz neural baixada e escolhida. */
    val systemSpeech by lazy { AndroidSpeechOutput(this, voicePrefs) }
    val voicePacks by lazy { VoicePackStore(this) }
    val neuralSpeech by lazy {
        NeuralSpeechOutput(voicePacks) { NeuralVoices.byId(voicePrefs.neural)?.let { it to voicePrefs.rate } }
    }

    /** Fala com a voz neural se ela estiver pronta; senão, com a do sistema. */
    val speech: SpeechOutput = object : SpeechOutput {
        override suspend fun speak(text: String) =
            if (neuralSpeech.isReady()) neuralSpeech.speak(text) else systemSpeech.speak(text)

        override fun stop() {
            neuralSpeech.stop()
            systemSpeech.stop()
        }
    }
    private val voiceStore by lazy { getSharedPreferences("voz", MODE_PRIVATE) }

    /** Motor, voz, velocidade e tom escolhidos em Ajustes. */
    var voicePrefs: VoicePrefs
        get() = VoicePrefs(
            engine = voiceStore.getString("motor", null),
            voice = voiceStore.getString("voz", null),
            rate = voiceStore.getFloat("velocidade", 1f),
            pitch = voiceStore.getFloat("tom", 1f),
            neural = voiceStore.getString("neural", null),
        )
        set(v) {
            voiceStore.edit()
                .putString("motor", v.engine)
                .putString("voz", v.voice)
                .putFloat("velocidade", v.rate)
                .putFloat("tom", v.pitch)
                .putString("neural", v.neural)
                .apply()
            systemSpeech.configure(v)
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

    /** Memória pessoal, cifrada no aparelho. Só o Gemma do celular lê; a nuvem nunca. */
    val memory by lazy { EncryptedMemoryStore(this) }

    /** O Gemma no aparelho (com os fatos da memória ligados ao pedido) e, se configurada e ligada, a nuvem, sempre mascarada. */
    private val brains by lazy {
        listOf(
            OptionalProvider(MemoryAwareProvider(local, memory) { capabilities.isOn("memoria") }) { local.isReady() },
            OptionalProvider(
                RedactingProvider(CloudProvider(transport::post)) {
                    RedactingProvider.entitiesFrom(contacts.names(), places.all())
                },
            ) { cloudFallback && cloudConfigured },
        )
    }

    /**
     * Respostas no card: o Gemma do celular e a resposta instantânea do DuckDuckGo.
     * A consulta só vai para o DuckDuckGo se não tiver nome da agenda, endereço, telefone ou e-mail.
     */
    private val knowledge by lazy {
        DeviceKnowledge(
            model = OptionalProvider(local) { local.isReady() },
            fetch = { HttpWebFetch.get(it) },
            knownEntities = { RedactingProvider.entitiesFrom(contacts.names(), places.all()) },
        )
    }

    /** A conta Google, quando a pessoa entra. A Agenda e o Gmail só aparecem aos modelos depois disso. */
    val google by lazy { GoogleLogin(this) }

    private val capabilityPrefs by lazy { getSharedPreferences("funcionalidades", MODE_PRIVATE) }

    /**
     * As funcionalidades em pacotes (sistema, e as que vierem: memória, agenda, notificações).
     * Uma nova entra nesta lista; ferramentas, regras e a tela de Ajustes vêm dela.
     */
    val capabilities by lazy {
        Capabilities(
            listOf(
                SistemaCapability(),
                MemoriaCapability(memory),
                AgendaCapability(AndroidCalendarPort(this), AndroidReminders(this)),
            ),
        ) { capabilityPrefs.getStringSet("desligadas", emptySet()).orEmpty() }
    }

    fun setCapabilityOn(id: String, on: Boolean) {
        val off = capabilityPrefs.getStringSet("desligadas", emptySet()).orEmpty().toMutableSet()
        if (on) off.remove(id) else off.add(id)
        capabilityPrefs.edit().putStringSet("desligadas", off).apply()
    }

    val registry by lazy { ToolRegistry(builtinTools() + googleTools(google) + capabilities.tools()) }

    /**
     * Primeiro as regras (instantâneas); depois o Gemma no aparelho; só o que os dois não
     * resolvem vai para a nuvem, sempre mascarado, e só se ela estiver configurada e ligada.
     */
    val assistant by lazy { Assistant(providers = listOf(RuleRouter(capabilities.rules())) + brains, registry = registry) }

    /** Agentes em arquivo. Rodam só com os modelos: as regras repetiriam o mesmo pedido a cada passo. */
    val agents by lazy { AgentStore(this) }
    val agentRunner by lazy { AgentRunner(brains, assistant, directory = { agents.agents() }) }

    /** Conversa com vários agentes por @nome. */
    val groupRunner by lazy { GroupRunner(agentRunner) }

    val benchmark by lazy { BenchmarkRunner(this, scope) }

    /** Carrega o modelo local enquanto a pessoa fala, para a resposta não esperar por isso. */
    fun warmUpLocal() {
        scope.launch { runCatching { local.warmUp() } }
    }

    /** Carrega a voz neural enquanto a pessoa fala, para a resposta não esperar por isso. */
    fun warmUpVoice() {
        scope.launch { runCatching { neuralSpeech.warmUp() } }
    }

    fun say(text: String) {
        scope.launch { speech.speak(text) }
    }

    private val activityPrefs by lazy { getSharedPreferences("atividade", MODE_PRIVATE) }
    private val activityFlow by lazy { MutableStateFlow(ActivityLog.decode(activityPrefs.getString("log", null))) }

    /** O que o Blico fez, o mais novo primeiro. Fica só no celular. */
    val activity: StateFlow<List<ActivityLog.Entry>> get() = activityFlow

    /** Anota uma ação concluída, venha ela do botão lateral ou da tela inicial, com o selo de onde rodou. */
    fun record(outcome: Outcome, origin: Origin? = null) {
        val entry = ActivityLog.entryFor(outcome, System.currentTimeMillis(), origin) ?: return
        val next = ActivityLog.add(activityFlow.value, entry)
        activityFlow.value = next
        activityPrefs.edit().putString("log", ActivityLog.encode(next)).apply()
    }

    fun clearActivity() {
        activityFlow.value = emptyList()
        activityPrefs.edit().remove("log").apply()
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
        knowledge = knowledge,
        ports = Ports.None.with(SystemPort::class.java, AndroidSystemPort(this, launcher)),
    )
}
