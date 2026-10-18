package app.blico.core

/**
 * Troca dados pessoais por apelidos antes de um texto sair do aparelho, e desfaz na volta.
 *
 * Entidades conhecidas (contatos, lugares) vêm da memória. Por padrão são achados: e-mails, chaves PIX
 * aleatórias, CNPJ e CPF (com dígito verificador quando vêm sem pontuação), cartões (com Luhn), CEP e telefones.
 */
class Redactor(private val known: Map<String, String> = emptyMap()) {

    /** [mapping] vai de apelido para o valor original. */
    data class Redacted(val text: String, val mapping: Map<String, String>)

    fun redact(text: String): Redacted {
        val all = redactAll(listOf(text))
        return Redacted(all.texts.single(), all.mapping)
    }

    /** Vários textos com os mesmos apelidos: o mesmo telefone vira o mesmo telefone_N em todos. */
    data class RedactedAll(val texts: List<String>, val mapping: Map<String, String>)

    fun redactAll(texts: List<String>): RedactedAll {
        val mapping = linkedMapOf<String, String>()
        val reverse = hashMapOf<String, String>()
        return RedactedAll(texts.map { redactInto(it, mapping, reverse) }, mapping)
    }

    private fun redactInto(text: String, mapping: MutableMap<String, String>, reverse: MutableMap<String, String>): String {
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

        // Do mais específico ao mais genérico: um CPF ou cartão nunca pode virar "telefone" pela metade.
        out = replaceAll(out, PIX_RANDOM, "chave_pix", mapping, reverse)
        out = replaceAll(out, EMAIL, "email", mapping, reverse)
        out = replaceAll(out, CNPJ, "cnpj", mapping, reverse) { it.length > 14 || validCnpj(it) }
        out = replaceAll(out, CPF, "cpf", mapping, reverse) { it.length > 11 || validCpf(it) }
        out = replaceAll(out, CARD, "cartao", mapping, reverse) { luhn(it.filter(Char::isDigit)) }
        out = replaceAll(out, CEP, "cep", mapping, reverse)
        return replaceAll(out, PHONE, "telefone", mapping, reverse)
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
        accept: (String) -> Boolean = { true },
    ): String = regex.replace(text) { match ->
        val value = match.value
        if (!accept(value)) return@replace value
        reverse.getOrPut(value.lowercase()) {
            val alias = "${prefix}_${mapping.keys.count { it.startsWith("${prefix}_") } + 1}"
            mapping[alias] = value
            alias
        }
    }

    private companion object {
        /** Chave PIX aleatória: um UUID. */
        val PIX_RANDOM = Regex("(?i)(?<![0-9a-f-])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![0-9a-f-])")

        /** Com pontuação (00.000.000/0000-00) ou 14 dígitos seguidos. */
        val CNPJ = Regex("(?<![\\w./-])(\\d{2}\\.\\d{3}\\.\\d{3}/\\d{4}-\\d{2}|\\d{14})(?!\\w|[./-]\\d)")

        /** Com pontuação (000.000.000-00) ou 11 dígitos seguidos. */
        val CPF = Regex("(?<![\\w.-])(\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}|\\d{11})(?!\\w|[.-]\\d)")

        /** 13 a 19 dígitos, em blocos separados por espaço ou hífen ou juntos. */
        val CARD = Regex("(?<!\\w)\\d(?:[ -]?\\d){12,18}(?!\\d)")

        /** Só no formato com hífen: oito dígitos seguidos se confundem com telefone. */
        val CEP = Regex("(?<![\\w-])\\d{5}-\\d{3}(?!\\w|-\\d)")

        val EMAIL = Regex("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+")

        // Telefones brasileiros: +55, DDD opcional entre parênteses, 8 ou 9 dígitos com hífen ou espaço opcional.
        val PHONE = Regex("(?<![\\d_])(\\+?55\\s?)?(\\(?\\d{2}\\)?\\s?)?9?\\d{4}[-\\s]?\\d{4}(?!\\d)")

        // Apelidos terminam em número (telefone_1): os padrões não começam colados a letra ou _.
        fun validCpf(raw: String): Boolean {
            val d = raw.filter(Char::isDigit).map { it - '0' }
            if (d.size != 11 || d.distinct().size == 1) return false
            fun check(n: Int) = (0 until n).sumOf { d[it] * (n + 1 - it) }.let { (it * 10 % 11) % 10 }
            return check(9) == d[9] && check(10) == d[10]
        }

        fun validCnpj(raw: String): Boolean {
            val d = raw.filter(Char::isDigit).map { it - '0' }
            if (d.size != 14 || d.distinct().size == 1) return false
            fun check(n: Int): Int {
                val weights = (n - 7 downTo 2).toList() + (9 downTo 2).toList()
                val r = (0 until n).sumOf { d[it] * weights[it] } % 11
                return if (r < 2) 0 else 11 - r
            }
            return check(12) == d[12] && check(13) == d[13]
        }

        fun luhn(digits: String): Boolean {
            if (digits.length !in 13..19 || digits.toSet().size == 1) return false
            val sum = digits.reversed().mapIndexed { i, c ->
                val n = c - '0'
                if (i % 2 == 1) (n * 2).let { if (it > 9) it - 9 else it } else n
            }.sum()
            return sum % 10 == 0
        }
    }
}
