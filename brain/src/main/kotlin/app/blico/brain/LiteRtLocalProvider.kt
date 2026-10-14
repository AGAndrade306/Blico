package app.blico.brain

import app.blico.core.LlmDecision
import app.blico.core.LlmProvider
import app.blico.core.LlmRequest
import app.blico.core.LocalBrain
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.ZonedDateTime

/**
 * Gemma rodando no aparelho pelo LiteRT-LM. Nada sai do celular, então não passa pelo Redactor.
 *
 * O motor carrega na primeira vez (alguns segundos) e fica na memória; [warmUp] adianta isso
 * quando o cartão abre. Cada pedido usa uma conversa nova, sem histórico, com as ferramentas
 * do Blico em modo manual: o modelo só propõe a chamada, quem executa é o [app.blico.core.Assistant].
 */
class LiteRtLocalProvider(
    private val modelFile: () -> File?,
    private val cacheDir: File,
) : LlmProvider {
    override val id = "local"

    /** Uma thread só: o motor nativo não é feito para chamadas concorrentes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val lock = Mutex()
    private var engine: Engine? = null
    private var loadedPath: String? = null

    /** "GPU" ou "CPU", depois de carregar. Aparece na tela do modelo. */
    @Volatile
    var backend: String? = null
        private set

    fun isReady(): Boolean = modelFile()?.isFile == true

    /** Carrega o modelo em segundo plano, se ele já foi baixado. */
    suspend fun warmUp() {
        if (isReady()) lock.withLock { ensureEngine() }
    }

    override suspend fun chooseTools(request: LlmRequest): LlmDecision = lock.withLock {
        val engine = ensureEngine() ?: return LlmDecision(emptyList(), 0.0)
        runInterruptible(worker) {
            val config = ConversationConfig(
                systemInstruction = Contents.of(LocalBrain.systemPrompt(ZonedDateTime.now())),
                tools = request.tools.map { tool(BlicoTool(LocalBrain.toolJson(it))) },
                // Determinístico: o mesmo pedido escolhe sempre a mesma ferramenta.
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                automaticToolCalling = false,
                maxOutputToken = MAX_OUTPUT_TOKENS,
            )
            engine.createConversation(config).use { conversation ->
                val message = conversation.sendMessage(request.text)
                LocalBrain.decide(
                    calls = message.toolCalls.map { LocalBrain.RawCall(it.name, it.arguments) },
                    text = message.contents.toString(),
                    tools = request.tools,
                )
            }
        }
    }

    /** Troca de modelo (ou apagou o arquivo): fecha o motor antigo. */
    suspend fun unload() = lock.withLock { closeEngine() }

    private suspend fun ensureEngine(): Engine? {
        val file = modelFile()?.takeIf { it.isFile } ?: run { closeEngine(); return null }
        engine?.let { if (loadedPath == file.path) return it }
        closeEngine()
        return runInterruptible(worker) {
            cacheDir.mkdirs()
            // GPU é bem mais rápida no S25; se o driver recusar, a CPU sempre funciona.
            val (loaded, name) = try {
                load(file, Backend.GPU()) to "GPU"
            } catch (e: Exception) {
                load(file, Backend.CPU()) to "CPU"
            }
            engine = loaded
            loadedPath = file.path
            backend = name
            loaded
        }
    }

    private fun load(file: File, backend: Backend): Engine {
        val e = Engine(EngineConfig(modelPath = file.path, backend = backend, maxNumTokens = MAX_TOKENS, cacheDir = cacheDir.path))
        try {
            e.initialize()
        } catch (t: Exception) {
            e.close()
            throw t
        }
        return e
    }

    private fun closeEngine() {
        engine?.close()
        engine = null
        loadedPath = null
        backend = null
    }

    /** Só descreve a ferramenta; com a chamada manual o LiteRT-LM nunca executa. */
    private class BlicoTool(private val json: String) : OpenApiTool {
        override fun getToolDescriptionJsonString() = json

        override fun execute(paramsJsonString: String): String = error("O Blico executa as ferramentas, não o modelo")
    }

    private companion object {
        /** Prompt com o catálogo cabe folgado; a resposta é curta. */
        const val MAX_TOKENS = 4096
        const val MAX_OUTPUT_TOKENS = 256
    }
}
