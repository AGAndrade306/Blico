package app.blico.core.capability

import app.blico.core.ConfirmationSummary
import app.blico.core.Contact
import app.blico.core.DeviceAction
import app.blico.core.DeviceResult
import app.blico.core.ExecContext
import app.blico.core.RiskClass
import app.blico.core.Tool
import app.blico.core.ToolCall
import app.blico.core.ToolResult
import app.blico.core.tools.BaseTool
import app.blico.core.tools.arg
import app.blico.core.tools.toToolResult
import java.net.URLEncoder

/** Envio de SMS de verdade, pelo chip. No app, SmsManager; nos testes, uma lista. */
interface SmsPort {
    fun canSend(): Boolean

    suspend fun send(phone: String, text: String): DeviceResult
}

/**
 * SMS: prepara no app de mensagens e, com a permissão dada, envia depois do Confirmar,
 * como o WhatsApp. Para quem não tem WhatsApp ou quando a pessoa diz "SMS".
 */
class SmsCapability(private val port: SmsPort) : Capability {
    override val id = "sms"
    override val title = "SMS"
    override val summary = "Mensagem de texto pelo chip: deixa pronta ou, com sua permissão, envia depois do Confirmar"

    override val tools: List<Tool> = listOf(SmsPreparar(port), SmsEnviar(port))

    override val permissions = listOf(Permission.SMS)

    override val keywords = listOf("sms", "torpedo", "mensagem de texto")

    override val rules: List<SpeechRule> = listOf(
        // "manda um SMS pro João dizendo que eu chego às 8", "manda mensagem de texto pra mãe: tô chegando"
        SpeechRule("^(?:manda|mande|mandar|envia|envie|enviar|escreve|escreva)(?: um| uma)? (?:sms|torpedo|mensagem de texto|mensagem por sms){F} {PRO} (?<contato>.+?)(?:,? (?:dizendo|falando|avisando|perguntando)(?: que)?|,? que|:|,) (?<texto>.+)$") { m ->
            ToolCall("sms_preparar", mapOf("contato" to m["contato"]!!, "texto" to m.raw("texto")!!))
        },
        SpeechRule("^(?:manda|mande|mandar|envia|envie|enviar|escreve|escreva)(?: um| uma)? (?:sms|torpedo|mensagem de texto){F} {PRO} (?<contato>.+)$") { m ->
            ToolCall("sms_preparar", mapOf("contato" to m["contato"]!!))
        },
    )
}

internal class SmsPreparar(private val port: SmsPort) : BaseTool(
    name = "sms_preparar",
    description = "Escreve um SMS (mensagem de texto pelo chip) para um contato. Use só quando a pessoa disser SMS",
    risk = RiskClass.PREPARE,
    apps = emptySet(),
    params = mapOf("contato" to "nome ou número", "texto" to "mensagem", "telefone" to "número, quando já se sabe qual"),
    required = setOf("contato"),
    title = "SMS",
) {
    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
        val texto = call.arg("texto").orEmpty()
        val contato = call.arg("contato")!!
        val found: List<Contact> = call.arg("telefone")?.let { listOf(Contact(contato, it)) }
            ?: if (contato.count(Char::isDigit) >= 8) listOf(Contact(contato, contato))
            else ctx.contacts.find(contato).distinctBy { it.phone.filter(Char::isDigit).takeLast(9) }
        return when {
            found.isEmpty() -> ToolResult.Failure("contato não encontrado", "Não achei $contato na agenda")
            found.size > 1 -> ToolResult.NeedsChoice(
                "Qual $contato?",
                found.take(4).map { ToolResult.Choice(it.name, call.copy(args = call.args + ("telefone" to it.phone) + ("contato" to it.name))) },
            )
            // Com a permissão e o texto, o próximo passo é enviar, e ele passa pelo Confirmar.
            port.canSend() && texto.isNotEmpty() -> ToolResult.Then(
                ToolCall(SmsEnviar.NAME, mapOf("contato" to found[0].name, "telefone" to found[0].phone, "texto" to texto)),
            )
            else -> {
                val phone = found[0].phone.filter { it.isDigit() || it == '+' }
                val body = if (texto.isEmpty()) "" else "?body=" + URLEncoder.encode(texto, Charsets.UTF_8).replace("+", "%20")
                ctx.device.perform(DeviceAction.OpenUri("smsto:$phone$body"))
                    .toToolResult("SMS para ${found[0].name} pronto. É só tocar em enviar.")
            }
        }
    }
}

/** Envia de verdade. Classe 2, só nasce de [SmsPreparar] com o número já achado. */
internal class SmsEnviar(private val port: SmsPort) : BaseTool(
    name = NAME,
    description = "Envia um SMS para um número já confirmado",
    risk = RiskClass.COMMIT,
    apps = emptySet(),
    params = mapOf("contato" to "nome", "telefone" to "número", "texto" to "mensagem"),
    required = setOf("contato", "telefone", "texto"),
) {
    override val exposedToModels = false

    override fun summarize(call: ToolCall) = ConfirmationSummary(
        "Mandar SMS para ${call.arg("contato")}",
        listOf("Para" to "${call.arg("contato")} · ${call.arg("telefone")}", "Mensagem" to call.arg("texto").orEmpty()),
    )

    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult =
        port.send(call.arg("telefone")!!, call.arg("texto")!!).toToolResult("SMS enviado para ${call.arg("contato")}.")

    companion object {
        const val NAME = "sms_enviar"
    }
}
