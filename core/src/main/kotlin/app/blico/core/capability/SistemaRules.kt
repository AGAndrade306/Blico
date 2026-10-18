package app.blico.core.capability

import app.blico.core.ToolCall

/** Frases do sistema do celular entendidas sem modelo. Ver [SpeechRule] para os trechos `{F}` e afins. */
internal object SistemaRules {
    private const val ON = "(?:liga|ligue|ligar|acende|acenda|acender|ativa|ative|ativar|habilita|habilitar)"
    private const val OFF = "(?:desliga|desligue|desligar|apaga|apague|apagar|desativa|desative|desativar|desabilita|desabilitar)"
    private const val UP = "(?:aumenta|aumente|aumentar|sobe|suba|subir|levanta|mais)"
    private const val DOWN = "(?:diminui|diminua|diminuir|abaixa|abaixe|abaixar|baixa|baixe|baixar|reduz|reduza|reduzir|menos)"
    private const val SET = "(?:coloca|coloque|põe|poe|bota|deixa|deixe|muda|mude|ajusta|ajuste)"
    private const val PHONE = "(?: (?:o )?(?:celular|telefone|aparelho))?"

    /** "do celular" no fim: "liga a lanterna do celular", "aumenta o volume do telefone". */
    private const val OF_PHONE = "(?: (?:do|no|deste|desse|neste|nesse) (?:celular|telefone|aparelho))?"

    private const val TORCH = "(?:a |o )?(?:lanterna|luz da câmera|luz da camera|flash)"

    /** Wi-Fi e afins, que o Android só deixa a pessoa ligar: abre o painel ou a tela. */
    private const val RADIOS = "wi-?fi|wi fi|bluetooth|nfc|modo avião|modo aviao|dados móveis|dados moveis|dados|localização|localizacao|gps|roteador|hotspot"

    private const val LEVEL = "(?<n>\\d{1,3})(?: ?%| por cento)?|(?<w>(?:o )?máximo|(?:o )?maximo|(?:o )?mínimo|(?:o )?minimo|metade|(?:o )?meio)"

    private const val SETTINGS_WORDS = "configurações|configuracoes|configuração|configuracao|ajustes|ajuste|definições|definicoes"

    private fun level(m: app.blico.core.RuleRouter.Match): String? = m.lower("n") ?: m.lower("w")?.removePrefix("o ")

    val all: List<SpeechRule> = listOf(
        // "liga a lanterna", "ligue a lanterna do celular", "acende o flash", "apaga a lanterna"
        SpeechRule("^$ON{F} $TORCH$OF_PHONE{F}$") { ToolCall("lanterna", mapOf("estado" to "ligar")) },
        SpeechRule("^$OFF{F} $TORCH$OF_PHONE{F}$") { ToolCall("lanterna", mapOf("estado" to "desligar")) },

        // "liga o wi-fi", "desativa o bluetooth", "ativa o modo avião"
        SpeechRule("^(?<acao>$ON|$OFF){F} (?:o |a |os )?(?<tela>$RADIOS)(?:$PHONE|$OF_PHONE)$") { m ->
            val acao = if (m.lower("acao")!!.let { it.startsWith("des") || it.startsWith("apag") }) "desligar" else "ligar"
            ToolCall("abrir_ajuste", mapOf("tela" to m["tela"]!!, "acao" to acao))
        },
        // "abre as configurações de bateria", "abre os ajustes", "vai nas configurações do bluetooth"
        SpeechRule("^(?:abre|abra|abrir|mostra|mostre|vai (?:em|nas|no|na|para as|pras|pra))(?: (?:as|a|os|o))?{F} (?:$SETTINGS_WORDS)(?: (?:de|do|da|dos|das) (?<tela>.+))?$") { m ->
            ToolCall("abrir_ajuste", mapOf("tela" to (m["tela"] ?: "configuracoes")))
        },
        // "abre o wi-fi", "abre o bluetooth", "abre a economia de bateria"
        SpeechRule("^(?:abre|abra|abrir){F} (?:o |a )?(?<tela>$RADIOS|economia de bateria|economia de energia|não perturbe|nao perturbe)$") { m ->
            ToolCall("abrir_ajuste", mapOf("tela" to m["tela"]!!))
        },

        // "aumenta o volume", "abaixa o som", "sobe o volume do toque"
        SpeechRule("^(?<acao>$UP|$DOWN){F} (?:o |um pouco o |mais o )?(?:volume|som)(?: (?:do|da) (?<fluxo>mídia|midia|música|musica|toque|alarme|despertador))?$OF_PHONE(?: um pouco)?$") { m ->
            val acao = if (m.lower("acao")!!.matches(Regex(UP))) "aumentar" else "diminuir"
            ToolCall("volume", mapOf("acao" to acao) + (m["fluxo"]?.let { mapOf("fluxo" to it) } ?: emptyMap()))
        },
        // "volume no máximo", "coloca o volume em 30", "volume do alarme pra 80%"
        SpeechRule("^(?:$SET{F} )?(?:o )?volume(?: (?:do|da) (?<fluxo>mídia|midia|música|musica|toque|alarme|despertador))? (?:no|na|em|pra|para|pro|a|ao) (?:$LEVEL)$") { m ->
            val n = level(m) ?: return@SpeechRule null
            ToolCall("volume", mapOf("acao" to n) + (m["fluxo"]?.let { mapOf("fluxo" to it) } ?: emptyMap()))
        },
        // "muta o som", "tira o som da música"
        SpeechRule("^(?:muta|mute|mutar|silencia|silencie|tira)(?: o)? (?:som|volume|áudio|audio)(?: da (?:música|musica|mídia|midia))?$") {
            ToolCall("volume", mapOf("acao" to "mudo"))
        },

        // "põe no silencioso", "modo silencioso", "silencia o celular", "deixa no vibrar"
        SpeechRule("^(?:(?:$SET|ativa|ative|liga|ligue){F}$PHONE (?:no |em |pro |para o |o )?)?(?:modo )?(?<modo>silencioso|vibrar|vibração|vibracao|vibratório|vibratorio)$") { m ->
            val modo = if (m.lower("modo")!!.startsWith("sil")) "silencioso" else "vibrar"
            ToolCall("modo_som", mapOf("modo" to modo))
        },
        SpeechRule("^(?:silencia|silencie|silenciar|cala|cale)$PHONE$") { ToolCall("modo_som", mapOf("modo" to "silencioso")) },
        // "liga o não perturbe", "desliga o não perturbe", "tira do silencioso"
        SpeechRule("^$ON{F} (?:o )?(?:modo )?(?:não|nao) perturbe$OF_PHONE$") { ToolCall("modo_som", mapOf("modo" to "nao_perturbe")) },
        SpeechRule("^$OFF{F} (?:o )?(?:modo )?(?:não|nao) perturbe$OF_PHONE$") { ToolCall("modo_som", mapOf("modo" to "normal")) },
        SpeechRule("^(?:tira|tire|sai|sair)$PHONE (?:do|de) (?:modo )?(?:silencioso|vibrar|não perturbe|nao perturbe)$") {
            ToolCall("modo_som", mapOf("modo" to "normal"))
        },
        SpeechRule("^(?:volta|volte|coloca|põe|poe|deixa)$PHONE (?:o som|no normal|pro normal|para o normal|no som normal)$") {
            ToolCall("modo_som", mapOf("modo" to "normal"))
        },

        // "aumenta o brilho", "brilho no máximo", "brilho automático"
        SpeechRule("^(?<acao>$UP|$DOWN){F} (?:o |um pouco o )?brilho(?: da tela)?$OF_PHONE(?: um pouco)?$") { m ->
            ToolCall("brilho", mapOf("acao" to if (m.lower("acao")!!.matches(Regex(UP))) "aumentar" else "diminuir"))
        },
        SpeechRule("^(?:$SET{F} )?(?:o )?brilho(?: da tela)? (?:no|na|em|pra|para|pro|a|ao) (?:$LEVEL)$") { m ->
            level(m)?.let { ToolCall("brilho", mapOf("acao" to it)) }
        },
        SpeechRule("^(?:$SET{F} |$ON{F} (?:o )?)?(?:o )?brilho(?: da tela)?(?: (?:no|em))? (?:automático|automatico)$") {
            ToolCall("brilho", mapOf("acao" to "automatico"))
        },

        // "quanto de bateria tem", "como tá a bateria", "bateria", "tá carregando?"
        SpeechRule("^(?:(?:quanto|quanta|qual|como)(?: (?:é|e|tá|ta|está|esta|tem|que tem|eu tenho|ainda tem|resta|o nível|o nivel|a carga))*(?: (?:de|da|do))? )?(?:a )?(?:bateria|carga)(?: (?:do|no) (?:celular|telefone))?(?: (?:tem|tá|ta|está|esta|resta|restante|ainda))?$") {
            ToolCall("estado_celular", mapOf("aspecto" to "bateria"))
        },
        SpeechRule("^(?:o celular |ele )?(?:tá|ta|está|esta) carregando$") { ToolCall("estado_celular", mapOf("aspecto" to "bateria")) },
        // "quanto espaço livre eu tenho", "como está o armazenamento", "a memória tá cheia?"
        SpeechRule("^(?:quanto|quanta|qual|como)(?: (?:é|e|tá|ta|está|esta|tem|eu tenho|ainda tem|sobrou|resta|de|o|a))* (?:espaço|espaco|armazenamento|memória|memoria)(?: (?:livre|sobrando|disponível|disponivel|sobrou|tem|eu tenho|resta|ainda))*(?: (?:no|do) (?:celular|telefone))?$") {
            ToolCall("estado_celular", mapOf("aspecto" to "armazenamento"))
        },
        SpeechRule("^(?:a )?(?:memória|memoria|armazenamento)(?: do celular)? (?:tá|ta|está|esta) (?:cheia|cheio|lotada|lotado)$") {
            ToolCall("estado_celular", mapOf("aspecto" to "armazenamento"))
        },
        // "tô conectado?", "tem internet?", "a internet caiu", "por que a internet não funciona"
        SpeechRule("^(?:(?:eu )?(?:tô|to|estou) (?:conectado|online|sem internet)|(?:o celular )?tem internet|(?:a )?internet (?:caiu|tá ruim|ta ruim|não funciona|nao funciona|não tá funcionando|nao ta funcionando|parou)|por ?que (?:a internet|o wi-?fi) (?:não|nao) (?:funciona|conecta|tá funcionando|ta funcionando)|(?:o )?wi-?fi (?:caiu|não conecta|nao conecta|não funciona|nao funciona))$") {
            ToolCall("estado_celular", mapOf("aspecto" to "rede"))
        },
        // "como está o celular", "status do celular"
        SpeechRule("^(?:como (?:tá|ta|está|esta) o celular|(?:status|estado|situação|situacao) do celular)$") {
            ToolCall("estado_celular", mapOf("aspecto" to "tudo"))
        },
    )
}
