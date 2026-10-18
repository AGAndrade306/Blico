package app.blico.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Uma resposta curta para o card, com a fonte de onde veio. */
data class WebSnippet(val text: String, val source: String, val url: String? = null)

/**
 * Respostas rápidas para o card, sem abrir o navegador. No app são o Gemma do celular e a
 * resposta instantânea do DuckDuckGo; nos testes, fakes.
 */
interface Knowledge {
    /**
     * O que o modelo do celular responde em uma ou duas frases. [reference] é um trecho da web
     * que entra como dado não confiável. Nulo quando ele não sabe ou não está pronto.
     */
    suspend fun answer(question: String, reference: String? = null): String?

    /** Resposta instantânea ou resumo de verbete do DuckDuckGo. Nulo quando não há. */
    suspend fun lookup(query: String): WebSnippet?

    object None : Knowledge {
        override suspend fun answer(question: String, reference: String?): String? = null
        override suspend fun lookup(query: String): WebSnippet? = null
    }
}

/** Separa a pergunta pontual ("qual a capital da Dinamarca") da pesquisa ampla ("receitas de bolo"). */
object WebQuery {
    enum class Kind {
        /** Cabe numa frase: responde no card e não abre o navegador. */
        POINTED,

        /** Pede várias fontes: abre o navegador e deixa no card um resumo raso ou um direcionamento. */
        BROAD,
    }

    // Comparado sem acento e em minúsculas.
    private val BROAD = Regex(
        "\\b(?:receitas?|dicas?|ideias?|melhores|melhor|opcoes|comparar|comparacao|versus|vs|review|reviews|avaliacoes|tutorial|" +
            "passo a passo|como fazer|como faco|como montar|como aprender|historia|noticias|novidades|lugares|restaurantes|hoteis|" +
            "viagem|roteiro|lista de|exemplos|cursos?|onde comprar|sobre)\\b",
    )
    private val POINTED_START = Regex(
        "^(?:qual|quais|quanto|quanta|quantos|quantas|quem|quando|onde (?:fica|e|esta|nasceu)|que (?:horas|dia|ano|mes)|em que|" +
            "o que (?:e|significa|quer dizer)|oque e|como se (?:diz|fala|escreve|pronuncia)|e verdade|existe|por que|porque|pq)\\b",
    )
    private val POINTED_ANYWHERE = Regex(
        "\\b(?:capital d[aeo]s?|significado d[aeo]|traducao d[aeo]|cotacao d[aeo]|quanto e|quantos anos|altura d[aeo]|" +
            "populacao d[aeo]|fuso horario|que horas|data d[aeo]|aniversario d[aeo]|placar d[aeo]|resultado d[aeo] jogo)\\b",
    )
    private val MATH = Regex("\\d\\s*(?:[-+*/x×÷^%]|mais|menos|vezes|dividido por|elevado a|por cento de)\\s*\\d")

    fun classify(query: String): Kind {
        val q = FileSearch.simplify(query)
        if (MATH.containsMatchIn(q)) return Kind.POINTED
        if (BROAD.containsMatchIn(q)) return Kind.BROAD
        if (POINTED_START.containsMatchIn(q) || POINTED_ANYWHERE.containsMatchIn(q)) return Kind.POINTED
        return Kind.BROAD
    }

    /** O texto é uma pergunta? Usado quando nada entendeu o pedido, para tentar a busca em vez de desistir. */
    fun isQuestion(text: String): Boolean {
        val q = FileSearch.simplify(text)
        return text.trim().endsWith("?") || POINTED_START.containsMatchIn(q) || POINTED_ANYWHERE.containsMatchIn(q) || MATH.containsMatchIn(q)
    }
}

/** A API de respostas instantâneas do DuckDuckGo: sem conta, sem chave e sem perfil de quem pergunta. */
object DuckDuckGo {
    private const val MAX = 320
    private val json = Json { ignoreUnknownKeys = true }

    fun instantUrl(query: String): String =
        "https://api.duckduckgo.com/?q=" + java.net.URLEncoder.encode(query, Charsets.UTF_8).replace("+", "%20") +
            "&format=json&no_html=1&skip_disambig=1&no_redirect=1&kl=br-pt"

    /** Fica com a resposta direta (conta, conversão), senão com o resumo do verbete, senão com a definição. */
    fun parseInstant(body: String): WebSnippet? {
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        text(o, "Answer")?.let { return WebSnippet(it, "DuckDuckGo") }
        text(o, "AbstractText")?.let { return WebSnippet(it, text(o, "AbstractSource") ?: "DuckDuckGo", text(o, "AbstractURL")) }
        text(o, "Definition")?.let { return WebSnippet(it, text(o, "DefinitionSource") ?: "DuckDuckGo", text(o, "DefinitionURL")) }
        return null
    }

    /** A página de resultados sem JavaScript do DuckDuckGo, para quando a resposta instantânea vem vazia. */
    fun htmlUrl(query: String): String =
        "https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(query, Charsets.UTF_8).replace("+", "%20") + "&kl=br-pt"

    /**
     * O trecho do primeiro resultado que não é anúncio, com o site de onde veio. A resposta instantânea
     * quase nunca cobre pergunta em frase ("qual a capital da Mongólia"); o trecho do primeiro resultado
     * costuma ter a resposta.
     */
    fun parseResults(body: String): WebSnippet? {
        for (block in body.split(RESULT_START).drop(1)) {
            if ("result--ad" in block.substringBefore('>')) continue
            val snippet = SNIPPET.find(block)?.groupValues?.get(1)?.let(::plain)?.takeIf { it.isNotEmpty() } ?: continue
            val site = SITE.find(block)?.groupValues?.get(1)?.let(::plain)?.substringBefore('/')?.removePrefix("www.")
            val url = SITE.find(block)?.groupValues?.get(1)?.let(::plain)?.let { if (it.startsWith("http")) it else "https://$it" }
            return WebSnippet(clip(snippet), site?.takeIf { it.isNotEmpty() } ?: "DuckDuckGo", url)
        }
        return null
    }

    private val RESULT_START = Regex("<div class=\"result ")
    private val SNIPPET = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
    private val SITE = Regex("class=\"result__url\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)

    /** Sem tags e com as entidades de HTML mais comuns trocadas. */
    private fun plain(html: String): String = html.replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.let { c -> String(Character.toChars(c)) } ?: "" }
        .replace(Regex("\\s+"), " ").trim()

    private fun clip(text: String): String =
        if (text.length <= MAX) text else text.take(MAX - 1).substringBeforeLast(' ').trimEnd(',', ';', ' ') + "…"

    /** Só texto; a API às vezes manda objeto ou lista no lugar, e isso fica de fora. */
    private fun text(o: JsonObject, key: String): String? {
        val v = o[key] as? JsonPrimitive ?: return null
        if (!v.isString) return null
        val clean = v.content.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return null
        return if (clean.length <= MAX) clean else clean.take(MAX - 1).substringBeforeLast(' ').trimEnd(',', ';', ' ') + "…"
    }
}

/**
 * [Knowledge] montado com o modelo do celular e um GET da web.
 * A consulta só sai para o DuckDuckGo se o [Redactor] não achar nada pessoal nela
 * (contato, endereço, telefone, e-mail); se achar, fica só o modelo do celular.
 */
class DeviceKnowledge(
    private val model: LlmProvider,
    private val fetch: suspend (url: String) -> String?,
    private val knownEntities: suspend () -> Map<String, String> = { emptyMap() },
    private val modelTimeoutMs: Long = 12_000,
    private val webTimeoutMs: Long = 5_000,
) : Knowledge {
    override suspend fun answer(question: String, reference: String?): String? {
        val text = if (reference == null) {
            question
        } else {
            "Responda em português do Brasil, em no máximo duas frases curtas, usando o trecho abaixo só como referência.\n" +
                UntrustedContent.wrap("duckduckgo", reference) + "\n" + question
        }
        return guarded(modelTimeoutMs) { model.chooseTools(LlmRequest(text, emptyList())).answer }
    }

    override suspend fun lookup(query: String): WebSnippet? {
        return guarded(webTimeoutMs) {
            // Sem conseguir ler a agenda, a consulta não sai: o erro cai no guarded e vira nulo.
            val personal = Redactor(knownEntities()).redact(query).mapping.isNotEmpty()
            if (personal) {
                null
            } else {
                fetch(DuckDuckGo.instantUrl(query))?.let(DuckDuckGo::parseInstant)
                    ?: fetch(DuckDuckGo.htmlUrl(query))?.let(DuckDuckGo::parseResults)
            }
        }
    }

    private suspend fun <T> guarded(timeoutMs: Long, block: suspend () -> T?): T? = try {
        withTimeoutOrNull(timeoutMs) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
