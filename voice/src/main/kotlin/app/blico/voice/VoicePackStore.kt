package app.blico.voice

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import app.blico.core.NeuralVoices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File

/** Em que pé está um pacote de voz. */
sealed interface PackState {
    data object Missing : PackState

    data class Downloading(val done: Long, val total: Long) : PackState

    /** Baixado; abrindo o arquivo no celular, o que leva alguns segundos. */
    data object Unpacking : PackState

    data object Ready : PackState

    data class Failed(val message: String) : PackState
}

/**
 * Baixa, abre e apaga os pacotes de voz. O download usa o DownloadManager, que continua com o app
 * fechado. Pacotes de voz são menores que o modelo de linguagem, então os dados móveis são liberados.
 */
class VoicePackStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("vozes", Context.MODE_PRIVATE)
    private val downloads = appContext.getSystemService(DownloadManager::class.java)
    private val dir: File = appContext.getExternalFilesDir(DIR) ?: File(appContext.filesDir, DIR)
    private val _states = MutableStateFlow(NeuralVoices.packs.associate { it.id to initial(it) })
    val states: StateFlow<Map<String, PackState>> = _states

    /** Pasta com o modelo, os tokens e o espeak-ng-data do pacote. */
    fun folder(pack: NeuralVoices.Pack) = File(dir, pack.root)

    fun isReady(pack: NeuralVoices.Pack) = File(folder(pack), READY_MARK).isFile

    private fun initial(pack: NeuralVoices.Pack): PackState = when {
        isReady(pack) -> PackState.Ready
        prefs.getLong(pack.id, -1) >= 0 -> PackState.Downloading(0, pack.approxBytes)
        else -> PackState.Missing
    }

    fun download(pack: NeuralVoices.Pack) {
        if (isReady(pack) || prefs.getLong(pack.id, -1) >= 0) return
        dir.mkdirs()
        if (dir.usableSpace < pack.approxBytes * 3) {
            set(pack, PackState.Failed("Falta espaço: essa voz precisa de uns ${mb(pack.approxBytes * 3)} livres."))
            return
        }
        archive(pack).delete()
        val request = DownloadManager.Request(Uri.parse(pack.url))
            .setTitle("Blico: voz ${pack.id}")
            .setDescription("Voz para o Blico falar sem internet")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(appContext, DIR, archive(pack).name)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        prefs.edit().putLong(pack.id, downloads.enqueue(request)).apply()
        set(pack, PackState.Downloading(0, pack.approxBytes))
    }

    /** Atualiza o andamento dos downloads e abre os que terminaram. Chamado pela tela enquanto ela está aberta. */
    suspend fun poll() {
        for (pack in NeuralVoices.packs) {
            val id = prefs.getLong(pack.id, -1)
            if (id < 0) continue
            val row = downloads.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) null else Triple(
                    c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 } ?: pack.approxBytes,
                )
            }
            when (row?.first) {
                null -> {
                    forget(pack)
                    set(pack, PackState.Missing)
                }
                DownloadManager.STATUS_SUCCESSFUL -> {
                    forget(pack)
                    set(pack, PackState.Unpacking)
                    set(pack, unpack(pack))
                }
                DownloadManager.STATUS_FAILED -> {
                    downloads.remove(id)
                    forget(pack)
                    set(pack, PackState.Failed("O download da voz falhou. Tente de novo."))
                }
                else -> set(pack, PackState.Downloading(row.second, row.third))
            }
        }
    }

    fun cancel(pack: NeuralVoices.Pack) {
        val id = prefs.getLong(pack.id, -1)
        if (id >= 0) downloads.remove(id)
        forget(pack)
        archive(pack).delete()
        set(pack, PackState.Missing)
    }

    fun delete(pack: NeuralVoices.Pack) {
        folder(pack).deleteRecursively()
        set(pack, PackState.Missing)
    }

    /** Abre o `.tar.bz2` só com o que o português usa, e marca como pronto no fim. */
    private suspend fun unpack(pack: NeuralVoices.Pack): PackState = withContext(Dispatchers.IO) {
        val target = folder(pack)
        target.deleteRecursively()
        try {
            TarArchiveInputStream(BZip2CompressorInputStream(archive(pack).inputStream().buffered(1 shl 16))).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val rel = NeuralVoices.extractPath(pack, entry.name) ?: continue
                    val out = File(target, rel)
                    // Segunda trava contra caminhos que saem da pasta, depois de resolvidos pelo sistema.
                    if (!out.canonicalPath.startsWith(target.canonicalPath + File.separator)) continue
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it) }
                }
            }
            if (!File(target, pack.model).isFile) error("o pacote não tem ${pack.model}")
            File(target, READY_MARK).writeText("ok")
            PackState.Ready
        } catch (e: Exception) {
            target.deleteRecursively()
            PackState.Failed("Não consegui abrir a voz baixada (${e.message}). Tente de novo.")
        } finally {
            archive(pack).delete()
        }
    }

    private fun set(pack: NeuralVoices.Pack, state: PackState) = _states.update { it + (pack.id to state) }

    private fun forget(pack: NeuralVoices.Pack) = prefs.edit().remove(pack.id).apply()

    private fun archive(pack: NeuralVoices.Pack) = File(dir, "${pack.root}.tar.bz2")

    private companion object {
        const val DIR = "vozes"
        const val READY_MARK = ".pronto"

        fun mb(bytes: Long) = "${bytes / 1_000_000} MB"
    }
}
