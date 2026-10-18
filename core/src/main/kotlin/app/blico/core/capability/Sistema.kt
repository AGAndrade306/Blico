package app.blico.core.capability

import app.blico.core.DeviceResult
import app.blico.core.ExecContext
import app.blico.core.RiskClass
import app.blico.core.Tool
import app.blico.core.ToolCall
import app.blico.core.ToolResult
import app.blico.core.tools.BaseTool
import app.blico.core.tools.arg
import app.blico.core.tools.toToolResult
import java.text.Normalizer
import java.util.Locale

/**
 * O que o Blico consegue ler e mudar no próprio celular. No app é o Android (AudioManager, CameraManager,
 * BatteryManager, painéis de Configurações); nos testes, um fake. O Android 10+ não deixa um app ligar
 * Wi-Fi, Bluetooth, NFC ou modo avião sozinho: aí o Blico abre o painel certo e diz isso.
 */
interface SystemPort {
    fun battery(): Battery?

    fun storage(): Storage?

    fun network(): Network

    suspend fun torch(on: Boolean): DeviceResult

    suspend fun volume(stream: Stream, change: Level): DeviceResult

    /** Volume atual de [stream], de 0 a 100, para a fala dizer onde ficou. */
    fun volumePercent(stream: Stream): Int?

    suspend fun soundMode(mode: SoundMode): DeviceResult

    suspend fun brightness(change: Level): DeviceResult

    fun brightnessPercent(): Int?

    suspend fun open(screen: SettingsScreen): DeviceResult

    data class Battery(val percent: Int, val charging: Boolean, val saver: Boolean)

    data class Storage(val freeBytes: Long, val totalBytes: Long)

    data class Network(
        val type: Type,
        /** O Android testou e a conexão chega na internet. */
        val internet: Boolean,
        val airplane: Boolean = false,
        val wifiOn: Boolean = false,
    ) {
        enum class Type { WIFI, MOBILE, OTHER, NONE }
    }

    enum class Stream(val label: String) { MEDIA("da mídia"), RING("do toque"), ALARM("do alarme") }

    sealed interface Level {
        data object Up : Level
        data object Down : Level
        data object Mute : Level
        data object Auto : Level
        data class To(val percent: Int) : Level
    }

    enum class SoundMode { NORMAL, VIBRATE, SILENT, DO_NOT_DISTURB }
}

/**
 * Telas de Configurações que o Blico abre pela voz. [panel] diz que o Android tem um painel por cima do
 * app atual, onde a pessoa liga e desliga sem sair do que estava fazendo.
 */
enum class SettingsScreen(val label: String, val words: List<String>, val panel: Boolean = false) {
    WIFI("Wi-Fi", listOf("wifi", "wi-fi", "wi fi", "wireless", "rede wifi"), panel = true),
    INTERNET("internet", listOf("internet", "conexao", "rede", "redes"), panel = true),
    MOBILE_DATA("dados móveis", listOf("dados moveis", "dados", "4g", "5g", "rede movel")),
    BLUETOOTH("Bluetooth", listOf("bluetooth", "blutu", "blutufi")),
    NFC("NFC", listOf("nfc", "pagamento por aproximacao", "aproximacao"), panel = true),
    AIRPLANE("modo avião", listOf("modo aviao", "aviao", "modo de voo")),
    HOTSPOT("roteador", listOf("roteador", "hotspot", "ancoragem", "compartilhar internet", "roteamento")),
    VOLUME("volume", listOf("volume", "volumes"), panel = true),
    SOUND("som", listOf("som", "sons", "toque", "toques", "sons e vibracao", "vibracao")),
    DO_NOT_DISTURB("Não perturbe", listOf("nao perturbe", "modo nao perturbe")),
    NOTIFICATIONS("notificações", listOf("notificacoes", "notificacao")),
    DISPLAY("tela", listOf("tela", "visor", "display", "brilho", "rotacao", "girar tela", "modo escuro", "tema escuro")),
    BATTERY("bateria", listOf("bateria", "uso da bateria")),
    BATTERY_SAVER("economia de bateria", listOf("economia de bateria", "economia de energia", "modo economia", "economizar bateria", "poupanca de energia")),
    STORAGE("armazenamento", listOf("armazenamento", "memoria", "espaco")),
    LOCATION("localização", listOf("localizacao", "gps", "local")),
    ACCESSIBILITY("acessibilidade", listOf("acessibilidade")),
    APPS("aplicativos", listOf("aplicativos", "apps", "aplicativo")),
    DATE_TIME("data e hora", listOf("data e hora", "hora", "data", "fuso horario")),
    LANGUAGE("idioma", listOf("idioma", "idiomas", "lingua", "teclado")),
    SECURITY("segurança", listOf("seguranca", "biometria", "senha", "bloqueio de tela", "digital")),
    PRIVACY("privacidade", listOf("privacidade", "permissoes")),
    ACCOUNTS("contas", listOf("contas", "conta")),
    SETTINGS("Configurações", listOf("configuracoes", "ajustes", "definicoes", "configuracao", "geral"));

    companion object {
        /** A tela pelo jeito que a pessoa falou: "wi-fi", "dados móveis", "a bateria". */
        fun find(spoken: String): SettingsScreen? {
            val s = simplify(spoken).removePrefix("o ").removePrefix("a ").removePrefix("as ").removePrefix("os ").trim()
            return entries.firstOrNull { it.name.equals(spoken.trim(), ignoreCase = true) }
                ?: entries.firstOrNull { s in it.words }
                ?: entries.firstOrNull { e -> e.words.any { w -> w.length >= 4 && s.startsWith(w) } }
        }
    }
}

/** Sem a porta (fora do app, num teste sem fake), a ferramenta explica em vez de quebrar. */
private val NO_SYSTEM = ToolResult.Failure("sem porta do sistema", "Ainda não consigo mexer no sistema deste aparelho")

internal fun simplify(s: String): String =
    Normalizer.normalize(s.lowercase(Locale.ROOT).trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

/** Sistema do celular: bateria, armazenamento, conexão, lanterna, volume, modo de som, brilho e telas de Configurações. */
class SistemaCapability : Capability {
    override val id = "sistema"
    override val title = "Sistema do celular"
    override val summary = "Lanterna, volume, brilho, Não perturbe, bateria, espaço, conexão e telas de Configurações"

    override val tools: List<Tool> = listOf(
        EstadoCelular, Lanterna, Volume, ModoSom, Brilho, AbrirAjuste,
    )

    override val rules: List<SpeechRule> = SistemaRules.all

    override val permissions = listOf(Permission.DO_NOT_DISTURB, Permission.WRITE_SETTINGS)
}

internal object EstadoCelular : BaseTool(
    name = "estado_celular",
    description = "Diz a bateria, o espaço livre ou a conexão do celular, e sugere o que fazer se algo estiver ruim",
    risk = RiskClass.READ,
    apps = emptySet(),
    params = mapOf("aspecto" to "bateria, armazenamento, rede ou tudo"),
    required = setOf("aspecto"),
    title = "Estado do celular",
) {
    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
        val port = ctx.ports.get<SystemPort>() ?: return NO_SYSTEM
        val aspecto = simplify(call.arg("aspecto")!!)
        val parts = when {
            aspecto.startsWith("bat") || aspecto.startsWith("carga") -> listOf(battery(port))
            aspecto.startsWith("arm") || aspecto.startsWith("esp") || aspecto.startsWith("mem") -> listOf(storage(port))
            aspecto.startsWith("red") || aspecto.startsWith("con") || aspecto.startsWith("int") || aspecto.startsWith("wi") -> listOf(network(port))
            else -> listOf(battery(port), storage(port), network(port))
        }
        return ToolResult.Answer(parts.joinToString(" "))
    }

    private fun battery(port: SystemPort): String {
        val b = port.battery() ?: return "Não consegui ler a bateria."
        return buildString {
            append("Bateria em ").append(b.percent).append('%')
            if (b.charging) append(", carregando")
            append('.')
            if (!b.charging && b.percent <= 20 && !b.saver) append(" Se quiser, diga \"abre a economia de bateria\".")
        }
    }

    private fun storage(port: SystemPort): String {
        val s = port.storage() ?: return "Não consegui ler o armazenamento."
        val text = "Livres ${gb(s.freeBytes)} de ${gb(s.totalBytes)}."
        val low = s.totalBytes > 0 && s.freeBytes * 10 < s.totalBytes
        return if (low) "$text Está quase cheio: dá para liberar espaço em Configurações › Armazenamento." else text
    }

    private fun network(port: SystemPort): String {
        val n = port.network()
        return when {
            n.type == SystemPort.Network.Type.NONE && n.airplane -> "Sem conexão: o modo avião está ligado."
            n.type == SystemPort.Network.Type.NONE && n.wifiOn -> "Sem conexão: o Wi-Fi está ligado, mas não entrou em nenhuma rede. Diga \"abre o Wi-Fi\" para escolher uma."
            n.type == SystemPort.Network.Type.NONE -> "Sem conexão. Ligue o Wi-Fi ou os dados móveis."
            !n.internet && n.type == SystemPort.Network.Type.WIFI -> "Conectado no Wi-Fi, mas sem internet. Tente desligar e ligar o Wi-Fi, ou usar os dados móveis."
            !n.internet -> "Conectado, mas sem internet. Tente o modo avião por alguns segundos."
            n.type == SystemPort.Network.Type.WIFI -> "Conectado no Wi-Fi, com internet."
            n.type == SystemPort.Network.Type.MOBILE -> "Conectado nos dados móveis, com internet."
            else -> "Conectado, com internet."
        }
    }

    private fun gb(bytes: Long): String {
        val g = bytes / 1_000_000_000.0
        return if (g >= 10) "${g.toLong()} GB" else String.format(Locale.forLanguageTag("pt-BR"), "%.1f GB", g)
    }
}

internal object Lanterna : BaseTool(
    name = "lanterna",
    description = "Liga ou desliga a lanterna",
    risk = RiskClass.PREPARE,
    apps = emptySet(),
    params = mapOf("estado" to "ligar ou desligar"),
    required = setOf("estado"),
    title = "Lanterna",
) {
    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
        val port = ctx.ports.get<SystemPort>() ?: return NO_SYSTEM
        val on = !simplify(call.arg("estado")!!).let { it.startsWith("des") || it.startsWith("apag") || it == "off" }
        return port.torch(on).toToolResult(if (on) "Lanterna ligada" else "Lanterna desligada")
    }
}

/** "aumentar", "diminuir", "mudo", "máximo", "automático" ou um número de 0 a 100. */
internal fun parseLevel(raw: String?): SystemPort.Level? {
    val s = simplify(raw ?: return null)
    s.filter(Char::isDigit).toIntOrNull()?.let { return SystemPort.Level.To(it.coerceIn(0, 100)) }
    return when {
        s.startsWith("aum") || s.startsWith("sob") || s.startsWith("mais") || s == "up" -> SystemPort.Level.Up
        s.startsWith("dim") || s.startsWith("aba") || s.startsWith("bai") || s.startsWith("menos") || s == "down" -> SystemPort.Level.Down
        s.startsWith("mud") || s.startsWith("sil") || s == "zero" || s.startsWith("mut") -> SystemPort.Level.Mute
        s.startsWith("max") || s == "tudo" || s == "cem" -> SystemPort.Level.To(100)
        s.startsWith("min") -> SystemPort.Level.To(0)
        s.startsWith("meio") || s == "metade" -> SystemPort.Level.To(50)
        s.startsWith("auto") -> SystemPort.Level.Auto
        else -> null
    }
}

internal object Volume : BaseTool(
    name = "volume",
    description = "Aumenta, diminui, silencia ou define o volume (de 0 a 100) da mídia, do toque ou do alarme",
    risk = RiskClass.PREPARE,
    apps = emptySet(),
    params = mapOf(
        "acao" to "aumentar, diminuir, mudo ou um número de 0 a 100",
        "fluxo" to "midia (padrão), toque ou alarme",
    ),
    required = setOf("acao"),
    title = "Volume",
) {
    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
        val port = ctx.ports.get<SystemPort>() ?: return NO_SYSTEM
        val level = parseLevel(call.arg("acao"))?.takeIf { it != SystemPort.Level.Auto }
            ?: return ToolResult.Failure("nível inválido", "Não entendi como mudar o volume")
        val stream = call.arg("fluxo")?.let(::simplify).let { f ->
            when {
                f == null -> SystemPort.Stream.MEDIA
                f.startsWith("toq") || f.startsWith("cham") || f.startsWith("camp") -> SystemPort.Stream.RING
                f.startsWith("ala") || f.startsWith("desp") -> SystemPort.Stream.ALARM
                else -> SystemPort.Stream.MEDIA
            }
        }
        val r = port.volume(stream, level)
        if (r != DeviceResult.Done) return r.toToolResult("")
        val now = port.volumePercent(stream)
        val spoken = when {
            level == SystemPort.Level.Mute -> "Volume ${stream.label} no mudo"
            now != null -> "Volume ${stream.label} em $now%"
            else -> "Volume ${stream.label} ajustado"
        }
        return ToolResult.Success(spoken)
    }
}

internal object ModoSom : BaseTool(
    name = "modo_som",
    description = "Põe o celular no som normal, vibrar, silencioso ou Não perturbe",
    risk = RiskClass.PREPARE,
    apps = emptySet(),
    params = mapOf("modo" to "normal, vibrar, silencioso ou nao_perturbe"),
    required = setOf("modo"),
    title = "Modo de som",
) {
    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
        val port = ctx.ports.get<SystemPort>() ?: return NO_SYSTEM
        val m = simplify(call.arg("modo")!!)
        val (mode, spoken) = when {
            m.startsWith("vib") -> SystemPort.SoundMode.VIBRATE to "Celular no vibrar"
            m.startsWith("sil") || m == "mudo" -> SystemPort.SoundMode.SILENT to "Celular no silencioso"
            m.startsWith("nao") || m.contains("perturb") -> SystemPort.SoundMode.DO_NOT_DISTURB to "Não perturbe ligado"
            m.startsWith("norm") || m.startsWith("som") || m.startsWith("des") -> SystemPort.SoundMode.NORMAL to "Som normal de volta"
            else -> return ToolResult.Failure("modo inválido: $m", "Não entendi qual modo de som")
        }
        return port.soundMode(mode).toToolResult(spoken)
    }
}

internal object Brilho : BaseTool(
    name = "brilho",
    description = "Aumenta, diminui, define (0 a 100) ou deixa automático o brilho da tela",
    risk = RiskClass.PREPARE,
    apps = emptySet(),
    params = mapOf("acao" to "aumentar, diminuir, automatico ou um número de 0 a 100"),
    required = setOf("acao"),
    title = "Brilho",
) {
    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
        val port = ctx.ports.get<SystemPort>() ?: return NO_SYSTEM
        val level = parseLevel(call.arg("acao"))?.takeIf { it != SystemPort.Level.Mute }
            ?: return ToolResult.Failure("nível inválido", "Não entendi como mudar o brilho")
        val r = port.brightness(level)
        if (r != DeviceResult.Done) return r.toToolResult("")
        val now = port.brightnessPercent()
        return ToolResult.Success(
            when {
                level == SystemPort.Level.Auto -> "Brilho automático"
                now != null -> "Brilho em $now%"
                else -> "Brilho ajustado"
            },
        )
    }
}

internal object AbrirAjuste : BaseTool(
    name = "abrir_ajuste",
    description = "Abre uma tela de Configurações: wifi, bluetooth, nfc, dados móveis, modo avião, roteador, som, " +
        "notificações, tela, bateria, economia de bateria, armazenamento, localização, acessibilidade e outras. " +
        "Use também para ligar ou desligar Wi-Fi, Bluetooth, NFC e modo avião, que o Android só deixa a pessoa mudar",
    risk = RiskClass.READ,
    apps = emptySet(),
    params = mapOf("tela" to "qual tela, por exemplo wifi ou bateria", "acao" to "ligar ou desligar, se a pessoa pediu"),
    required = setOf("tela"),
    title = "Abrir Configurações",
) {
    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
        val port = ctx.ports.get<SystemPort>() ?: return NO_SYSTEM
        val tela = call.arg("tela")!!
        val screen = SettingsScreen.find(tela)
            ?: return ToolResult.Failure("tela desconhecida: $tela", "Não sei abrir a tela de $tela. Abra as Configurações e procure por lá.")
        val toggle = call.arg("acao")?.let(::simplify)?.let { it.startsWith("lig") || it.startsWith("ativ") || it.startsWith("des") }
        val spoken = when {
            toggle == true && screen.panel -> "O Android não deixa apps mudarem o ${screen.label} sozinhos. Abri o painel, é só tocar."
            toggle == true -> "O Android não deixa apps mudarem o ${screen.label} sozinhos. Abri a tela, é só tocar."
            else -> "Abrindo ${screen.label}"
        }
        return port.open(screen).toToolResult(spoken)
    }
}
