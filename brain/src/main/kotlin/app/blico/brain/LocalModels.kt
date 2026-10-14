package app.blico.brain

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Modelos que o Blico sabe rodar. Os arquivos vêm do Hugging Face, presos a um commit
 * para o download ser sempre o mesmo arquivo que foi testado.
 */
enum class LocalModel(
    val id: String,
    val label: String,
    val detail: String,
    val fileName: String,
    private val repo: String,
    private val commit: String,
    val approxBytes: Long,
) {
    GEMMA4_E2B(
        id = "gemma-4-e2b",
        label = "Gemma 4 E2B",
        detail = "Mais rápido. Bom para comandos do dia a dia.",
        fileName = "gemma-4-E2B-it.litertlm",
        repo = "litert-community/gemma-4-E2B-it-litert-lm",
        commit = "7fa1d78473894f7e736a21d920c3aa80f950c0db",
        approxBytes = 2_588_147_712,
    ),
    GEMMA4_E4B(
        id = "gemma-4-e4b",
        label = "Gemma 4 E4B",
        detail = "Mais esperto e um pouco mais lento. Cabe bem nos 12 GB do S25 Ultra.",
        fileName = "gemma-4-E4B-it.litertlm",
        repo = "litert-community/gemma-4-E4B-it-litert-lm",
        commit = "28299f30ee4d43294517a4ac93abd6163412f07f",
        approxBytes = 3_659_530_240,
    ),
    ;

    val url: String get() = "https://huggingface.co/$repo/resolve/$commit/$fileName?download=true"

    companion object {
        fun byId(id: String?): LocalModel? = entries.firstOrNull { it.id == id }
    }
}

/** Como está o download do modelo, para a tela mostrar. */
sealed interface ModelDownload {
    data object Idle : ModelDownload

    data class Running(val model: LocalModel, val done: Long, val total: Long, val waitingForWifi: Boolean) : ModelDownload

    data class Failed(val model: LocalModel, val message: String) : ModelDownload
}

/**
 * Guarda, baixa e apaga os modelos. O download usa o DownloadManager do Android, que continua
 * com o app fechado, retoma depois de cair e só usa Wi-Fi (são alguns GB).
 */
class ModelStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("modelo_local", Context.MODE_PRIVATE)
    private val downloads = appContext.getSystemService(DownloadManager::class.java)
    private val dir: File = appContext.getExternalFilesDir(DIR) ?: File(appContext.filesDir, DIR)

    /** Modelo que o assistente usa. Só vale se o arquivo existe. */
    var selected: LocalModel?
        get() = LocalModel.byId(prefs.getString("selecionado", null))
        set(v) = prefs.edit().putString("selecionado", v?.id).apply()

    fun file(model: LocalModel) = File(dir, model.fileName)

    fun isInstalled(model: LocalModel) = file(model).isFile

    /** Arquivo do modelo escolhido, se já foi baixado. */
    fun selectedFile(): File? = selected?.let(::file)?.takeIf { it.isFile }

    /** Começa a baixar. Devolve uma frase de erro se não dá para começar. */
    fun download(model: LocalModel): String? {
        if (isInstalled(model)) {
            selected = model
            return null
        }
        cancel()
        dir.mkdirs()
        val free = dir.usableSpace
        if (free < model.approxBytes + SPACE_MARGIN) {
            return "Falta espaço: o ${model.label} precisa de ${gb(model.approxBytes + SPACE_MARGIN)} livres e há ${gb(free)}."
        }
        partial(model).delete()
        val request = DownloadManager.Request(Uri.parse(model.url))
            .setTitle("Blico: ${model.label}")
            .setDescription("Modelo para entender pedidos sem internet")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(appContext, DIR, partial(model).name)
            .setAllowedOverMetered(false)
            .setAllowedOverRoaming(false)
        val id = downloads.enqueue(request)
        prefs.edit().putLong("download", id).putString("baixando", model.id).apply()
        return null
    }

    /**
     * Lê o andamento. Quando termina, troca o nome do arquivo parcial pelo final
     * e passa a usar o modelo novo.
     */
    fun poll(): ModelDownload {
        val id = prefs.getLong("download", -1)
        val model = LocalModel.byId(prefs.getString("baixando", null))
        if (id < 0 || model == null) return ModelDownload.Idle
        downloads.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) {
                clear()
                return ModelDownload.Idle
            }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 } ?: model.approxBytes
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> finish(model)
                DownloadManager.STATUS_FAILED -> {
                    downloads.remove(id)
                    clear()
                    ModelDownload.Failed(model, failure(reason))
                }
                else -> ModelDownload.Running(
                    model,
                    done,
                    total,
                    waitingForWifi = status == DownloadManager.STATUS_PAUSED && reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI,
                )
            }
        }
    }

    fun cancel() {
        val id = prefs.getLong("download", -1)
        if (id >= 0) downloads.remove(id)
        clear()
    }

    fun delete(model: LocalModel) {
        file(model).delete()
        if (selected == model) selected = null
    }

    private fun finish(model: LocalModel): ModelDownload {
        val target = file(model)
        val ok = partial(model).renameTo(target)
        clear()
        if (!ok) return ModelDownload.Failed(model, "O download terminou, mas não consegui guardar o arquivo.")
        selected = model
        return ModelDownload.Idle
    }

    private fun clear() = prefs.edit().remove("download").remove("baixando").apply()

    private fun partial(model: LocalModel) = File(dir, model.fileName + ".baixando")

    private fun failure(reason: Int) = when (reason) {
        401, 403 -> "O Hugging Face recusou o download ($reason)."
        404 -> "O arquivo do modelo não está mais nesse endereço."
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Acabou o espaço durante o download."
        DownloadManager.ERROR_CANNOT_RESUME -> "O download caiu e não deu para continuar. Tente de novo."
        else -> "O download falhou (código $reason). Tente de novo."
    }

    private companion object {
        const val DIR = "modelos"

        /** Folga para o cache do motor e para o resto do celular. */
        const val SPACE_MARGIN = 1_000_000_000L

        fun gb(bytes: Long) = "%.1f GB".format(java.util.Locale.forLanguageTag("pt-BR"), bytes / 1e9)
    }
}
