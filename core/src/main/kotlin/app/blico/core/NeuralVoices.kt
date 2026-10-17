package app.blico.core

/**
 * Vozes neurais que rodam no aparelho com o sherpa-onnx. Cada voz vem de um pacote baixado uma vez;
 * as três vozes do Kokoro dividem o mesmo pacote.
 */
object NeuralVoices {
    enum class Family { KOKORO, PIPER }

    /** Um arquivo `.tar.bz2` dos modelos de TTS do sherpa-onnx, preso à release `tts-models`. */
    data class Pack(
        val id: String,
        val family: Family,
        /** Pasta de topo dentro do arquivo, que é também o nome do arquivo sem extensão. */
        val root: String,
        val approxBytes: Long,
        /** Arquivo do modelo dentro de [root]. */
        val model: String,
    ) {
        val url: String get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$root.tar.bz2"
    }

    data class Voice(
        val id: String,
        val name: String,
        val detail: String,
        val pack: Pack,
        /** Índice da voz dentro do modelo (o Kokoro tem 54; o Piper, uma). */
        val speaker: Int = 0,
    )

    val KOKORO = Pack("kokoro", Family.KOKORO, "kokoro-int8-multi-lang-v1_0", 132_303_094, "model.int8.onnx")

    private fun piper(name: String, quality: String, bytes: Long) =
        Pack("piper-$name", Family.PIPER, "vits-piper-pt_BR-$name-$quality", bytes, "pt_BR-$name-$quality.onnx")

    val all: List<Voice> = listOf(
        Voice("kokoro-dora", "Dora", "Feminina · Kokoro, a mais natural", KOKORO, speaker = 42),
        Voice("kokoro-alex", "Alex", "Masculina · Kokoro, a mais natural", KOKORO, speaker = 43),
        Voice("kokoro-santa", "Santa", "Masculina, mais grave · Kokoro", KOKORO, speaker = 44),
        Voice("piper-dii", "Dii", "Feminina · Piper, leve e instantânea · uso pessoal", piper("dii", "high", 67_238_016)),
        Voice("piper-faber", "Faber", "Masculina · Piper, leve e instantânea", piper("faber", "medium", 67_183_065)),
        Voice("piper-cadu", "Cadu", "Masculina, grave · Piper, leve e instantânea", piper("cadu", "medium", 67_207_562)),
        Voice("piper-jeff", "Jeff", "Masculina · Piper, leve e instantânea", piper("jeff", "medium", 67_207_052)),
        Voice("piper-miro", "Miro", "Masculina, grave · Piper, leve e instantânea · uso pessoal", piper("miro", "high", 67_207_707)),
    )

    val packs: List<Pack> get() = all.map { it.pack }.distinctBy { it.id }

    fun byId(id: String?): Voice? = all.firstOrNull { it.id == id }

    /**
     * Caminho relativo seguro para uma entrada do arquivo, sem a pasta de topo, ou nulo para pular.
     * Pula o que o português não usa (léxicos e regras do chinês e do inglês) e recusa
     * qualquer caminho que tente sair da pasta da voz.
     */
    fun extractPath(pack: Pack, entry: String): String? {
        val parts = entry.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." } || entry.startsWith("/")) return null
        if (parts.first() != pack.root) return null
        val rel = parts.drop(1)
        if (rel.isEmpty()) return null
        val name = rel.last()
        val skip = rel.first() == "dict" || name.endsWith(".fst") || name.startsWith("lexicon") ||
            name == "README.md" || name.endsWith(".py")
        return if (skip) null else rel.joinToString("/")
    }

    /**
     * Quebra o texto em frases para falar a primeira enquanto as outras são geradas.
     * Frases muito curtas vão junto com a seguinte, e trechos longos sem pontuação são cortados entre palavras.
     */
    fun chunks(text: String, maxChars: Int = 220, minChars: Int = 24): List<String> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return emptyList()
        val sentences = Regex("(?<=[.!?…;:])\\s+").split(clean).flatMap { s ->
            if (s.length <= maxChars) listOf(s) else splitLong(s, maxChars)
        }
        val out = mutableListOf<String>()
        var buffer = ""
        for (s in sentences) {
            buffer = if (buffer.isEmpty()) s else "$buffer $s"
            if (buffer.length >= minChars) {
                out += buffer
                buffer = ""
            }
        }
        if (buffer.isNotEmpty()) {
            if (out.isNotEmpty() && out.last().length + buffer.length < maxChars) out[out.lastIndex] = out.last() + " " + buffer else out += buffer
        }
        return out
    }

    private fun splitLong(s: String, maxChars: Int): List<String> {
        val out = mutableListOf<String>()
        var line = ""
        for (piece in s.split(' ')) {
            if (line.isNotEmpty() && line.length + 1 + piece.length > maxChars) {
                out += line
                line = piece
            } else {
                line = if (line.isEmpty()) piece else "$line $piece"
            }
        }
        if (line.isNotEmpty()) out += line
        return out
    }
}
