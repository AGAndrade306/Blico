package app.blico.core

import app.blico.core.capability.AgendaCapability
import app.blico.core.capability.CalendarEvent
import app.blico.core.capability.CalendarPort
import app.blico.core.capability.Capabilities
import app.blico.core.capability.Reminder
import app.blico.core.capability.ReminderPort
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeCalendar : CalendarPort {
    var read = true
    var write = true
    val events = mutableListOf<CalendarEvent>()

    override fun canRead() = read
    override fun canWrite() = write

    override suspend fun events(from: LocalDateTime, to: LocalDateTime) =
        events.filter { it.start < to && it.end > from }.sortedBy { it.start }

    override suspend fun insert(title: String, start: LocalDateTime, end: LocalDateTime, location: String?): DeviceResult {
        events += CalendarEvent(title, start, end, location = location)
        return DeviceResult.Done
    }
}

class FakeReminders : ReminderPort {
    var notify = true
    val list = mutableListOf<Reminder>()

    override fun canNotify() = notify

    override suspend fun schedule(text: String, at: LocalDateTime): DeviceResult {
        list += Reminder("r${list.size + 1}", text, at)
        return DeviceResult.Done
    }

    override suspend fun pending() = list.toList()

    override suspend fun cancel(id: String) = list.removeIf { it.id == id }
}

class AgendaTest {
    private val zone = ZoneId.of("America/Sao_Paulo")

    // Sábado, 18 de outubro de 2026, 10h.
    private val now = LocalDateTime.of(2026, 10, 18, 10, 0)
    private val calendar = FakeCalendar()
    private val reminders = FakeReminders()
    private val caps = Capabilities(listOf(AgendaCapability(calendar, reminders)))
    private val router = RuleRouter(caps.rules())
    private val registry = ToolRegistry(caps.tools())
    private val assistant = Assistant(listOf(router), registry)
    private val ctx = ExecContext("t", object : DeviceGateway {
        override suspend fun perform(action: DeviceAction) = DeviceResult.Done
    }, object : ContactDirectory {
        override suspend fun find(query: String) = emptyList<Contact>()
    }, object : PlaceBook {
        override fun resolve(alias: String): String? = null
    }, clock = Clock.fixed(now.atZone(zone).toInstant(), zone))

    private fun assertCall(text: String, tool: String, vararg args: Pair<String, String>) =
        assertEquals(ToolCall(tool, mapOf(*args)), router.parse(text), "frase: $text")

    @Test
    fun `frases de lembrete`() {
        assertCall("me lembra de tirar o bolo do forno daqui a 20 minutos", "lembrete_criar", "texto" to "tirar o bolo do forno", "quando" to "daqui a 20 minutos")
        assertCall("me lembra amanhã às 9 de pagar a conta de luz", "lembrete_criar", "texto" to "pagar a conta de luz", "quando" to "amanhã às 9")
        assertCall("Blico, me avisa às 18h pra ligar pro João", "lembrete_criar", "texto" to "ligar pro João", "quando" to "às 18h")
        assertCall("cria um lembrete pra sexta às 10 de ligar pro banco", "lembrete_criar", "texto" to "ligar pro banco", "quando" to "sexta às 10")
        assertCall("quais são meus lembretes", "lembretes_ver")
        assertCall("cancela o lembrete do bolo", "lembrete_cancelar", "assunto" to "bolo")
        // Sem quando, não é lembrete com hora: fica para o modelo.
        assertNull(router.parse("me lembra de comprar pão"))
    }

    @Test
    fun `frases de agenda`() {
        assertCall("o que eu tenho hoje", "agenda_dia", "dia" to "hoje")
        assertCall("o que tenho amanhã na agenda", "agenda_dia", "dia" to "amanhã")
        assertCall("como está minha agenda", "agenda_dia")
        assertCall("minha agenda da semana", "agenda_dia", "dia" to "semana")
        assertCall("tenho compromisso sexta?", "agenda_dia", "dia" to "sexta")
        assertCall("quando eu tô livre amanhã", "horarios_livres", "dia" to "amanhã")
        assertCall("marca reunião com o João amanhã às 15h", "evento_criar", "titulo" to "Reunião com o João", "quando" to "amanhã às 15h")
        assertCall("agenda dentista sexta às 10", "evento_criar", "titulo" to "Dentista", "quando" to "sexta às 10")
        assertCall("coloca na agenda almoço com a Ana dia 25 ao meio-dia", "evento_criar", "titulo" to "Almoço com a Ana", "quando" to "dia 25 ao meio-dia")
        // "marca 10 minutos" continua sendo timer nas regras de base.
        assertCall("marca 10 minutos", "timer", "segundos" to "600")
    }

    @Test
    fun `lembrete marca e fala quando`() = runTest {
        val out = assistant.handle("me lembra amanhã às 9 de pagar a conta de luz", ctx)
        assertEquals("Vou te lembrar amanhã às 9h: pagar a conta de luz", out.spoken)
        assertEquals(LocalDateTime.of(2026, 10, 19, 9, 0), reminders.list.single().at)

        assertEquals("Amanhã às 9h, pagar a conta de luz.", assistant.handle("meus lembretes", ctx).spoken.replaceFirstChar { it.uppercase() })
        assertIs<Outcome.Done>(assistant.handle("cancela o lembrete da conta de luz", ctx))
        assertTrue(reminders.list.isEmpty())

        reminders.notify = false
        assertTrue(assistant.handle("me lembra daqui a 5 minutos de olhar o forno", ctx).spoken.contains("Notificações"))
    }

    @Test
    fun `agenda do dia com conflito e horarios livres`() = runTest {
        calendar.events += CalendarEvent("Dentista", LocalDateTime.of(2026, 10, 19, 9, 0), LocalDateTime.of(2026, 10, 19, 10, 0))
        calendar.events += CalendarEvent("Reunião", LocalDateTime.of(2026, 10, 19, 9, 30), LocalDateTime.of(2026, 10, 19, 11, 0))
        calendar.events += CalendarEvent("Almoço", LocalDateTime.of(2026, 10, 19, 12, 0), LocalDateTime.of(2026, 10, 19, 13, 0))
        val day = assistant.handle("o que eu tenho amanhã", ctx)
        assertIs<Outcome.Answer>(day)
        assertEquals("Amanhã: 9h, Dentista; 9h30, Reunião; 12h, Almoço. Conflito: Dentista e Reunião.", day.spoken)
        assertEquals("Livre amanhã: das 8h às 9h, das 11h às 12h, das 13h às 20h.", assistant.handle("quando eu tô livre amanhã", ctx).spoken)
        assertEquals("Nada na agenda hoje.", assistant.handle("o que eu tenho hoje", ctx).spoken)
        assertTrue(assistant.readsPrivate("agenda_dia"))

        calendar.read = false
        assertTrue(assistant.handle("o que eu tenho hoje", ctx).spoken.contains("Agenda"))
    }

    @Test
    fun `criar evento passa pelo Confirmar e avisa de conflito`() = runTest {
        calendar.events += CalendarEvent("Dentista", LocalDateTime.of(2026, 10, 19, 15, 0), LocalDateTime.of(2026, 10, 19, 16, 0))
        val out = assistant.handle("marca reunião com o João amanhã às 15h", ctx)
        assertIs<Outcome.Confirm>(out)
        assertEquals(listOf("O quê" to "Reunião com o João", "Quando" to "amanhã às 15h"), out.summary.fields)
        assertEquals(1, calendar.events.size)
        val done = assistant.execute(out.call, ctx, confirmed = true)
        assertEquals("Pus Reunião com o João na agenda, amanhã às 15h. Atenção: bate com Dentista.", done.spoken)
        assertEquals(2, calendar.events.size)
    }
}
