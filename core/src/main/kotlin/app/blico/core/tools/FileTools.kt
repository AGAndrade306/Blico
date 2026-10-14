package app.blico.core.tools

import app.blico.core.ConfirmationSummary
import app.blico.core.DeviceAction
import app.blico.core.ExecContext
import app.blico.core.FileKind
import app.blico.core.FileQuery
import app.blico.core.FileSearch
import app.blico.core.RiskClass
import app.blico.core.ToolCall
import app.blico.core.ToolResult

fun fileTools(): List<app.blico.core.Tool> = listOf(BuscarArquivos, ArquivoAbrir, ArquivoCompartilhar, ArquivoRenomear)

private const val SEM_ACESSO = "Preciso do acesso aos arquivos. Abra o Blico e libere em Arquivos."

internal object BuscarArquivos : BaseTool(
    name = "buscar_arquivos",
    description = "Procura arquivos no celular pelo nome, tipo e data. Os resultados aparecem numa lista para abrir, compartilhar ou renomear",
    risk = RiskClass.READ,
    apps = emptySet(),
    params = mapOf(
        "termo" to "palavras do nome do arquivo, como 'contrato aluguel'",
        "tipo" to "imagem, video, audio, pdf, documento, planilha ou apresentacao",
        "periodo" to "hoje, ontem, semana, mes ou ano",
    ),
    title = "Buscar arquivos",
) {
    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
        if (!ctx.files.hasAccess()) return ToolResult.Failure("sem acesso a arquivos", SEM_ACESSO)
        val kind = call.arg("tipo")?.let { FileKind.fromWord(it) ?: FileKind.entries.firstOrNull { k -> k.name.equals(FileSearch.simplify(it), true) } }
        val period = FileSearch.Period.parse(call.arg("periodo"))
        val query = FileQuery(
            terms = FileSearch.terms(call.arg("termo")),
            kind = kind,
            modifiedAfterMs = period?.since(ctx.clock),
        )
        if (query.terms.isEmpty() && kind == null && period == null) {
            return ToolResult.Failure("busca vazia", "Que arquivo você procura?")
        }
        val found = FileSearch.rank(ctx.files.search(query), query)
        val what = describe(call.arg("termo"), kind)
        return when (found.size) {
            0 -> ToolResult.Failure("nada encontrado", "Não achei $what")
            1 -> ToolResult.Files("Achei um arquivo", found)
            else -> ToolResult.Files("Achei ${found.size} arquivos", found)
        }
    }

    private fun describe(termo: String?, kind: FileKind?): String {
        val tipo = kind?.label?.lowercase() ?: "arquivo"
        return if (termo.isNullOrBlank()) "nenhum $tipo nesse período" else "nenhum $tipo com \"$termo\""
    }
}

/** Base das ações sobre um arquivo da lista: chegam pelo id e nunca do modelo. */
internal abstract class FileAction(name: String, description: String, risk: RiskClass, title: String, extra: Map<String, String> = emptyMap()) :
    BaseTool(
        name = name,
        description = description,
        risk = risk,
        apps = emptySet(),
        params = mapOf("id" to "id do arquivo na lista", "nome" to "nome atual do arquivo") + extra,
        required = setOf("id", "nome") + extra.keys,
        title = title,
    ) {
    override val exposedToModels = false

    protected suspend fun withFile(call: ToolCall, ctx: ExecContext, block: suspend (app.blico.core.FileItem) -> ToolResult): ToolResult {
        if (!ctx.files.hasAccess()) return ToolResult.Failure("sem acesso a arquivos", SEM_ACESSO)
        val file = ctx.files.get(call.arg("id")!!) ?: return ToolResult.Failure("arquivo sumiu", "Não encontrei mais esse arquivo")
        return block(file)
    }
}

internal object ArquivoAbrir : FileAction("arquivo_abrir", "Abre um arquivo da lista", RiskClass.READ, "Abrir arquivo") {
    override suspend fun run(call: ToolCall, ctx: ExecContext) = withFile(call, ctx) { f ->
        ctx.device.perform(DeviceAction.OpenFile(f.id, f.mime)).toToolResult("Abrindo ${f.name}")
    }
}

internal object ArquivoCompartilhar : FileAction("arquivo_compartilhar", "Compartilha um arquivo da lista", RiskClass.PREPARE, "Compartilhar arquivo") {
    override suspend fun run(call: ToolCall, ctx: ExecContext) = withFile(call, ctx) { f ->
        ctx.device.perform(DeviceAction.ShareFile(f.id, f.mime)).toToolResult("Escolha com quem compartilhar")
    }
}

/** Classe 2: muda o arquivo, então passa pelo cartão Confirmar com o nome antigo e o novo. */
internal object ArquivoRenomear : FileAction(
    "arquivo_renomear",
    "Renomeia um arquivo da lista",
    RiskClass.COMMIT,
    "Renomear arquivo",
    extra = mapOf("novo_nome" to "nome novo, com ou sem a extensão"),
) {
    override fun summarize(call: ToolCall) = ConfirmationSummary(
        title = "Renomear arquivo",
        fields = listOf("De" to call.arg("nome").orEmpty(), "Para" to finalName(call.arg("nome").orEmpty(), call.arg("novo_nome").orEmpty())),
    )

    override suspend fun run(call: ToolCall, ctx: ExecContext) = withFile(call, ctx) { f ->
        val target = finalName(f.name, call.arg("novo_nome")!!)
        when {
            !isValidName(target) -> ToolResult.Failure("nome inválido: $target", "Esse nome não serve para arquivo")
            target == f.name -> ToolResult.Success("O nome já é esse")
            else -> ctx.device.perform(DeviceAction.RenameFile(f.id, target)).toToolResult("Renomeado para $target")
        }
    }

    /** Mantém a extensão original quando o nome novo vem sem ela: "contrato" + ".pdf". */
    fun finalName(old: String, new: String): String {
        val clean = new.trim().trimEnd('.')
        val oldExt = old.substringAfterLast('.', "")
        val newExt = clean.substringAfterLast('.', "")
        return if (oldExt.isNotEmpty() && !newExt.equals(oldExt, ignoreCase = true)) "$clean.$oldExt" else clean
    }

    private fun isValidName(name: String) =
        name.isNotBlank() && name.length <= 200 && name.none { it in "/\\:*?\"<>|" || it.code < 32 } && !name.startsWith(".")
}
