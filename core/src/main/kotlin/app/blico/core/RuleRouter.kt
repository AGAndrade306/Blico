package app.blico.core

import app.blico.core.capability.SpeechRule

/**
 * Primeiro nível do cérebro: entende os comandos mais comuns com regras, sem modelo nenhum.
 * Rápido, previsível e offline. O que não casar aqui vai para o modelo (local ou nuvem).
 * [extra] são as regras das funcionalidades ([app.blico.core.capability.Capability]); rodam antes das de base.
 */
class RuleRouter(extra: List<SpeechRule> = emptyList()) : LlmProvider {
    override val id = "regras"

    private val rules: List<Rule> = extra.map { Rule(expand(it.pattern), it.build) } + RULES

    override suspend fun chooseTools(request: LlmRequest): LlmDecision {
        val call = parse(request.text)
        return if (call == null) LlmDecision(emptyList(), 0.0) else LlmDecision(listOf(call), 1.0)
    }

    fun parse(raw: String): ToolCall? {
        val text = Text.of(raw) ?: return null
        for (rule in rules) {
            val m = rule.regex.matchEntire(text.lower) ?: continue
            rule.build(Match(m, text.original))?.let { return it }
        }
        return null
    }

    /** As regras casam no texto em minúsculas, mas os valores saem do original, com maiúsculas. */
    private class Text(val original: String, val lower: String) {
        companion object {
            fun of(raw: String): Text? {
                var original = raw.trim().replace(Regex("\\s+"), " ")
                var lower = original.lowercase()
                if (lower.length != original.length) original = lower // raro: caixa que muda o tamanho
                LEAD.find(lower)?.let { original = original.substring(it.range.last + 1); lower = lower.substring(it.range.last + 1) }
                TAIL.find(lower)?.let { original = original.substring(0, it.range.first); lower = lower.substring(0, it.range.first) }
                return Text(original.trim(), lower.trim()).takeIf { it.lower.isNotEmpty() && it.lower.length == it.original.length }
            }
        }
    }

    class Match internal constructor(private val m: MatchResult, private val original: String) {
        /** Valor do grupo no texto original, sem espaços nem muletas ("pra mim", "aí") nas pontas. */
        operator fun get(name: String): String? = raw(name)?.let(::stripFillers)?.takeIf { it.isNotEmpty() }

        /** Valor do grupo como foi dito, só sem espaços nas pontas. Para o texto de uma mensagem, que é do usuário. */
        fun raw(name: String): String? =
            m.groups[name]?.range?.let { original.substring(it.first, it.last + 1).trim() }?.takeIf { it.isNotEmpty() }

        /** Valor do grupo em minúsculas, para números e horários. */
        fun lower(name: String): String? = m.groups[name]?.value?.let(::stripFillers)

        private fun stripFillers(value: String): String {
            var v = value.trim()
            while (true) {
                val next = v.replace(EDGE_FILLER, "").trim()
                if (next == v) return v
                v = next
            }
        }
    }

    private class Rule(pattern: String, val build: (Match) -> ToolCall?) {
        val regex = Regex(pattern)
    }

    private companion object {
        private const val PRO = "(?:pro|pra|para o|para a|para|ao|à|a|o)"
        private const val OPT_ART = "(?:o |a |os |as )?"
        private const val SEND = "(?:manda|mande|mandar|envia|envie|enviar|escreve|escreva|escrever|fala|avisa|avise|avisar|diz|diga|dizer)"
        private const val MSG = "(?: (?:uma |um )?(?:mensagem|msg|zap|whats|whatsapp|recado))?(?: no (?:whats|whatsapp|zap))?"
        private const val SEP = "(?:,? (?:dizendo|falando|avisando|perguntando)(?: que)?|,? que|:|,)"
        private const val FILE_WORDS = "arquivos?|pdfs?|documentos?|fotos?|imagens?|imagem|vídeos?|videos?|planilhas?|áudios?|audios?|gravaç(?:ão|ões)|apresentaç(?:ão|ões)|prints?|capturas? de tela"
        private const val TO_PLACE = "(?:pra|para|pro|até|ate|ao|à)"

        /**
         * Muletas da fala que não fazem parte do pedido: "pesquisa pra mim X", "liga aí pra mãe", "toca rapidinho Djavan".
         * Entram nas regras logo depois do verbo, onde nunca são conteúdo.
         */
        private const val FILLER = "pra mim|para mim|pra gente|para a gente|pra nós|para nós|aí|por favor|por gentileza|rapidinho|rapidão|agora|já"
        private const val F = "(?:,? (?:$FILLER))*"

        /** Troca os trechos de [SpeechRule] pelos das regras de base. */
        private fun expand(pattern: String): String = pattern
            .replace("{F}", F)
            .replace("{PRO}", PRO)
            .replace("{TO}", TO_PLACE)
            .replace("{ART}", OPT_ART)

        private fun g(m: Match, name: String) = m[name]

        val RULES = listOf(
            // "manda pro João que eu chego em dez minutos"
            Rule("^$SEND$F$MSG$F $PRO (?<contato>.+?)$SEP (?<texto>.+)$") { m ->
                ToolCall("whatsapp_preparar", mapOf("contato" to g(m, "contato")!!, "texto" to m.raw("texto")!!))
            },
            // "manda mensagem pro João"
            Rule("^$SEND$F$MSG$F $PRO (?<contato>.+)$") { m ->
                ToolCall("whatsapp_preparar", mapOf("contato" to g(m, "contato")!!))
            },
            // "liga pra mãe", "ligar para o João"
            Rule("^(?:liga|ligue|ligar|telefona|telefonar|chama|chamar)$F $PRO (?<contato>.+)$") { m ->
                ToolCall("ligar", mapOf("contato" to g(m, "contato")!!))
            },
            // "pede um uber pra casa", "chama um uber para o shopping"
            Rule("^(?:pede|peça|pedir|chama|chame|chamar|me chama)$F(?: um| uma)? uber(?: $TO_PLACE $OPT_ART(?<destino>.+))?$") { m ->
                g(m, "destino")?.let { ToolCall("uber_preparar", mapOf("destino" to it)) }
                    ?: ToolCall("abrir_app", mapOf("nome" to "Uber"))
            },
            Rule("^(?:pede|peça|pedir|chama|chame|chamar)$F(?: um| uma)? (?:99|noventa e nove|nove nove)(?: $TO_PLACE $OPT_ART(?<destino>.+))?$") { m ->
                ToolCall("noventa_e_nove_abrir", g(m, "destino")?.let { mapOf("destino" to it) } ?: emptyMap())
            },
            // "pede comida no ifood", "abre o ifood"
            Rule("^(?:pede|peça|pedir|quero pedir|vou pedir)$F(?: (?<busca>.+?))? (?:no|pelo) ifood$") { m ->
                ToolCall("ifood_abrir", g(m, "busca")?.let { mapOf("busca" to it) } ?: emptyMap())
            },
            // "me leva pra casa", "navega até o aeroporto", "como chego no shopping"
            Rule("^(?:me leva|me levar|leva|navega|navegar|vamos|rota|traça rota|traça uma rota|me guia|me guiar)$F(?: $TO_PLACE)? $OPT_ART(?<destino>.+)$") { m ->
                ToolCall("navegar", mapOf("destino" to g(m, "destino")!!))
            },
            Rule("^como (?:chego|eu chego|faço pra chegar|vou)$F(?: em| no| na| ao| à| até| pra| para)? $OPT_ART(?<destino>.+)$") { m ->
                ToolCall("navegar", mapOf("destino" to g(m, "destino")!!))
            },
            // "me acorda às 7", "alarme para 6 e meia", "coloca um despertador pras 7h30"
            Rule("^(?:me acorda|me acorde|me acordar|acorda|desperta|(?:(?:cria|coloca|põe|poe|bota)$F )?(?:um )?(?:alarme|despertador))$F(?: (?:às|as|pras|para as|para às|pra|para))? (?<hora>.+)$") { m ->
                PtNumbers.parseTime(m.lower("hora")!!)?.let { t ->
                    ToolCall("alarme", mapOf("hora" to "${t.hour}", "minuto" to "${t.minute}"))
                }
            },
            // "timer de 5 minutos", "marca 10 minutos", "cronômetro de meia hora"
            Rule("^(?:(?:(?:cria|coloca|põe|poe|bota|liga)$F )?(?:um )?(?:timer|temporizador|cronômetro|cronometro)$F(?: de| para| pra)?|marca|conta)$F (?<dur>.+)$") { m ->
                PtNumbers.parseDuration(m.lower("dur")!!)?.let { ToolCall("timer", mapOf("segundos" to "$it")) }
            },
            // Depois de alarme e timer, porque "coloca" também serve para eles. "toca Djavan", "toca meu mix no spotify", "coloca uma música do Legião"
            Rule("^(?:toca|toque|tocar|bota|coloca|põe|poe)$F(?: pra tocar)?$F(?: (?:um pouco|algo|alguma coisa) de)? (?<busca>.+?)(?: no spotify)?$") { m ->
                ToolCall("spotify_tocar", mapOf("busca" to g(m, "busca")!!))
            },
            // Antes da busca na web: "acha o pdf do contrato", "fotos de ontem", "cadê a planilha de gastos"
            Rule("^(?:(?:procura|procure|busca|busque|acha|ache|encontra|encontre|localiza|mostra|mostre|me mostra|me mostre|cadê|cade|onde (?:está|esta|tá|ta|fica))$F(?: (?:o|a|os|as|meu|minha|meus|minhas|aquele|aquela))? )?(?<tipo>$FILE_WORDS)(?: (?:de|do|da|dos|das|com|chamado|chamada|sobre|que tem))?(?: (?<termo>.+?))?(?: no (?:celular|telefone|aparelho))?$") { m ->
                val (termo, periodo) = FileSearch.splitPeriod(g(m, "termo"))
                val kind = FileKind.fromWord(m.lower("tipo")!!)
                ToolCall(
                    "buscar_arquivos",
                    buildMap {
                        termo?.let { put("termo", it) }
                        kind?.let { put("tipo", it.name.lowercase()) }
                        periodo?.let { put("periodo", it.name.lowercase()) }
                    },
                )
            },
            // "pesquisa preço do dólar", "procura na internet receita de bolo", "pesquisa pra mim qual é a capital da Dinamarca"
            Rule("^(?:pesquisa|pesquise|pesquisar|procura|procure|procurar|busca|busque|buscar|googla|google|(?:dá|da|dê|de|faz|faça|fazer) uma (?:pesquisa|pesquisada|busca|buscada|procurada))$F(?: na internet| no google)?$F(?: por| sobre| em)? (?<q>.+?)(?: na internet| no google)?$") { m ->
                ToolCall("buscar_web", mapOf("consulta" to g(m, "q")!!))
            },
            // "abre o WhatsApp", "abrir câmera"
            Rule("^(?:abre|abra|abrir|inicia|inicie|iniciar|entra no|entra na)$F(?: o| a)? (?<nome>.+)$") { m ->
                ToolCall("abrir_app", mapOf("nome" to g(m, "nome")!!))
            },
        )

        private val LEAD = Regex(
            "^(?:(?:ei|oi|olá|ola|ok|hey),? )?(?:blico,? )?(?:(?:me )?(?:faz|faça|faca) (?:um|uma) favor,? )?(?:por favor,? )?" +
                "(?:(?:será que |sera que )?(?:você |voce |tu )?(?:pode|poderia|podia|consegue|conseguiria) |dá pra |da pra |tem como |(?:eu )?(?:quero|queria) que (?:você |voce )?)?",
        )
        private val TAIL = Regex("(?:,? por favor)?[.!?]*$")

        /** Muletas que sobram nas pontas de um valor: "qual é a capital da Dinamarca pra mim". */
        private val EDGE_FILLER = Regex("^(?:(?:pra mim|para mim|aí|por favor|rapidinho),? )+|,?(?: (?:pra mim|para mim|pra gente|aí|por favor|rapidinho))+$", RegexOption.IGNORE_CASE)
    }
}
