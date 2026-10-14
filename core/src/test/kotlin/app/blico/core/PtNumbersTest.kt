package app.blico.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PtNumbersTest {
    @Test
    fun numeros() {
        assertEquals(7, PtNumbers.parse("sete"))
        assertEquals(25, PtNumbers.parse("vinte e cinco"))
        assertEquals(12, PtNumbers.parse("12"))
        assertNull(PtNumbers.parse("muitos"))
    }

    @Test
    fun horarios() {
        assertEquals(PtNumbers.Time(7, 0), PtNumbers.parseTime("7"))
        assertEquals(PtNumbers.Time(7, 30), PtNumbers.parseTime("7:30"))
        assertEquals(PtNumbers.Time(7, 30), PtNumbers.parseTime("7h30"))
        assertEquals(PtNumbers.Time(6, 30), PtNumbers.parseTime("seis e meia"))
        assertEquals(PtNumbers.Time(20, 0), PtNumbers.parseTime("8 da noite"))
        assertEquals(PtNumbers.Time(14, 15), PtNumbers.parseTime("2 e 15 da tarde"))
        assertEquals(PtNumbers.Time(12, 0), PtNumbers.parseTime("meio-dia"))
        assertNull(PtNumbers.parseTime("25:00"))
        assertNull(PtNumbers.parseTime("quando der"))
    }

    @Test
    fun duracoes() {
        assertEquals(300, PtNumbers.parseDuration("5 minutos"))
        assertEquals(1800, PtNumbers.parseDuration("meia hora"))
        assertEquals(5400, PtNumbers.parseDuration("uma hora e meia"))
        assertEquals(150, PtNumbers.parseDuration("2 minutos e 30 segundos"))
        assertEquals(30, PtNumbers.parseDuration("trinta segundos"))
        assertNull(PtNumbers.parseDuration("um tempinho"))
    }
}
