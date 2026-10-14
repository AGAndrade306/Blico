package app.blico.core

/** Números e horários falados em português, como o reconhecedor costuma devolver. */
object PtNumbers {
    private val WORDS = mapOf(
        "zero" to 0, "um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "três" to 3, "tres" to 3,
        "quatro" to 4, "cinco" to 5, "seis" to 6, "sete" to 7, "oito" to 8, "nove" to 9, "dez" to 10,
        "onze" to 11, "doze" to 12, "treze" to 13, "catorze" to 14, "quatorze" to 14, "quinze" to 15,
        "dezesseis" to 16, "dezessete" to 17, "dezoito" to 18, "dezenove" to 19, "vinte" to 20,
        "trinta" to 30, "quarenta" to 40, "quarenta e cinco" to 45, "cinquenta" to 50,
    )

    /** "7", "sete", "vinte e cinco" → número. */
    fun parse(text: String): Int? {
        val t = text.trim().lowercase()
        t.toIntOrNull()?.let { return it }
        WORDS[t]?.let { return it }
        val parts = t.split(" e ")
        if (parts.size == 2) {
            val tens = WORDS[parts[0]] ?: return null
            val units = WORDS[parts[1]] ?: return null
            if (tens >= 20 && tens % 10 == 0 && units in 1..9) return tens + units
        }
        return null
    }

    /** Palavras de número aceitas nas expressões regulares. */
    val WORD_PATTERN: String = WORDS.keys.sortedByDescending { it.length }.joinToString("|")
    val NUMBER_PATTERN: String = "\\d+|(?:(?:vinte|trinta|quarenta|cinquenta) e (?:$WORD_PATTERN))|$WORD_PATTERN"

    data class Time(val hour: Int, val minute: Int)

    private val TIME = Regex(
        "^(?<h>$NUMBER_PATTERN)(?:(?::|h| horas? e | e |h e |\\s?horas?\\s?)(?<m>meia|$NUMBER_PATTERN))?" +
            "(?:\\s?h(?:oras?)?)?(?: (?<p>da manhã|da tarde|da noite|da madrugada))?$",
    )

    /** "7", "7:30", "7h30", "sete e meia", "8 da noite", "meio-dia" → hora. */
    fun parseTime(text: String): Time? {
        val t = text.trim().lowercase().removePrefix("às ").removePrefix("as ").removePrefix("pras ").trim()
        when (t) {
            "meio-dia", "meio dia" -> return Time(12, 0)
            "meia-noite", "meia noite" -> return Time(0, 0)
        }
        val m = TIME.matchEntire(t) ?: return null
        var hour = parse(m.groups["h"]!!.value) ?: return null
        val minute = m.groups["m"]?.value?.let { if (it == "meia") 30 else parse(it) } ?: 0
        when (m.groups["p"]?.value) {
            "da tarde", "da noite" -> if (hour in 1..11) hour += 12
            "da manhã", "da madrugada" -> if (hour == 12) hour = 0
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return Time(hour, minute)
    }

    private val DURATION_PART = Regex("(?<n>$NUMBER_PATTERN|meia) (?<u>segundos?|minutos?|horas?)(?<half> e meia| e meio)?")

    /** "5 minutos", "meia hora", "uma hora e meia", "2 minutos e 30 segundos" → segundos. */
    fun parseDuration(text: String): Int? {
        val t = text.trim().lowercase()
        var total = 0
        var matched = false
        for (m in DURATION_PART.findAll(t)) {
            val nText = m.groups["n"]!!.value
            val unit = when {
                m.groups["u"]!!.value.startsWith("segundo") -> 1
                m.groups["u"]!!.value.startsWith("minuto") -> 60
                else -> 3600
            }
            val n = if (nText == "meia") 0.5 else (parse(nText) ?: return null).toDouble()
            val half = if (m.groups["half"] != null) 0.5 else 0.0
            total += ((n + half) * unit).toInt()
            matched = true
        }
        return total.takeIf { matched && it > 0 }
    }
}
