package app.blico.core.tools

import app.blico.core.Contact
import app.blico.core.DeviceAction
import app.blico.core.ExecContext
import app.blico.core.RiskClass
import app.blico.core.Tool
import app.blico.core.ToolCall
import app.blico.core.ToolResult

/** Todas as ferramentas que o Blico tem hoje. */
fun builtinTools(): List<Tool> = listOf(
    AbrirApp, SpotifyTocar, WhatsAppPreparar, Ligar, UberPreparar, NoventaENoveAbrir,
    IfoodAbrir, Navegar, Alarme, Timer, BuscarWeb,
) + fileTools()

internal object AbrirApp : BaseTool(
    name = "abrir_app",
    description = "Abre um app instalado pelo nome",
    risk = RiskClass.READ,
    apps = emptySet(),
    params = mapOf("nome" to "nome do app, como aparece na tela inicial"),
    required = setOf("nome"),
    title = "Abrir app",
) {
    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
        val nome = call.arg("nome")!!
        return ctx.device.perform(DeviceAction.LaunchByName(nome)).toToolResult("Abrindo $nome")
    }
}

internal object SpotifyTocar : BaseTool(
    name = "spotify_tocar",
    description = "Procura uma música, artista, álbum ou playlist no Spotify",
    risk = RiskClass.READ,
    apps = setOf(Apps.SPOTIFY),
    params = mapOf("busca" to "o que tocar"),
    required = setOf("busca"),
    title = "Tocar no Spotify",
) {
    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
        val busca = call.arg("busca")!!
        return ctx.device.perform(DeviceAction.OpenUri("spotify:search:${encode(busca)}", Apps.SPOTIFY))
            .toToolResult("Procurando $busca no Spotify")
    }
}

/** Resolve "contato" em um número: direto se já for número, senão pela agenda. */
private sealed interface Resolved {
    data class One(val name: String, val phone: String) : Resolved
    data class Many(val options: List<Contact>) : Resolved
    data object None : Resolved
}

private suspend fun resolveContact(call: ToolCall, ctx: ExecContext): Resolved {
    call.arg("telefone")?.let { return Resolved.One(call.arg("contato") ?: it, it) }
    val contato = call.arg("contato") ?: return Resolved.None
    if (contato.count(Char::isDigit) >= 8) return Resolved.One(contato, contato)
    val found = ctx.contacts.find(contato).distinctBy { it.phone.filter(Char::isDigit).takeLast(9) }
    return when (found.size) {
        0 -> Resolved.None
        1 -> Resolved.One(found[0].name, found[0].phone)
        else -> Resolved.Many(found.take(4))
    }
}

internal object WhatsAppPreparar : BaseTool(
    name = "whatsapp_preparar",
    description = "Abre a conversa do WhatsApp com a mensagem pronta, sem enviar",
    risk = RiskClass.PREPARE,
    apps = setOf(Apps.WHATSAPP),
    params = mapOf(
        "contato" to "nome ou apelido do contato",
        "texto" to "mensagem a escrever",
        "telefone" to "número, quando já se sabe qual",
    ),
    required = setOf("contato"),
    title = "Mensagem no WhatsApp",
) {
    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
        val texto = call.arg("texto").orEmpty()
        return when (val r = resolveContact(call, ctx)) {
            is Resolved.One -> {
                val uri = buildString {
                    append("https://api.whatsapp.com/send?phone=").append(phoneForWhatsApp(r.phone))
                    if (texto.isNotEmpty()) append("&text=").append(encode(texto))
                }
                ctx.device.perform(DeviceAction.OpenUri(uri, Apps.WHATSAPP))
                    .toToolResult("Mensagem para ${r.name} pronta. É só tocar em enviar.")
            }
            is Resolved.Many -> ToolResult.NeedsChoice(
                question = "Qual ${call.arg("contato")}?",
                options = r.options.map {
                    ToolResult.Choice(it.name, call.copy(args = call.args + ("telefone" to it.phone) + ("contato" to it.name)))
                },
            )
            Resolved.None ->
                // Sem número: o WhatsApp abre a lista de conversas com o texto pronto e você escolhe.
                ctx.device.perform(DeviceAction.ShareText(texto.ifEmpty { " " }, Apps.WHATSAPP))
                    .toToolResult("Não achei ${call.arg("contato")} na agenda. Escolha a conversa no WhatsApp.")
        }
    }
}

internal object Ligar : BaseTool(
    name = "ligar",
    description = "Abre o discador com o número do contato, sem ligar",
    risk = RiskClass.PREPARE,
    apps = emptySet(),
    params = mapOf("contato" to "nome, apelido ou número", "telefone" to "número, quando já se sabe qual"),
    required = setOf("contato"),
    title = "Ligar",
) {
    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult =
        when (val r = resolveContact(call, ctx)) {
            is Resolved.One -> ctx.device.perform(DeviceAction.Dial(r.phone))
                .toToolResult("Número de ${r.name} no discador. Toque para ligar.")
            is Resolved.Many -> ToolResult.NeedsChoice(
                question = "Qual ${call.arg("contato")}?",
                options = r.options.map {
                    ToolResult.Choice(it.name, call.copy(args = call.args + ("telefone" to it.phone) + ("contato" to it.name)))
                },
            )
            Resolved.None -> ToolResult.Failure("contato não encontrado", "Não achei ${call.arg("contato")} na agenda")
        }
}

internal object UberPreparar : BaseTool(
    name = "uber_preparar",
    description = "Abre o Uber com o destino preenchido, sem pedir a corrida",
    risk = RiskClass.PREPARE,
    apps = setOf(Apps.UBER),
    params = mapOf("destino" to "endereço ou apelido salvo, como casa ou trabalho"),
    required = setOf("destino"),
    title = "Corrida no Uber",
) {
    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
        val destino = call.arg("destino")!!
        val address = ctx.places.resolve(destino) ?: if (destino.lowercase() in KNOWN_ALIASES) {
            return ToolResult.Failure("lugar sem endereço", "Ainda não sei onde fica $destino. Cadastre no app do Blico.")
        } else {
            destino
        }
        val uri = "https://m.uber.com/ul/?action=setPickup&pickup=my_location" +
            "&dropoff%5Bformatted_address%5D=${encode(address)}"
        return ctx.device.perform(DeviceAction.OpenUri(uri, Apps.UBER))
            .toToolResult("Uber aberto com destino $destino. Confira e confirme a corrida.")
    }
}

internal val KNOWN_ALIASES = setOf("casa", "trabalho", "escritório", "escritorio")

internal object NoventaENoveAbrir : BaseTool(
    name = "noventa_e_nove_abrir",
    description = "Abre o app 99. O destino ainda precisa ser digitado no app",
    risk = RiskClass.PREPARE,
    apps = setOf(Apps.NOVENTA_E_NOVE),
    params = mapOf("destino" to "para onde ir, opcional"),
    title = "Abrir o 99",
) {
    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult =
        ctx.device.perform(DeviceAction.LaunchPackage(Apps.NOVENTA_E_NOVE)).toToolResult("Abrindo o 99")
}

internal object IfoodAbrir : BaseTool(
    name = "ifood_abrir",
    description = "Abre o iFood",
    risk = RiskClass.READ,
    apps = setOf(Apps.IFOOD),
    params = mapOf("busca" to "comida ou restaurante, opcional"),
    title = "Abrir o iFood",
) {
    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult =
        ctx.device.perform(DeviceAction.LaunchPackage(Apps.IFOOD)).toToolResult("Abrindo o iFood")
}

internal object Navegar : BaseTool(
    name = "navegar",
    description = "Inicia a navegação no Google Maps até um destino",
    risk = RiskClass.READ,
    apps = setOf(Apps.GOOGLE_MAPS),
    params = mapOf("destino" to "endereço, lugar ou apelido salvo"),
    required = setOf("destino"),
    title = "Navegar",
) {
    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
        val destino = call.arg("destino")!!
        val address = ctx.places.resolve(destino) ?: if (destino.lowercase() in KNOWN_ALIASES) {
            return ToolResult.Failure("lugar sem endereço", "Ainda não sei onde fica $destino. Cadastre no app do Blico.")
        } else {
            destino
        }
        return ctx.device.perform(DeviceAction.OpenUri("google.navigation:q=${encode(address)}", Apps.GOOGLE_MAPS))
            .toToolResult("Navegando até $destino")
    }
}

internal object Alarme : BaseTool(
    name = "alarme",
    description = "Cria um alarme no relógio",
    risk = RiskClass.PREPARE,
    apps = emptySet(),
    params = mapOf("hora" to "0 a 23", "minuto" to "0 a 59", "rotulo" to "nome do alarme, opcional"),
    required = setOf("hora"),
    title = "Alarme",
) {
    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
        val hora = call.arg("hora")?.toIntOrNull()?.takeIf { it in 0..23 }
            ?: return ToolResult.Failure("hora inválida", "Não entendi o horário")
        val minuto = call.arg("minuto")?.toIntOrNull()?.takeIf { it in 0..59 } ?: 0
        return ctx.device.perform(DeviceAction.SetAlarm(hora, minuto, call.arg("rotulo")))
            .toToolResult("Alarme para ${hora}h${if (minuto > 0) "%02d".format(minuto) else ""}")
    }
}

internal object Timer : BaseTool(
    name = "timer",
    description = "Inicia um timer",
    risk = RiskClass.PREPARE,
    apps = emptySet(),
    params = mapOf("segundos" to "duração em segundos"),
    required = setOf("segundos"),
    title = "Timer",
) {
    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
        val s = call.arg("segundos")?.toIntOrNull()?.takeIf { it in 1..86_400 }
            ?: return ToolResult.Failure("duração inválida", "Não entendi a duração")
        return ctx.device.perform(DeviceAction.SetTimer(s)).toToolResult("Timer de ${durationLabel(s)}")
    }
}

internal fun durationLabel(seconds: Int): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return listOfNotNull(
        h.takeIf { it > 0 }?.let { if (it == 1) "1 hora" else "$it horas" },
        m.takeIf { it > 0 }?.let { if (it == 1) "1 minuto" else "$it minutos" },
        s.takeIf { it > 0 }?.let { if (it == 1) "1 segundo" else "$it segundos" },
    ).joinToString(" e ")
}

internal object BuscarWeb : BaseTool(
    name = "buscar_web",
    description = "Pesquisa na internet",
    risk = RiskClass.READ,
    apps = emptySet(),
    params = mapOf("consulta" to "o que pesquisar"),
    required = setOf("consulta"),
    title = "Pesquisar",
) {
    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
        val q = call.arg("consulta")!!
        return ctx.device.perform(DeviceAction.WebSearch(q)).toToolResult("Pesquisando $q")
    }
}
