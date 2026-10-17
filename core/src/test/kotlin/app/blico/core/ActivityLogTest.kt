package app.blico.core

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActivityLogTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 10, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun soAcoesConcluidasEntram() {
        val call = ToolCall("timer", mapOf("minutos" to "10"))
        assertEquals("Timer de 10 minutos", ActivityLog.entryFor(Outcome.Done("Timer de 10 minutos", call), 1)?.text)
        assertNull(ActivityLog.entryFor(Outcome.Answer("Amanhã chove"), 1))
        assertNull(ActivityLog.entryFor(Outcome.Failed("Não consegui", "x"), 1))
        assertEquals("timer", ActivityLog.entryFor(Outcome.Done("  ", call), 1)?.text)
    }

    @Test
    fun maisNovoPrimeiroEComLimite() {
        var log = emptyList<ActivityLog.Entry>()
        repeat(40) { log = ActivityLog.add(log, ActivityLog.Entry("a$it", it.toLong())) }
        assertEquals(ActivityLog.MAX, log.size)
        assertEquals("a39", log.first().text)
    }

    @Test
    fun contaSoAsDeHojeNoFusoDoCelular() {
        val log = listOf(
            ActivityLog.Entry("hoje cedo", at(17, 0)),
            ActivityLog.Entry("hoje", at(17, 9)),
            ActivityLog.Entry("ontem à noite", at(16, 23)),
        )
        assertEquals(2, ActivityLog.countToday(log, at(17, 12), zone))
    }

    @Test
    fun guardaEVoltaIgualSemQuebrarComTextoEstranho() {
        val log = listOf(ActivityLog.Entry("Mandei\tpara o\nJoão", 5), ActivityLog.Entry("Alarme às 7:30", 4))
        val back = ActivityLog.decode(ActivityLog.encode(log))
        assertEquals(listOf("Mandei para o João", "Alarme às 7:30"), back.map { it.text })
        assertEquals(listOf(5L, 4L), back.map { it.at })
        assertEquals(1, ActivityLog.decode("lixo\n12\tok\n\tsem hora\nabc\tsem número").size)
        assertEquals(emptyList(), ActivityLog.decode(null))
    }

    @Test
    fun textoLongoECortado() {
        val e = ActivityLog.entryFor(Outcome.Done("x".repeat(200), ToolCall("busca")), 1)!!
        assertEquals(80, e.text.length)
        assertEquals('…', e.text.last())
    }
}
