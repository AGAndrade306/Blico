package app.blico.core

/**
 * Troca dados pessoais por apelidos antes de um texto sair do aparelho, e desfaz na volta.
 *
 * Entidades conhecidas (contatos, lugares) vêm da memória; telefones e e-mails são achados por regex.
 */
class Redactor(private val known: Map<String, String> = emptyMap()) {

    /** [mapping] vai de apelido para o valor original. */
    data class Redacted(val text: String, val mapping: Map<String, String>)

    fun redact(text: String): Redacted {
        val mapping = linkedMapOf<String, String>()
        val reverse = hashMapOf<String, String>()
        var out = text

        // Nomes mais longos primeiro, para "João Silva" não virar "contato_1 Silva".
        for ((value, alias) in known.entries.sortedByDescending { it.key.length }) {
            val regex = Regex("(?<![\\p{L}\\d])${Regex.escape(value)}(?![\\p{L}\\d])", RegexOption.IGNORE_CASE)
            if (regex.containsMatchIn(out)) {
                out = regex.replace(out, Regex.escapeReplacement(alias))
                mapping[alias] = value
                reverse[value.lowercase()] = alias
            }
        }

        out = replaceAll(out, EMAIL, "email", mapping, reverse)
        out = replaceAll(out, PHONE, "telefone", mapping, reverse)
        return Redacted(out, mapping)
    }

    fun restore(text: String, mapping: Map<String, String>): String {
        var out = text
        for ((alias, value) in mapping.entries.sortedByDescending { it.key.length }) {
            out = out.replace(alias, value)
        }
        return out
    }

    private fun replaceAll(
        text: String,
        regex: Regex,
        prefix: String,
        mapping: MutableMap<String, String>,
        reverse: MutableMap<String, String>,
    ): String = regex.replace(text) { match ->
        val value = match.value
        reverse.getOrPut(value.lowercase()) {
            val alias = "${prefix}_${mapping.keys.count { it.startsWith(prefix) } + 1}"
            mapping[alias] = value
            alias
        }
    }

    private companion object {
        val EMAIL = Regex("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+")

        // Telefones brasileiros: +55, DDD opcional entre parênteses, 8 ou 9 dígitos com hífen ou espaço opcional.
        val PHONE = Regex("(?<!\\d)(\\+?55\\s?)?(\\(?\\d{2}\\)?\\s?)?9?\\d{4}[-\\s]?\\d{4}(?!\\d)")
    }
}
