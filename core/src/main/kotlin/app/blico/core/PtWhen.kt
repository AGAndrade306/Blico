package app.blico.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeParseException

/**
 * Quando, falado em português: "amanhã às 9", "daqui a 20 minutos", "sexta às 15h", "dia 25 às 14h", "hoje à noite".
 * [extract] separa o quando do resto do pedido sem precisar da hora atual (as regras são puras);
 * [resolve] transforma em data e hora com o relógio do pedido.
 */
object PtWhen {
    data class Split(val rest: String, val phrase: String)

    private const val N = "\\d{1,2}|${"(?:vinte|trinta|quarenta|cinquenta) e (?:um|uma|dois|duas|três|tres|quatro|cinco|seis|sete|oito|nove)"}|" +
        "zero|uma|um|duas|dois|três|tres|quatro|cinco|seis|sete|oito|nove|dez|onze|doze|treze|catorze|quatorze|quinze|dezesseis|dezessete|dezoito|dezenove|vinte|trinta|quarenta|cinquenta"

    private const val TIME = "(?:meio-dia|meio dia|meia-noite|meia noite|(?:$N)(?:(?::|h| horas? e | e |h e )(?:meia|$N))?(?:\\s?h(?:oras?)?)?(?: (?:da manhã|da manha|da tarde|da noite|da madrugada))?)"
    private const val DURATION = "(?:(?:$N|meia) (?:segundos?|minutos?|horas?)(?: e meia| e meio)?)(?: e (?:$N) (?:segundos?|minutos?))?"
    private const val WEEKDAY = "segunda|terça|terca|quarta|quinta|sexta|sábado|sabado|domingo"
    private const val MONTH = "janeiro|fevereiro|março|marco|abril|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro"

    private val RELATIVE = Regex("(?:^|\\s)(?:daqui a|daqui|em|dentro de)\\s(?<d>$DURATION)(?=\\s|$)", RegexOption.IGNORE_CASE)
    private val AT = Regex("(?:^|\\s)(?:às|as|à|ao|pras|pelas|lá pelas)\\s(?<t>$TIME)(?=\\s|$|,)", RegexOption.IGNORE_CASE)
    private val BARE_TIME = Regex("(?:^|\\s)(?<t>\\d{1,2}(?:h\\d{2}|h|:\\d{2}))(?=\\s|$|,)", RegexOption.IGNORE_CASE)
    private val DAY = Regex("(?:^|\\s)(?<day>hoje|amanhã|amanha|depois de amanhã|depois de amanha)(?=\\s|$|,)", RegexOption.IGNORE_CASE)
    private val WEEK = Regex("(?:^|\\s)(?:(?:na|no|nessa|nesta|neste|nesse|na próxima|na proxima|no próximo|no proximo|próxima|proxima|próximo|proximo)\\s)?(?<wd>$WEEKDAY)(?:-feira| feira)?(?=\\s|$|,)", RegexOption.IGNORE_CASE)
    private val DATE = Regex("(?:^|\\s)(?:no )?dia\\s(?<d>\\d{1,2})(?:\\sde\\s(?<m>$MONTH))?(?=\\s|$|,)", RegexOption.IGNORE_CASE)
    private val PERIOD = Regex("(?:^|\\s)(?<p>de manhã|de manha|pela manhã|pela manha|cedo|à tarde|a tarde|de tarde|à noite|a noite|de noite|hoje à noite|hoje a noite)(?=\\s|$|,)", RegexOption.IGNORE_CASE)

    /** Tira o quando de [text]. Nulo quando não há hora, dia ou prazo. */
    fun extract(text: String): Split? {
        var rest = " ${text.trim()} "
        val found = mutableListOf<Pair<Int, String>>()
        fun take(r: Regex) {
            val m = r.find(rest) ?: return
            found += m.range.first to m.value.trim()
            rest = rest.removeRange(m.range).let { it.substring(0, m.range.first) + " " + it.substring(m.range.first) }
        }
        take(RELATIVE)
        if (found.isEmpty()) {
            take(DAY)
            take(WEEK)
            take(DATE)
            take(AT)
            if (found.none { AT.containsMatchIn(" ${it.second} ") }) take(BARE_TIME)
            take(PERIOD)
        }
        if (found.isEmpty()) return null
        val phrase = found.sortedBy { it.first }.joinToString(" ") { it.second }
        val clean = rest.replace(Regex("\\s+"), " ").trim()
            .replace(Regex("^(?:de|que|pra|para|,)\\s+"), "")
            .replace(Regex("\\s+(?:de|que|pra|para|,)$"), "")
            .trim(',', ' ')
        return Split(clean, phrase)
    }

    /**
     * Data e hora de [phrase] a partir de [now]. Aceita também AAAA-MM-DDTHH:MM, que é como os modelos mandam.
     * Hora sem dia: hoje se ainda não passou, senão amanhã. Dia sem hora: 9h (ou o período dito).
     */
    fun resolve(phrase: String, now: LocalDateTime): LocalDateTime? {
        val p = " ${phrase.trim().lowercase()} "
        try {
            return LocalDateTime.parse(phrase.trim())
        } catch (_: DateTimeParseException) {
            // não é ISO; segue
        }
        RELATIVE.find(p)?.let { m -> return PtNumbers.parseDuration(m.groups["d"]!!.value)?.let { now.plusSeconds(it.toLong()) } }

        val today = now.toLocalDate()
        var day: LocalDate? = DAY.find(p)?.groups?.get("day")?.value?.let {
            when (it) {
                "hoje" -> today
                "amanhã", "amanha" -> today.plusDays(1)
                else -> today.plusDays(2)
            }
        }
        WEEK.find(p)?.groups?.get("wd")?.value?.let { wd ->
            val target = weekday(wd)
            var d = today.plusDays(1)
            while (d.dayOfWeek != target) d = d.plusDays(1)
            if (target == today.dayOfWeek && !p.contains("próxim") && !p.contains("proxim")) d = today
            day = d
        }
        DATE.find(p)?.let { m ->
            val dom = m.groups["d"]!!.value.toInt()
            val month = m.groups["m"]?.value?.let(::month)
            var d = runCatching { LocalDate.of(today.year, month ?: today.monthValue, dom) }.getOrNull() ?: return null
            if (d.isBefore(today)) d = if (month == null) d.plusMonths(1) else d.plusYears(1)
            day = d
        }
        val period = PERIOD.find(p)?.groups?.get("p")?.value
        val rawTime = (AT.find(p)?.groups?.get("t") ?: BARE_TIME.find(p)?.groups?.get("t"))?.value
        // "da manhã", "da noite", "18h", "18:30": a pessoa já disse qual metade do dia.
        val explicit = rawTime != null && (rawTime.contains(" da ") || (rawTime.takeWhile(Char::isDigit).toIntOrNull() ?: 0) >= 13)
        var time: LocalTime? = rawTime?.let { raw ->
            PtNumbers.parseTime(raw.replace(Regex("^(\\d{1,2})h(\\d{2})$"), "$1:$2").removeSuffix("h"))?.let { LocalTime.of(it.hour, it.minute) }
        }
        // "às 3 da tarde" já vem certo; "às 3 à tarde" e "às 8 da noite" sem o "da" também.
        if (time != null && period != null && time.hour in 1..11 && (period.contains("tarde") || period.contains("noite"))) time = time.plusHours(12)
        if (time == null && period != null) {
            time = when {
                period.contains("manh") || period == "cedo" -> LocalTime.of(9, 0)
                period.contains("tarde") -> LocalTime.of(15, 0)
                else -> LocalTime.of(20, 0)
            }
        }
        if (time == null && day == null) return null
        val at = LocalDateTime.of(day ?: today, time ?: LocalTime.of(9, 0))
        // Hora que já passou hoje, sem dia dito: amanhã. Hora sem "da tarde" de manhã cedo pode ser à tarde: 3 vira 15h.
        if (day == null && !at.isAfter(now)) {
            val pm = at.plusHours(12)
            return if (time!!.hour in 1..11 && pm.isAfter(now) && pm.toLocalDate() == today && period == null && !explicit) pm else at.plusDays(1)
        }
        return at
    }

    private fun weekday(s: String): DayOfWeek = when (s) {
        "segunda" -> DayOfWeek.MONDAY
        "terça", "terca" -> DayOfWeek.TUESDAY
        "quarta" -> DayOfWeek.WEDNESDAY
        "quinta" -> DayOfWeek.THURSDAY
        "sexta" -> DayOfWeek.FRIDAY
        "sábado", "sabado" -> DayOfWeek.SATURDAY
        else -> DayOfWeek.SUNDAY
    }

    private fun month(s: String): Int = listOf(
        "janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro",
    ).indexOf(s.replace("marco", "março")) + 1
}
