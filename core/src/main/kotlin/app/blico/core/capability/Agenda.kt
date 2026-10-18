package app.blico.core.capability

import app.blico.core.ConfirmationSummary
import app.blico.core.DeviceResult
import app.blico.core.ExecContext
import app.blico.core.PtWhen
import app.blico.core.RiskClass
import app.blico.core.Tool
import app.blico.core.ToolCall
import app.blico.core.ToolResult
import app.blico.core.tools.BaseTool
import app.blico.core.tools.arg
import app.blico.core.tools.toToolResult
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Um compromisso da agenda do celular (que já inclui as contas sincronizadas, como a do Google). */
data class CalendarEvent(
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean = false,
    val location: String? = null,
)

/** A agenda do aparelho. No app é o CalendarContract; nos testes, uma lista. */
interface CalendarPort {
    fun canRead(): Boolean

    fun canWrite(): Boolean

    /** Compromissos que cruzam o intervalo, em ordem de início. */
    suspend fun events(from: LocalDateTime, to: LocalDateTime): List<CalendarEvent>

    suspend fun insert(title: String, start: LocalDateTime, end: LocalDateTime, location: String?): DeviceResult
}

data class Reminder(val id: String, val text: String, val at: LocalDateTime)

/** Lembretes com hora, avisados por notificação. No app, AlarmManager; nos testes, uma lista. */
interface ReminderPort {
    fun canNotify(): Boolean

    suspend fun schedule(text: String, at: LocalDateTime): DeviceResult

    suspend fun pending(): List<Reminder>

    suspend fun cancel(id: String): Boolean
}

/** Agenda do celular e lembretes: ver o dia, horários livres, conflitos, criar compromisso e lembrar na hora. */
class AgendaCapability(private val calendar: CalendarPort, private val reminders: ReminderPort) : Capability {
    override val id = "agenda"
    override val title = "Agenda e lembretes"
    override val summary = "Seus compromissos, horários livres e conflitos, criar evento com Confirmar e lembretes na hora certa"

    override val tools: List<Tool> = listOf(
        AgendaDia(calendar), HorariosLivres(calendar), EventoCriar(calendar),
        LembreteCriar(reminders), LembretesVer(reminders), LembreteCancelar(reminders),
    )

    override val rules: List<SpeechRule> = AgendaRules.all

    override val permissions = listOf(Permission.CALENDAR, Permission.NOTIFICATIONS)
}

internal object AgendaText {
    private val PT = Locale.forLanguageTag("pt-BR")
    private val DAY = DateTimeFormatter.ofPattern("d/M")

    fun hour(t: LocalTime): String = if (t.minute == 0) "${t.hour}h" else "${t.hour}h%02d".format(t.minute)

    fun day(d: LocalDate, today: LocalDate): String = when (d) {
        today -> "hoje"
        today.plusDays(1) -> "amanhã"
        else -> d.dayOfWeek.getDisplayName(TextStyle.FULL, PT).substringBefore('-') + ", " + d.format(DAY)
    }

    fun whenText(at: LocalDateTime, now: LocalDateTime): String = "${day(at.toLocalDate(), now.toLocalDate())} às ${hour(at.toLocalTime())}"

    /** O dia (ou a semana) pedido: "hoje", "amanhã", "sexta", "dia 25", "semana". Nulo: não entendeu. */
    fun range(dia: String?, now: LocalDateTime): Pair<LocalDate, Int>? {
        val d = simplify(dia ?: "hoje")
        if (d.isEmpty() || d == "hoje") return now.toLocalDate() to 1
        if (d.contains("semana")) return now.toLocalDate() to 7
        val at = PtWhen.resolve(d, now) ?: return null
        return at.toLocalDate() to 1
    }
}

internal class AgendaDia(private val calendar: CalendarPort) : BaseTool(
    name = "agenda_dia",
    description = "Lista os compromissos da agenda do celular num dia (ou na semana) e avisa de conflitos",
    risk = RiskClass.READ,
    apps = emptySet(),
    params = mapOf("dia" to "hoje, amanhã, um dia da semana, dia 25 ou semana; sem nada, hoje"),
    title = "Agenda",
) {
    override val readsPrivate = true

    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
        if (!calendar.canRead()) return DeviceResult.NeedsPermission(Permission.CALENDAR).toToolResult("")
        val now = LocalDateTime.now(ctx.clock)
        val (from, days) = AgendaText.range(call.arg("dia"), now) ?: return ToolResult.Failure("dia inválido", "Não entendi o dia")
        val events = calendar.events(from.atStartOfDay(), from.plusDays(days.toLong()).atStartOfDay())
        val label = if (days == 1) AgendaText.day(from, now.toLocalDate()) else "nos próximos 7 dias"
        if (events.isEmpty()) return ToolResult.Answer("Nada na agenda $label.")
        val lines = events.map { e ->
            val time = if (e.allDay) "o dia todo" else AgendaText.hour(e.start.toLocalTime())
            val day = if (days > 1) AgendaText.day(e.start.toLocalDate(), now.toLocalDate()) + ", " else ""
            "$day$time, ${e.title}"
        }
        val clashes = conflicts(events)
        val warn = if (clashes.isEmpty()) "" else " Conflito: " + clashes.joinToString("; ") { "${it.first.title} e ${it.second.title}" } + "."
        return ToolResult.Answer("${label.replaceFirstChar { it.uppercase() }}: ${lines.joinToString("; ")}.$warn")
    }
}

/** Pares de compromissos com hora que se sobrepõem. */
internal fun conflicts(events: List<CalendarEvent>): List<Pair<CalendarEvent, CalendarEvent>> {
    val timed = events.filterNot { it.allDay }.sortedBy { it.start }
    return timed.flatMapIndexed { i, a -> timed.drop(i + 1).filter { b -> b.start < a.end }.map { a to it } }
}

internal class HorariosLivres(private val calendar: CalendarPort) : BaseTool(
    name = "horarios_livres",
    description = "Diz os horários livres de um dia na agenda do celular, entre 8h e 20h",
    risk = RiskClass.READ,
    apps = emptySet(),
    params = mapOf("dia" to "hoje, amanhã, um dia da semana ou dia 25; sem nada, hoje"),
    title = "Horários livres",
) {
    override val readsPrivate = true

    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
        if (!calendar.canRead()) return DeviceResult.NeedsPermission(Permission.CALENDAR).toToolResult("")
        val now = LocalDateTime.now(ctx.clock)
        val (day, _) = AgendaText.range(call.arg("dia")?.takeUnless { it.contains("semana") }, now)
            ?: return ToolResult.Failure("dia inválido", "Não entendi o dia")
        var cursor = day.atTime(WORK_START).let { if (day == now.toLocalDate() && now.isAfter(it)) now.withSecond(0).withNano(0) else it }
        val end = day.atTime(WORK_END)
        val free = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
        for (e in calendar.events(day.atStartOfDay(), day.plusDays(1).atStartOfDay()).filterNot { it.allDay }.sortedBy { it.start }) {
            if (e.start > cursor) free += cursor to minOf(e.start, end)
            if (e.end > cursor) cursor = e.end
        }
        if (end > cursor) free += cursor to end
        val slots = free.filter { Duration.between(it.first, it.second).toMinutes() >= 30 }
        val label = AgendaText.day(day, now.toLocalDate())
        if (slots.isEmpty()) return ToolResult.Answer("Nenhum horário livre $label entre 8h e 20h.")
        return ToolResult.Answer(
            "Livre $label: " + slots.joinToString(", ") { "das ${AgendaText.hour(it.first.toLocalTime())} às ${AgendaText.hour(it.second.toLocalTime())}" } + ".",
        )
    }

    private companion object {
        val WORK_START: LocalTime = LocalTime.of(8, 0)
        val WORK_END: LocalTime = LocalTime.of(20, 0)
    }
}

/** Cria um compromisso na agenda do celular. Classe 2: o cartão mostra título, dia e hora antes. */
internal class EventoCriar(private val calendar: CalendarPort) : BaseTool(
    name = "evento_criar",
    description = "Cria um compromisso na agenda do celular",
    risk = RiskClass.COMMIT,
    apps = emptySet(),
    params = mapOf(
        "titulo" to "nome do compromisso",
        "quando" to "dia e hora como a pessoa disse (amanhã às 15h) ou AAAA-MM-DDTHH:MM",
        "duracao_min" to "duração em minutos; sem nada, 60",
        "local" to "onde, se a pessoa disse",
    ),
    required = setOf("titulo", "quando"),
) {
    override fun summarize(call: ToolCall) = ConfirmationSummary(
        "Criar na agenda",
        listOfNotNull(
            "O quê" to call.arg("titulo").orEmpty(),
            "Quando" to call.arg("quando").orEmpty(),
            call.arg("duracao_min")?.let { "Duração" to "$it min" },
            call.arg("local")?.let { "Onde" to it },
        ),
    )

    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
        if (!calendar.canWrite()) return DeviceResult.NeedsPermission(Permission.CALENDAR).toToolResult("")
        val now = LocalDateTime.now(ctx.clock)
        val start = PtWhen.resolve(call.arg("quando")!!, now) ?: return ToolResult.Failure("quando inválido", "Não entendi o dia e a hora")
        val minutes = call.arg("duracao_min")?.filter(Char::isDigit)?.toLongOrNull()?.coerceIn(5, 24 * 60) ?: 60
        val end = start.plusMinutes(minutes)
        val clash = if (calendar.canRead()) calendar.events(start, end).filterNot { it.allDay } else emptyList()
        val r = calendar.insert(call.arg("titulo")!!, start, end, call.arg("local"))
        if (r != DeviceResult.Done) return r.toToolResult("")
        val warn = clash.firstOrNull()?.let { " Atenção: bate com ${it.title}." }.orEmpty()
        return ToolResult.Success("Pus ${call.arg("titulo")} na agenda, ${AgendaText.whenText(start, now)}.$warn")
    }
}

internal class LembreteCriar(private val reminders: ReminderPort) : BaseTool(
    name = "lembrete_criar",
    description = "Cria um lembrete que avisa por notificação na hora marcada",
    risk = RiskClass.PREPARE,
    apps = emptySet(),
    params = mapOf(
        "texto" to "do que lembrar",
        "quando" to "como a pessoa disse (amanhã às 9, daqui a 20 minutos) ou AAAA-MM-DDTHH:MM",
    ),
    required = setOf("texto", "quando"),
    title = "Lembrete",
) {
    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
        if (!reminders.canNotify()) return DeviceResult.NeedsPermission(Permission.NOTIFICATIONS).toToolResult("")
        val now = LocalDateTime.now(ctx.clock)
        val at = PtWhen.resolve(call.arg("quando")!!, now) ?: return ToolResult.Failure("quando inválido", "Não entendi quando")
        if (!at.isAfter(now)) return ToolResult.Failure("no passado", "Esse horário já passou")
        val texto = call.arg("texto")!!
        return reminders.schedule(texto, at).toToolResult("Vou te lembrar ${AgendaText.whenText(at, now)}: $texto")
    }
}

internal class LembretesVer(private val reminders: ReminderPort) : BaseTool(
    name = "lembretes_ver",
    description = "Lista os lembretes marcados",
    risk = RiskClass.READ,
    apps = emptySet(),
    title = "Lembretes",
) {
    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
        val now = LocalDateTime.now(ctx.clock)
        val list = reminders.pending().filter { it.at.isAfter(now) }.sortedBy { it.at }
        if (list.isEmpty()) return ToolResult.Answer("Nenhum lembrete marcado.")
        return ToolResult.Answer(list.joinToString("; ") { "${AgendaText.whenText(it.at, now)}, ${it.text}" } + ".")
    }
}

internal class LembreteCancelar(private val reminders: ReminderPort) : BaseTool(
    name = "lembrete_cancelar",
    description = "Cancela um lembrete marcado",
    risk = RiskClass.PREPARE,
    apps = emptySet(),
    params = mapOf("assunto" to "do que era o lembrete", "id" to "id do lembrete, quando já se sabe qual"),
    required = setOf("assunto"),
    title = "Cancelar lembrete",
) {
    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
        call.arg("id")?.let { id ->
            return if (reminders.cancel(id)) ToolResult.Success("Cancelei o lembrete.") else ToolResult.Failure("sem lembrete $id", "Esse lembrete já não existe")
        }
        val all = reminders.pending()
        val words = MemoryText.words(call.arg("assunto")!!)
        val found = all.filter { r -> MemoryText.words(r.text).any { it in words } }
        return when (found.size) {
            0 -> ToolResult.Failure("nenhum", "Não achei esse lembrete")
            1 -> if (reminders.cancel(found[0].id)) ToolResult.Success("Cancelei: ${found[0].text}") else ToolResult.Failure("sumiu", "Esse lembrete já não existe")
            else -> ToolResult.NeedsChoice(
                "Qual deles?",
                found.map { ToolResult.Choice(it.text, ToolCall(name, mapOf("assunto" to it.text, "id" to it.id))) },
            )
        }
    }
}
