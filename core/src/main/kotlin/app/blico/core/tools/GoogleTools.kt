package app.blico.core.tools

import app.blico.core.ExecContext
import app.blico.core.RiskClass
import app.blico.core.Tool
import app.blico.core.ToolCall
import app.blico.core.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Base64

/**
 * A conta Google conectada. No app, o login é o do Play Services e o acesso fica só no aparelho;
 * nos testes, um fake. O Blico fala direto com o Google, sem passar pelo servidor próprio.
 */
interface GoogleAccount {
    /** Se a pessoa entrou e liberou o acesso. Sem isso, as ferramentas nem aparecem aos modelos. */
    val connected: Boolean

    suspend fun request(method: String, url: String, body: String? = null): GoogleResponse
}

data class GoogleResponse(val status: Int, val body: String)

/** A Agenda e o Gmail. Ler executa direto; criar evento e enviar e-mail param no Confirmar. */
fun googleTools(account: GoogleAccount): List<Tool> = listOf(
    AgendaVer(account), AgendaCriar(account), GmailLer(account), GmailRascunho(account), GmailEnviar(account),
)

private val json = Json { ignoreUnknownKeys = true }
private const val CALENDAR = "https://www.googleapis.com/calendar/v3/calendars/primary/events"
private const val GMAIL = "https://gmail.googleapis.com/gmail/v1/users/me"

private abstract class GoogleTool(
    private val account: GoogleAccount,
    name: String,
    description: String,
    risk: RiskClass,
    params: Map<String, String>,
    required: Set<String>,
    title: String,
) : BaseTool(name, description, risk, emptySet(), params, required, title) {
    override val exposedToModels: Boolean get() = account.connected

    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
        if (!account.connected) return ToolResult.Failure("google desconectado", "Entre na sua conta Google nos Agentes primeiro")
        return try {
            act(call, ctx)
        } catch (e: GoogleFailure) {
            ToolResult.Failure(e.message ?: "erro", e.spoken)
        }
    }

    abstract suspend fun act(call: ToolCall, ctx: ExecContext): ToolResult

    protected suspend fun api(method: String, url: String, body: String? = null): JsonObject {
        val r = account.request(method, url, body)
        when {
            r.status == 401 -> throw GoogleFailure("401", "Preciso que você entre de novo na conta Google")
            r.status == 403 -> throw GoogleFailure("403: ${r.body.take(200)}", "O Google não liberou esse acesso. Entre de novo e aceite tudo")
            r.status !in 200..299 -> throw GoogleFailure("${r.status}: ${r.body.take(200)}", "O Google não respondeu agora")
        }
        return if (r.body.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(r.body).jsonObject
    }
}

private class GoogleFailure(reason: String, val spoken: String) : Exception(reason)

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

/** "hoje", "amanhã" ou uma data AAAA-MM-DD. */
internal fun parseDay(text: String?, today: LocalDate): LocalDate? {
    val t = text?.trim()?.lowercase().orEmpty()
    return when (t) {
        "", "hoje" -> today
        "amanhã", "amanha" -> today.plusDays(1)
        "ontem" -> today.minusDays(1)
        else -> try {
            LocalDate.parse(t)
        } catch (e: DateTimeParseException) {
            null
        }
    }
}

private class AgendaVer(account: GoogleAccount) : GoogleTool(
    account,
    name = "agenda_ver",
    description = "Lista os compromissos de um dia na Agenda Google",
    risk = RiskClass.READ,
    params = mapOf("dia" to "hoje, amanhã ou a data AAAA-MM-DD; sem nada, hoje"),
    required = emptySet(),
    title = "Ver a agenda",
) {
    override suspend fun act(call: ToolCall, ctx: ExecContext): ToolResult {
        val zone = ctx.clock.zone
        val day = parseDay(call.arg("dia"), LocalDate.now(ctx.clock))
            ?: return ToolResult.Failure("dia inválido: ${call.arg("dia")}", "Não entendi o dia")
        val from = day.atStartOfDay(zone).toOffsetDateTime()
        val to = day.plusDays(1).atStartOfDay(zone).toOffsetDateTime()
        val url = CALENDAR + "?singleEvents=true&orderBy=startTime&maxResults=20" +
            "&timeMin=" + encode(from.toString()) + "&timeMax=" + encode(to.toString())
        val items = api("GET", url)["items"]?.jsonArray.orEmpty().map { it.jsonObject }
        if (items.isEmpty()) return ToolResult.Success("Nada na agenda ${label(day, ctx)}")
        val lines = items.map { e ->
            val start = e["start"]?.jsonObject
            val time = start?.str("dateTime")?.let { OffsetDateTime.parse(it).atZoneSameInstant(zone).format(HOUR) } ?: "o dia todo"
            "$time, ${e.str("summary") ?: "sem título"}"
        }
        return ToolResult.Success("${label(day, ctx).replaceFirstChar { it.uppercase() }}: " + lines.joinToString("; "))
    }

    private fun label(day: LocalDate, ctx: ExecContext): String = when (day) {
        LocalDate.now(ctx.clock) -> "hoje"
        LocalDate.now(ctx.clock).plusDays(1) -> "amanhã"
        else -> "em " + day.format(DAY)
    }
}

private class AgendaCriar(account: GoogleAccount) : GoogleTool(
    account,
    name = "agenda_criar",
    description = "Cria um compromisso na Agenda Google",
    risk = RiskClass.COMMIT,
    params = mapOf(
        "titulo" to "nome do compromisso",
        "inicio" to "data e hora no formato AAAA-MM-DDTHH:MM",
        "duracao_min" to "duração em minutos; sem nada, 60",
        "local" to "onde, se a pessoa disse",
    ),
    required = setOf("titulo", "inicio"),
    title = "Criar na agenda",
) {
    override suspend fun act(call: ToolCall, ctx: ExecContext): ToolResult {
        val start = try {
            LocalDateTime.parse(call.arg("inicio")!!).atZone(ctx.clock.zone)
        } catch (e: DateTimeParseException) {
            return ToolResult.Failure("início inválido: ${call.arg("inicio")}", "Não entendi a data e a hora")
        }
        val minutes = call.arg("duracao_min")?.trim()?.toLongOrNull()?.coerceIn(5, 24 * 60) ?: 60
        val body = buildJsonObject {
            put("summary", call.arg("titulo")!!)
            call.arg("local")?.let { put("location", it) }
            putJsonObject("start") { put("dateTime", start.toOffsetDateTime().toString()) }
            putJsonObject("end") { put("dateTime", start.plus(Duration.ofMinutes(minutes)).toOffsetDateTime().toString()) }
        }
        api("POST", CALENDAR, body.toString())
        return ToolResult.Success("Pus ${call.arg("titulo")} na agenda, ${start.format(WHEN)}")
    }
}

private class GmailLer(account: GoogleAccount) : GoogleTool(
    account,
    name = "gmail_ler",
    description = "Lê os e-mails mais recentes do Gmail, ou os que batem com uma busca",
    risk = RiskClass.READ,
    params = mapOf("busca" to "busca do Gmail, como from:ana ou is:unread; sem nada, a caixa de entrada"),
    required = emptySet(),
    title = "Ler e-mails",
) {
    override suspend fun act(call: ToolCall, ctx: ExecContext): ToolResult {
        val q = call.arg("busca")?.takeIf { it.isNotBlank() } ?: "in:inbox"
        val ids = api("GET", "$GMAIL/messages?maxResults=$MAX_MAILS&q=${encode(q)}")["messages"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject.str("id") }
        if (ids.isEmpty()) return ToolResult.Success("Nenhum e-mail encontrado")
        val mails = ids.map { id ->
            val m = api("GET", "$GMAIL/messages/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject")
            val headers = m["payload"]?.jsonObject?.get("headers")?.jsonArray.orEmpty().map { it.jsonObject }
            fun header(n: String) = headers.firstOrNull { it.str("name").equals(n, ignoreCase = true) }?.str("value").orEmpty()
            "De ${header("From")}: ${header("Subject")}. ${m.str("snippet").orEmpty()}".trim()
        }
        return ToolResult.Success(mails.joinToString("\n"))
    }
}

/** Monta a mensagem no formato que o Gmail aceita: RFC 2822 em base64 de URL. */
internal fun rawMail(to: String, subject: String, text: String): String {
    val encodedSubject = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(subject.toByteArray()) + "?="
    // Uma quebra de linha no destinatário viraria um cabeçalho a mais.
    val mail = "To: ${to.replace(Regex("[\r\n]"), "")}\r\n" +
        "Subject: $encodedSubject\r\n" +
        "MIME-Version: 1.0\r\n" +
        "Content-Type: text/plain; charset=UTF-8\r\n" +
        "Content-Transfer-Encoding: 8bit\r\n\r\n" +
        text
    return Base64.getUrlEncoder().withoutPadding().encodeToString(mail.toByteArray())
}

private val MAIL_PARAMS = mapOf("para" to "endereço de e-mail", "assunto" to "assunto", "texto" to "corpo do e-mail")

private class GmailRascunho(account: GoogleAccount) : GoogleTool(
    account,
    name = "gmail_rascunho",
    description = "Deixa um e-mail pronto nos rascunhos do Gmail, sem enviar",
    risk = RiskClass.PREPARE,
    params = MAIL_PARAMS,
    required = setOf("para", "assunto", "texto"),
    title = "Rascunho no Gmail",
) {
    override suspend fun act(call: ToolCall, ctx: ExecContext): ToolResult {
        val body = buildJsonObject {
            putJsonObject("message") { put("raw", rawMail(call.arg("para")!!, call.arg("assunto")!!, call.arg("texto")!!)) }
        }
        api("POST", "$GMAIL/drafts", body.toString())
        return ToolResult.Success("Deixei o e-mail para ${call.arg("para")} nos rascunhos")
    }
}

private class GmailEnviar(account: GoogleAccount) : GoogleTool(
    account,
    name = "gmail_enviar",
    description = "Envia um e-mail pelo Gmail",
    risk = RiskClass.COMMIT,
    params = MAIL_PARAMS,
    required = setOf("para", "assunto", "texto"),
    title = "Enviar e-mail",
) {
    override suspend fun act(call: ToolCall, ctx: ExecContext): ToolResult {
        val body = buildJsonObject { put("raw", rawMail(call.arg("para")!!, call.arg("assunto")!!, call.arg("texto")!!)) }
        api("POST", "$GMAIL/messages/send", body.toString())
        return ToolResult.Success("Enviei o e-mail para ${call.arg("para")}")
    }
}

private const val MAX_MAILS = 5
private val HOUR = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("dd/MM")
private val WHEN = DateTimeFormatter.ofPattern("dd/MM 'às' HH:mm")
