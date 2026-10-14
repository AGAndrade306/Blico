package app.blico.core

import app.blico.core.tools.builtinTools
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilesTest {
    private val zone = ZoneId.of("America/Sao_Paulo")

    // 14/10/2026 15:00 em São Paulo
    private val clock = Clock.fixed(Instant.parse("2026-10-14T18:00:00Z"), zone)
    private val day = 24 * 3600 * 1000L
    private val now = clock.millis()

    private val files = listOf(
        FileItem("1", "Contrato_Aluguel_2026.pdf", "application/pdf", 230_000, now - 2 * day, "Download/"),
        FileItem("2", "contrato-trabalho.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 40_000, now - 40 * day, "Documents/"),
        FileItem("3", "IMG_20261014_101500.jpg", "image/jpeg", 3_100_000, now - 3600_000, "DCIM/Camera/"),
        FileItem("4", "Relatório final.pdf", "application/pdf", 900_000, now - day, "Download/"),
        FileItem("5", "gastos.csv", "text/csv", 2_000, now - 5 * day, "Download/"),
    )

    private val actions = mutableListOf<DeviceAction>()
    private val index = object : FileIndex {
        override fun hasAccess() = true

        override suspend fun search(query: FileQuery) = files

        override suspend fun get(id: String) = files.firstOrNull { it.id == id }
    }
    private val ctx = ExecContext(
        requestId = "t",
        device = object : DeviceGateway {
            override suspend fun perform(action: DeviceAction) = DeviceResult.Done.also { actions += action }
        },
        contacts = object : ContactDirectory {
            override suspend fun find(query: String) = emptyList<Contact>()
        },
        places = object : PlaceBook {
            override fun resolve(alias: String): String? = null
        },
        files = index,
        clock = clock,
    )
    private val assistant = Assistant(listOf(RuleRouter()), ToolRegistry(builtinTools()))

    @Test
    fun `tipo pelo mime e pela extensao`() {
        assertEquals(FileKind.PLANILHA, FileKind.of("text/csv", "gastos.csv"))
        assertEquals(FileKind.DOCUMENTO, FileKind.of("text/plain", "notas.txt"))
        assertEquals(FileKind.IMAGEM, FileKind.of(null, "foto.HEIC"))
        assertEquals(FileKind.OUTRO, FileKind.of("application/zip", "backup.zip"))
        assertEquals(FileKind.IMAGEM, FileKind.fromWord("fotos"))
        assertEquals(FileKind.APRESENTACAO, FileKind.fromWord("apresentações"))
        assertNull(FileKind.fromWord("arquivo"))
    }

    @Test
    fun `termos ignoram acento, caixa e separadores`() {
        val q = FileQuery(FileSearch.terms("o relatorio FINAL"))
        assertEquals(listOf("4"), FileSearch.rank(files, q).map { it.id })
        assertEquals(listOf("1", "2"), FileSearch.rank(files, FileQuery(listOf("contrato"))).map { it.id })
    }

    @Test
    fun `periodo separado do termo`() {
        assertEquals("contrato" to FileSearch.Period.ONTEM, FileSearch.splitPeriod("contrato de ontem"))
        assertEquals(null to FileSearch.Period.SEMANA, FileSearch.splitPeriod("desta semana"))
        assertEquals("Contrato Aluguel" to null, FileSearch.splitPeriod("Contrato Aluguel"))
        val since = FileSearch.Period.HOJE.since(clock)
        assertEquals(Instant.parse("2026-10-14T03:00:00Z").toEpochMilli(), since)
    }

    @Test
    fun `regras entendem pedidos de arquivo e nao roubam a busca na web`() {
        val r = RuleRouter()
        assertEquals(ToolCall("buscar_arquivos", mapOf("termo" to "contrato de aluguel", "tipo" to "pdf")), r.parse("acha o pdf do contrato de aluguel"))
        assertEquals(ToolCall("buscar_arquivos", mapOf("tipo" to "imagem", "periodo" to "ontem")), r.parse("fotos de ontem"))
        assertEquals(ToolCall("buscar_arquivos", mapOf("termo" to "gastos", "tipo" to "planilha")), r.parse("cadê a planilha de gastos?"))
        assertEquals(ToolCall("buscar_arquivos", mapOf("termo" to "Relatório", "periodo" to "semana")), r.parse("procura o arquivo Relatório desta semana"))
        assertEquals("buscar_web", r.parse("procura receita de bolo")!!.tool)
        assertEquals("abrir_app", r.parse("abre a galeria")!!.tool)
    }

    @Test
    fun `busca de ponta a ponta devolve a lista`() = runTest {
        val out = assistant.handle("procura os pdfs desta semana", ctx)
        assertIs<Outcome.Files>(out)
        assertEquals(listOf("4", "1"), out.items.map { it.id })
        assertEquals("Achei 2 arquivos", out.spoken)
    }

    @Test
    fun `nada encontrado e sem permissao`() = runTest {
        assertEquals("Não achei nenhum pdf com \"boleto\"", assistant.handle("acha o pdf do boleto", ctx).spoken)
        val semAcesso = ctx.copy(files = FileIndex.None)
        assertIs<Outcome.Failed>(assistant.handle("fotos de ontem", semAcesso))
    }

    @Test
    fun `abrir e compartilhar executam direto`() = runTest {
        assertIs<Outcome.Done>(assistant.execute(ToolCall("arquivo_abrir", mapOf("id" to "1", "nome" to "x")), ctx))
        assertIs<Outcome.Done>(assistant.execute(ToolCall("arquivo_compartilhar", mapOf("id" to "3", "nome" to "x")), ctx))
        assertEquals(listOf(DeviceAction.OpenFile("1", "application/pdf"), DeviceAction.ShareFile("3", "image/jpeg")), actions)
    }

    @Test
    fun `renomear pede confirmacao e mantem a extensao`() = runTest {
        val call = ToolCall("arquivo_renomear", mapOf("id" to "1", "nome" to "Contrato_Aluguel_2026.pdf", "novo_nome" to "Aluguel 2026"))
        val confirm = assistant.execute(call, ctx)
        assertIs<Outcome.Confirm>(confirm)
        assertEquals(listOf("De" to "Contrato_Aluguel_2026.pdf", "Para" to "Aluguel 2026.pdf"), confirm.summary.fields)
        assertTrue(actions.isEmpty())

        assertIs<Outcome.Done>(assistant.execute(confirm.call, ctx, confirmed = true))
        assertEquals(DeviceAction.RenameFile("1", "Aluguel 2026.pdf"), actions.single())

        val bad = assistant.execute(call.copy(args = call.args + ("novo_nome" to "a/b")), ctx, confirmed = true)
        assertIs<Outcome.Failed>(bad)
    }

    @Test
    fun `acoes sobre arquivo nao sao oferecidas aos modelos`() = runTest {
        var offered = emptyList<String>()
        val spy = object : LlmProvider {
            override val id = "espiao"
            override suspend fun chooseTools(request: LlmRequest): LlmDecision {
                offered = request.tools.map { it.name }
                return LlmDecision(emptyList(), 0.0)
            }
        }
        Assistant(listOf(spy), ToolRegistry(builtinTools())).handle("qualquer coisa", ctx)
        assertTrue("buscar_arquivos" in offered)
        assertTrue(offered.none { it.startsWith("arquivo_") })
    }
}
