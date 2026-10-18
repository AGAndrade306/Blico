package app.blico.core

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PtWhenTest {
    // Sábado, 18 de outubro de 2026, 10h.
    private val now = LocalDateTime.of(2026, 10, 18, 10, 0)

    private fun at(phrase: String) = PtWhen.resolve(phrase, now)

    @Test
    fun `separa o quando do resto`() {
        assertEquals(PtWhen.Split("tirar o bolo do forno", "daqui a 20 minutos"), PtWhen.extract("daqui a 20 minutos de tirar o bolo do forno"))
        assertEquals(PtWhen.Split("pagar a conta de luz", "amanhã às 9"), PtWhen.extract("pagar a conta de luz amanhã às 9"))
        assertEquals(PtWhen.Split("ligar pro médico", "às 18h"), PtWhen.extract("ligar pro médico às 18h"))
        assertEquals(PtWhen.Split("reunião com o João", "sexta às 15h"), PtWhen.extract("reunião com o João sexta às 15h"))
        assertEquals(PtWhen.Split("dentista", "dia 25 às 14h"), PtWhen.extract("dentista dia 25 às 14h"))
        assertEquals(PtWhen.Split("tomar o remédio", "hoje à noite"), PtWhen.extract("tomar o remédio hoje à noite"))
        assertNull(PtWhen.extract("comprar pão"))
        assertNull(PtWhen.extract("10 minutos"))
    }

    @Test
    fun `resolve com o relogio do pedido`() {
        assertEquals(LocalDateTime.of(2026, 10, 18, 10, 20), at("daqui a 20 minutos"))
        assertEquals(LocalDateTime.of(2026, 10, 18, 11, 30), at("em uma hora e meia"))
        assertEquals(LocalDateTime.of(2026, 10, 19, 9, 0), at("amanhã às 9"))
        assertEquals(LocalDateTime.of(2026, 10, 18, 18, 0), at("às 18h"))
        assertEquals(LocalDateTime.of(2026, 10, 18, 18, 30), at("às 18:30"))
        assertEquals(LocalDateTime.of(2026, 10, 18, 19, 30), at("às sete e meia da noite"))
        assertEquals(LocalDateTime.of(2026, 10, 23, 15, 0), at("sexta às 15h"))
        assertEquals(LocalDateTime.of(2026, 10, 25, 14, 0), at("dia 25 às 14h"))
        assertEquals(LocalDateTime.of(2026, 11, 5, 9, 0), at("dia 5"))
        assertEquals(LocalDateTime.of(2026, 10, 18, 20, 0), at("hoje à noite"))
        assertEquals(LocalDateTime.of(2026, 10, 19, 9, 0), at("amanhã de manhã"))
        assertEquals(LocalDateTime.of(2026, 10, 19, 15, 0), at("amanhã à tarde"))
        assertEquals(LocalDateTime.of(2026, 10, 19, 15, 0), at("amanhã às 3 da tarde"))
        assertEquals(LocalDateTime.of(2026, 10, 30, 10, 0), at("2026-10-30T10:00"))
    }

    @Test
    fun `hora que ja passou`() {
        // Às 10h, "às 9" e "às 3" já passaram de manhã: são as 21h e as 15h de hoje. "às 9:30 da manhã" é amanhã.
        assertEquals(LocalDateTime.of(2026, 10, 18, 21, 0), at("às 9"))
        assertEquals(LocalDateTime.of(2026, 10, 19, 9, 30), at("às 9:30 da manhã"))
        assertEquals(LocalDateTime.of(2026, 10, 18, 15, 0), at("às 3"))
        assertNull(at("qualquer hora"))
    }
}
