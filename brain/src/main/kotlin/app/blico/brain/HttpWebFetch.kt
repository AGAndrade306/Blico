package app.blico.brain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * GET curto para as respostas do card (DuckDuckGo). Só HTTPS, sem cookies nem conta.
 * Qualquer erro vira nulo: aí o card fica com o modelo do celular ou o Blico abre o navegador.
 */
object HttpWebFetch {
    suspend fun get(url: String, timeoutMs: Int = 4_000): String? = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) return@withContext null
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            useCaches = false
            setRequestProperty("accept", "application/json")
            setRequestProperty("user-agent", "Blico")
        }
        try {
            if (conn.responseCode !in 200..299) return@withContext null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
