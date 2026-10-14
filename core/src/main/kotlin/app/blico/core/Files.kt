package app.blico.core

import java.text.Normalizer
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** Tipos de arquivo que dá para pedir por voz: "acha o pdf", "fotos de ontem". */
enum class FileKind(val label: String, val mimes: List<String>, val extensions: List<String>) {
    IMAGEM("Imagem", listOf("image/"), listOf("jpg", "jpeg", "png", "webp", "heic", "gif")),
    VIDEO("Vídeo", listOf("video/"), listOf("mp4", "mov", "mkv", "3gp", "webm")),
    AUDIO("Áudio", listOf("audio/"), listOf("mp3", "m4a", "ogg", "opus", "wav", "aac", "amr")),
    PDF("PDF", listOf("application/pdf"), listOf("pdf")),
    DOCUMENTO(
        "Documento",
        listOf("application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml", "application/vnd.oasis.opendocument.text", "text/", "application/rtf"),
        listOf("doc", "docx", "odt", "txt", "rtf", "md"),
    ),
    PLANILHA(
        "Planilha",
        listOf("application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml", "application/vnd.oasis.opendocument.spreadsheet", "text/csv"),
        listOf("xls", "xlsx", "ods", "csv"),
    ),
    APRESENTACAO(
        "Apresentação",
        listOf("application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml", "application/vnd.oasis.opendocument.presentation"),
        listOf("ppt", "pptx", "odp"),
    ),
    OUTRO("Arquivo", emptyList(), emptyList()),
    ;

    companion object {
        /** Pelo tipo MIME e, se ele não ajudar, pela extensão. CSV é planilha, não texto. */
        fun of(mime: String?, name: String): FileKind {
            val ext = name.substringAfterLast('.', "").lowercase()
            entries.firstOrNull { ext.isNotEmpty() && ext in it.extensions }?.let { return it }
            val m = mime.orEmpty().lowercase()
            return entries.firstOrNull { k -> k.mimes.any { m.startsWith(it) } } ?: OUTRO
        }

        /** Palavra falada para tipo: "fotos" → IMAGEM. Null para "arquivo", que é qualquer tipo. */
        fun fromWord(word: String): FileKind? = when (FileSearch.simplify(word).trimEnd('s')) {
            "foto", "imagen", "imagem", "print", "captura de tela", "capturas de tela", "screenshot" -> IMAGEM
            "video" -> VIDEO
            "audio", "gravacao", "gravacoe", "musica" -> AUDIO
            "pdf" -> PDF
            "documento", "doc", "texto" -> DOCUMENTO
            "planilha" -> PLANILHA
            "apresentacao", "apresentacoe", "slide" -> APRESENTACAO
            else -> null
        }
    }
}

/** Um arquivo do aparelho. [id] é opaco: só o índice que o devolveu sabe abrir. */
data class FileItem(
    val id: String,
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val folder: String,
) {
    val kind: FileKind get() = FileKind.of(mime, name)
}

data class FileQuery(
    val terms: List<String>,
    val kind: FileKind? = null,
    val modifiedAfterMs: Long? = null,
    val limit: Int = 20,
)

/** Busca nos arquivos do aparelho. No app é o MediaStore; nos testes, uma lista. */
interface FileIndex {
    /** Sem a permissão de arquivos, a busca não enxerga nada. */
    fun hasAccess(): Boolean

    /** Candidatos para [query]; pode devolver a mais, porque [FileSearch.rank] filtra e ordena. */
    suspend fun search(query: FileQuery): List<FileItem>

    suspend fun get(id: String): FileItem?

    object None : FileIndex {
        override fun hasAccess() = false

        override suspend fun search(query: FileQuery) = emptyList<FileItem>()

        override suspend fun get(id: String): FileItem? = null
    }
}

/** A parte da busca que não depende do Android: termos, períodos e ordem dos resultados. */
object FileSearch {
    private val STOPWORDS = setOf(
        "o", "a", "os", "as", "de", "do", "da", "dos", "das", "um", "uma", "meu", "minha", "meus", "minhas",
        "com", "que", "e", "no", "na", "nos", "nas", "sobre", "chamado", "chamada", "arquivo", "arquivos",
    )

    /** Minúsculas, sem acento e com `_ - .` virando espaço, para "Relatório_Final.pdf" bater com "relatorio final". */
    fun simplify(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[_\\-.]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Palavras que importam no pedido, sem artigos e preposições. Mantém os acentos:
     * o índice do aparelho usa o acento como curinga, e o [rank] compara sem eles.
     */
    fun terms(text: String?): List<String> =
        text.orEmpty().lowercase().split(Regex("[\\s_\\-.]+")).filter { it.isNotBlank() && simplify(it) !in STOPWORDS }

    /** Fica com os que têm todos os termos no nome, do mais recente para o mais antigo. */
    fun rank(items: List<FileItem>, query: FileQuery): List<FileItem> {
        val terms = query.terms.map(::simplify).filter { it.isNotEmpty() }
        return items.asSequence()
            .filter { query.kind == null || it.kind == query.kind }
            .filter { query.modifiedAfterMs == null || it.modifiedMs >= query.modifiedAfterMs }
            .filter { item -> simplify(item.name).let { name -> terms.all { it in name } } }
            .distinctBy { it.id }
            .sortedByDescending { it.modifiedMs }
            .take(query.limit)
            .toList()
    }

    enum class Period(val words: List<String>) {
        HOJE(listOf("hoje")),
        ONTEM(listOf("ontem")),
        SEMANA(listOf("semana", "esta semana", "essa semana", "da semana")),
        MES(listOf("mes", "este mes", "esse mes", "do mes")),
        ANO(listOf("ano", "este ano", "esse ano", "do ano")),
        ;

        /** Início do período no fuso do aparelho. Semana e mês são os últimos 7 e 31 dias. */
        fun since(clock: Clock): Long {
            val zone: ZoneId = clock.zone
            val today = LocalDate.now(clock)
            val start = when (this) {
                HOJE -> today
                ONTEM -> today.minusDays(1)
                SEMANA -> today.minusDays(7)
                MES -> today.minusDays(31)
                ANO -> today.minusDays(365)
            }
            return start.atStartOfDay(zone).toInstant().toEpochMilli()
        }

        companion object {
            fun parse(word: String?): Period? = word?.let { w -> simplify(w).let { s -> entries.firstOrNull { s in it.words } } }
        }
    }

    private val PERIOD_TAIL = Regex("^(?<rest>.*?)\\s*(?:\\b(?:de|da|do|desta|dessa|deste|desse|esta|essa|este|esse)\\s+)?(?<p>hoje|ontem|semana|m[eê]s|ano)$")

    /** Separa o período do fim do termo: "contrato de ontem" → ("contrato", ONTEM). */
    fun splitPeriod(text: String?): Pair<String?, Period?> {
        val t = text?.trim()?.lowercase().orEmpty()
        val m = PERIOD_TAIL.matchEntire(t) ?: return text?.trim()?.takeIf { it.isNotEmpty() } to null
        val rest = text!!.trim().substring(0, m.groups["rest"]!!.value.length).trim()
        return rest.takeIf { it.isNotEmpty() } to Period.parse(m.groups["p"]!!.value)
    }
}

/** "2,3 MB", "850 KB". */
fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(java.util.Locale.forLanguageTag("pt-BR"), bytes / 1e9)
    bytes >= 1_000_000 -> "%.1f MB".format(java.util.Locale.forLanguageTag("pt-BR"), bytes / 1e6)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}
