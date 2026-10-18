package app.blico.core

import app.blico.core.capability.Capabilities
import app.blico.core.capability.Permission
import app.blico.core.capability.SettingsScreen
import app.blico.core.capability.SistemaCapability
import app.blico.core.capability.SystemPort
import app.blico.core.capability.SystemPort.Level
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeSystem : SystemPort {
    var battery: SystemPort.Battery? = SystemPort.Battery(76, charging = false, saver = false)
    var storage: SystemPort.Storage? = SystemPort.Storage(41_000_000_000, 256_000_000_000)
    var network = SystemPort.Network(SystemPort.Network.Type.WIFI, internet = true, wifiOn = true)
    var dndAllowed = true
    var canWriteSettings = true
    val done = mutableListOf<String>()
    var volume = 40
    var brightness = 50

    override fun battery() = battery
    override fun storage() = storage
    override fun network() = network

    override suspend fun torch(on: Boolean): DeviceResult {
        done += "lanterna $on"
        return DeviceResult.Done
    }

    override suspend fun volume(stream: SystemPort.Stream, change: Level): DeviceResult {
        volume = step(volume, change)
        done += "volume $stream $change"
        return DeviceResult.Done
    }

    override fun volumePercent(stream: SystemPort.Stream) = volume

    override suspend fun soundMode(mode: SystemPort.SoundMode): DeviceResult {
        if (mode != SystemPort.SoundMode.VIBRATE && mode != SystemPort.SoundMode.NORMAL && !dndAllowed) {
            return DeviceResult.NeedsPermission(Permission.DO_NOT_DISTURB)
        }
        done += "som $mode"
        return DeviceResult.Done
    }

    override suspend fun brightness(change: Level): DeviceResult {
        if (!canWriteSettings) return DeviceResult.NeedsPermission(Permission.WRITE_SETTINGS)
        brightness = step(brightness, change)
        done += "brilho $change"
        return DeviceResult.Done
    }

    override fun brightnessPercent() = brightness

    override suspend fun open(screen: SettingsScreen): DeviceResult {
        done += "abrir $screen"
        return DeviceResult.Done
    }

    private fun step(now: Int, change: Level) = when (change) {
        Level.Up -> (now + 10).coerceAtMost(100)
        Level.Down -> (now - 10).coerceAtLeast(0)
        Level.Mute -> 0
        Level.Auto -> now
        is Level.To -> change.percent
    }
}

class SistemaTest {
    private val system = FakeSystem()
    private val caps = Capabilities(listOf(SistemaCapability()))
    private val router = RuleRouter(caps.rules())
    private val registry = ToolRegistry(caps.tools())
    private val ctx = ExecContext("t", object : DeviceGateway {
        override suspend fun perform(action: DeviceAction) = DeviceResult.Done
    }, object : ContactDirectory {
        override suspend fun find(query: String) = emptyList<Contact>()
    }, object : PlaceBook {
        override fun resolve(alias: String): String? = null
    }, ports = Ports.None.with(SystemPort::class.java, system))

    private fun assertCall(text: String, tool: String, vararg args: Pair<String, String>) =
        assertEquals(ToolCall(tool, mapOf(*args)), router.parse(text), "frase: $text")

    private suspend fun run(tool: String, vararg args: Pair<String, String>): ToolResult {
        val call = ToolCall(tool, mapOf(*args))
        assertTrue(registry.validate(call).isEmpty(), "chamada inválida: ${registry.validate(call)}")
        return registry[tool]!!.run(call, ctx)
    }

    @Test
    fun `lanterna`() {
        assertCall("liga a lanterna", "lanterna", "estado" to "ligar")
        assertCall("Blico, acende a lanterna por favor", "lanterna", "estado" to "ligar")
        assertCall("apaga a lanterna", "lanterna", "estado" to "desligar")
        assertCall("desliga lanterna", "lanterna", "estado" to "desligar")
    }

    @Test
    fun `wi-fi e afins abrem o painel, porque o Android nao deixa ligar`() {
        assertCall("liga o wi-fi", "abrir_ajuste", "tela" to "wi-fi", "acao" to "ligar")
        assertCall("desativa o bluetooth", "abrir_ajuste", "tela" to "bluetooth", "acao" to "desligar")
        assertCall("ativa o modo avião", "abrir_ajuste", "tela" to "modo avião", "acao" to "ligar")
        assertCall("desliga os dados móveis", "abrir_ajuste", "tela" to "dados móveis", "acao" to "desligar")
        assertCall("abre o wifi", "abrir_ajuste", "tela" to "wifi")
        assertCall("abre as configurações de bateria", "abrir_ajuste", "tela" to "bateria")
        assertCall("abre as configurações", "abrir_ajuste", "tela" to "configuracoes")
        assertCall("vai nas configurações do Bluetooth", "abrir_ajuste", "tela" to "Bluetooth")
        assertCall("abre a economia de bateria", "abrir_ajuste", "tela" to "economia de bateria")
    }

    @Test
    fun `volume`() {
        assertCall("aumenta o volume", "volume", "acao" to "aumentar")
        assertCall("abaixa o som", "volume", "acao" to "diminuir")
        assertCall("sobe o volume do toque", "volume", "acao" to "aumentar", "fluxo" to "toque")
        assertCall("volume no máximo", "volume", "acao" to "máximo")
        assertCall("coloca o volume em 30%", "volume", "acao" to "30")
        assertCall("volume do alarme pra 80 por cento", "volume", "acao" to "80", "fluxo" to "alarme")
        assertCall("muta o som", "volume", "acao" to "mudo")
    }

    @Test
    fun `modo de som`() {
        assertCall("põe no silencioso", "modo_som", "modo" to "silencioso")
        assertCall("coloca o celular no vibrar", "modo_som", "modo" to "vibrar")
        assertCall("modo silencioso", "modo_som", "modo" to "silencioso")
        assertCall("ativa o modo silencioso", "modo_som", "modo" to "silencioso")
        assertCall("silencia o celular", "modo_som", "modo" to "silencioso")
        assertCall("liga o não perturbe", "modo_som", "modo" to "nao_perturbe")
        assertCall("desliga o não perturbe", "modo_som", "modo" to "normal")
        assertCall("tira do silencioso", "modo_som", "modo" to "normal")
    }

    @Test
    fun `brilho`() {
        assertCall("aumenta o brilho", "brilho", "acao" to "aumentar")
        assertCall("diminui o brilho da tela", "brilho", "acao" to "diminuir")
        assertCall("brilho no máximo", "brilho", "acao" to "máximo")
        assertCall("deixa o brilho em 40", "brilho", "acao" to "40")
        assertCall("brilho automático", "brilho", "acao" to "automatico")
    }

    @Test
    fun `perguntas sobre o celular`() {
        assertCall("quanto de bateria tem", "estado_celular", "aspecto" to "bateria")
        assertCall("como tá a bateria?", "estado_celular", "aspecto" to "bateria")
        assertCall("bateria", "estado_celular", "aspecto" to "bateria")
        assertCall("tá carregando?", "estado_celular", "aspecto" to "bateria")
        assertCall("quanto espaço livre eu tenho", "estado_celular", "aspecto" to "armazenamento")
        assertCall("a memória tá cheia?", "estado_celular", "aspecto" to "armazenamento")
        assertCall("tô conectado?", "estado_celular", "aspecto" to "rede")
        assertCall("a internet caiu", "estado_celular", "aspecto" to "rede")
        assertCall("por que a internet não funciona", "estado_celular", "aspecto" to "rede")
        assertCall("como está o celular", "estado_celular", "aspecto" to "tudo")
    }

    @Test
    fun `frases parecidas continuam com as regras de base`() {
        assertCall("liga pra Lanterna Verde", "ligar", "contato" to "Lanterna Verde")
        assertCall("abre a câmera", "abrir_app", "nome" to "câmera")
        assertNull(router.parse("qual bateria comprar pro carro"))
    }

    @Test
    fun `estado responde no card, com sugestao quando algo esta ruim`() = runTest {
        assertEquals("Bateria em 76%.", (run("estado_celular", "aspecto" to "bateria") as ToolResult.Answer).spoken)
        system.battery = SystemPort.Battery(12, charging = false, saver = false)
        assertTrue((run("estado_celular", "aspecto" to "bateria") as ToolResult.Answer).spoken.contains("economia de bateria"))
        system.battery = SystemPort.Battery(12, charging = true, saver = false)
        assertEquals("Bateria em 12%, carregando.", (run("estado_celular", "aspecto" to "bateria") as ToolResult.Answer).spoken)

        assertEquals("Livres 41 GB de 256 GB.", (run("estado_celular", "aspecto" to "armazenamento") as ToolResult.Answer).spoken)
        system.storage = SystemPort.Storage(3_200_000_000, 128_000_000_000)
        val low = (run("estado_celular", "aspecto" to "armazenamento") as ToolResult.Answer).spoken
        assertTrue(low.startsWith("Livres 3,2 GB de 128 GB.") && low.contains("quase cheio"), low)

        system.network = SystemPort.Network(SystemPort.Network.Type.WIFI, internet = false, wifiOn = true)
        assertTrue((run("estado_celular", "aspecto" to "rede") as ToolResult.Answer).spoken.contains("sem internet"))
        system.network = SystemPort.Network(SystemPort.Network.Type.NONE, internet = false, airplane = true)
        assertTrue((run("estado_celular", "aspecto" to "rede") as ToolResult.Answer).spoken.contains("modo avião"))
    }

    @Test
    fun `acoes falam onde ficou`() = runTest {
        assertEquals(ToolResult.Success("Lanterna ligada"), run("lanterna", "estado" to "ligar"))
        assertEquals(ToolResult.Success("Volume da mídia em 50%"), run("volume", "acao" to "aumentar"))
        assertEquals(ToolResult.Success("Volume do toque em 30%"), run("volume", "acao" to "30", "fluxo" to "toque"))
        assertEquals(ToolResult.Success("Volume da mídia no mudo"), run("volume", "acao" to "mudo"))
        assertEquals(ToolResult.Success("Brilho em 100%"), run("brilho", "acao" to "máximo"))
        assertEquals(ToolResult.Success("Celular no silencioso"), run("modo_som", "modo" to "silencioso"))
        assertEquals(ToolResult.Success("Abrindo bateria"), run("abrir_ajuste", "tela" to "bateria"))
        assertEquals("abrir BATTERY", system.done.last())
    }

    @Test
    fun `sem permissao diz qual liberar`() = runTest {
        system.dndAllowed = false
        val r = run("modo_som", "modo" to "nao_perturbe")
        assertIs<ToolResult.Failure>(r)
        assertTrue(r.spoken.contains("Acesso ao Não perturbe"), r.spoken)
        system.canWriteSettings = false
        assertTrue((run("brilho", "acao" to "aumentar") as ToolResult.Failure).spoken.contains("Modificar configurações"))
    }

    @Test
    fun `ligar o wi-fi explica a limitacao do Android`() = runTest {
        val r = run("abrir_ajuste", "tela" to "wi-fi", "acao" to "ligar")
        assertTrue((r as ToolResult.Success).spoken.contains("não deixa"), r.spoken)
        assertEquals("abrir WIFI", system.done.last())
        assertIs<ToolResult.Failure>(run("abrir_ajuste", "tela" to "cafeteira"))
    }

    @Test
    fun `sem a porta explica em vez de quebrar`() = runTest {
        val r = registry["lanterna"]!!.run(ToolCall("lanterna", mapOf("estado" to "ligar")), ctx.copy(ports = Ports.None))
        assertIs<ToolResult.Failure>(r)
    }

    @Test
    fun `telas pelo jeito de falar`() {
        assertEquals(SettingsScreen.WIFI, SettingsScreen.find("Wi-Fi"))
        assertEquals(SettingsScreen.MOBILE_DATA, SettingsScreen.find("dados móveis"))
        assertEquals(SettingsScreen.BATTERY_SAVER, SettingsScreen.find("economia de bateria"))
        assertEquals(SettingsScreen.BATTERY, SettingsScreen.find("a bateria do celular"))
        assertEquals(SettingsScreen.DO_NOT_DISTURB, SettingsScreen.find("não perturbe"))
        assertEquals(SettingsScreen.SETTINGS, SettingsScreen.find("configuracoes"))
        assertNull(SettingsScreen.find("cafeteira"))
    }

    @Test
    fun `classes de risco`() {
        val policy = RiskPolicy(registry)
        listOf(
            ToolCall("estado_celular", mapOf("aspecto" to "bateria")),
            ToolCall("lanterna", mapOf("estado" to "ligar")),
            ToolCall("volume", mapOf("acao" to "aumentar")),
            ToolCall("abrir_ajuste", mapOf("tela" to "wifi")),
        ).forEach { assertEquals(Decision.Execute, policy.decide(it), it.tool) }
    }
}
