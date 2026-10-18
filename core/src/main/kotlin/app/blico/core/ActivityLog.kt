package app.blico.core

import java.time.Instant
import java.time.ZoneId

/**
 * O que o Blico fez, para a tela inicial. Fica só no celular, guardado como texto simples:
 * uma linha por ação, com o horário, a frase que o Blico falou e de onde ela veio (o selo).
 */
object ActivityLog {
    data class Entry(val text: String, val at: Long, val origin: Origin? = null)

    const val MAX = 30
    private const val MAX_TEXT = 80

    /** Só ações concluídas entram; perguntas, falhas e cartões ainda abertos ficam de fora. */
    fun entryFor(outcome: Outcome, now: Long, origin: Origin? = null): Entry? {
        if (outcome !is Outcome.Done) return null
        val text = clean(outcome.spoken).ifEmpty { outcome.call.tool }
        return Entry(text, now, origin)
    }

    /** O mais novo primeiro, até [max] linhas. */
    fun add(entries: List<Entry>, entry: Entry, max: Int = MAX): List<Entry> = (listOf(entry) + entries).take(max)

    fun countToday(entries: List<Entry>, now: Long, zone: ZoneId): Int {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return entries.count { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() == today }
    }

    fun encode(entries: List<Entry>): String = entries.joinToString("\n") { e ->
        "${e.at}\t${clean(e.text)}" + (e.origin?.let { "\t" + clean(it.name) } ?: "")
    }

    /** Linhas estragadas são puladas, nunca derrubam a tela. Linhas antigas, sem selo, continuam valendo. */
    fun decode(stored: String?): List<Entry> = stored.orEmpty().lineSequence().mapNotNull { line ->
        val parts = line.split('\t', limit = 3)
        if (parts.size < 2 || parts[0].isEmpty()) return@mapNotNull null
        val at = parts[0].toLongOrNull() ?: return@mapNotNull null
        val text = parts[1]
        val origin = parts.getOrNull(2)?.takeIf { it.isNotBlank() }?.let(Origin::named)
        if (text.isBlank()) null else Entry(text, at, origin)
    }.take(MAX).toList()

    private fun clean(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= MAX_TEXT) flat else flat.take(MAX_TEXT - 1).trimEnd() + "…"
    }
}
