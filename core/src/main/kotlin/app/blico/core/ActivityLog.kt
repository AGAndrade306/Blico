package app.blico.core

import java.time.Instant
import java.time.ZoneId

/**
 * O que o Blico fez, para a tela inicial. Fica só no celular, guardado como texto simples:
 * uma linha por ação, com o horário e a frase que o Blico falou.
 */
object ActivityLog {
    data class Entry(val text: String, val at: Long)

    const val MAX = 30
    private const val MAX_TEXT = 80

    /** Só ações concluídas entram; perguntas, falhas e cartões ainda abertos ficam de fora. */
    fun entryFor(outcome: Outcome, now: Long): Entry? {
        if (outcome !is Outcome.Done) return null
        val text = clean(outcome.spoken).ifEmpty { outcome.call.tool }
        return Entry(text, now)
    }

    /** O mais novo primeiro, até [max] linhas. */
    fun add(entries: List<Entry>, entry: Entry, max: Int = MAX): List<Entry> = (listOf(entry) + entries).take(max)

    fun countToday(entries: List<Entry>, now: Long, zone: ZoneId): Int {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return entries.count { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() == today }
    }

    fun encode(entries: List<Entry>): String = entries.joinToString("\n") { "${it.at}\t${clean(it.text)}" }

    /** Linhas estragadas são puladas, nunca derrubam a tela. */
    fun decode(stored: String?): List<Entry> = stored.orEmpty().lineSequence().mapNotNull { line ->
        val tab = line.indexOf('\t')
        if (tab <= 0) return@mapNotNull null
        val at = line.substring(0, tab).toLongOrNull() ?: return@mapNotNull null
        val text = line.substring(tab + 1)
        if (text.isBlank()) null else Entry(text, at)
    }.take(MAX).toList()

    private fun clean(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= MAX_TEXT) flat else flat.take(MAX_TEXT - 1).trimEnd() + "…"
    }
}
