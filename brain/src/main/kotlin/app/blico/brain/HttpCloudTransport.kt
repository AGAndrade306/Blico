package app.blico.brain

import app.blico.core.CloudUnavailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * POST para o servidor do Blico. Só HTTPS, com o token do aparelho; a chave da Anthropic fica no servidor.
 * Erros viram [CloudUnavailable] com uma frase que pode ser falada.
 */
class HttpCloudTransport(
    private val endpoint: () -> String?,
    private val token: () -> String?,
    private val timeoutMs: Int = 15_000,
) {
    suspend fun post(body: String): String = withContext(Dispatchers.IO) {
        val base = endpoint()?.trim()?.trimEnd('/')
        val auth = token()?.trim()
        if (base.isNullOrEmpty() || auth.isNullOrEmpty()) throw CloudUnavailable("O servidor ainda não foi configurado no app.")
        if (!base.startsWith("https://")) throw CloudUnavailable("O endereço do servidor precisa começar com https.")

        val conn = (URL("$base/v1/plan").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("content-type", "application/json; charset=utf-8")
            setRequestProperty("authorization", "Bearer $auth")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            when (code) {
                in 200..299 -> text
                401 -> throw CloudUnavailable("O token do aparelho não confere com o do servidor.")
                503 -> throw CloudUnavailable("O servidor está sem o DEVICE_TOKEN. Crie ele como segredo na Cloudflare.")
                502 -> throw CloudUnavailable("O servidor não conseguiu falar com a Anthropic. Confira a chave no painel da Cloudflare.")
                429 -> throw CloudUnavailable("Limite de uso atingido, tente mais tarde.")
                else -> throw CloudUnavailable("O servidor respondeu com erro $code.")
            }
        } catch (e: UnknownHostException) {
            throw CloudUnavailable("Sem internet.")
        } catch (e: SocketTimeoutException) {
            throw CloudUnavailable("A nuvem demorou demais.")
        } catch (e: IOException) {
            throw CloudUnavailable("Falha de rede.")
        } finally {
            conn.disconnect()
        }
    }
}
