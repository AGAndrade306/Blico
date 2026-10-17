package app.blico.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Login nos serviços pelo OAuth com PKCE, como app nativo: a pessoa entra na página do próprio
 * serviço, a senha nunca passa pelo Blico, e o acesso fica só no aparelho.
 */
object OAuth {
    /** Um serviço que aceita login. [clientId] vem do registro do app no serviço, nunca é segredo. */
    data class Client(
        val service: String,
        val authorizeUrl: String,
        val tokenUrl: String,
        val clientId: String,
        val redirectUri: String,
        val scopes: List<String> = emptyList(),
        /** Parâmetros que só um serviço pede, como o access_type=offline do Google. */
        val extra: Map<String, String> = emptyMap(),
    )

    /** O que fica guardado entre o pedido e a volta do navegador. */
    data class Pending(val service: String, val state: String, val verifier: String)

    @Serializable
    data class Token(
        val access: String,
        val refresh: String? = null,
        /** Época em segundos; nulo quando o serviço não diz. */
        val expiresAt: Long? = null,
        val scope: String? = null,
    ) {
        /** Vence com um minuto de folga, para não usar um acesso que acaba no meio da chamada. */
        fun expired(nowSeconds: Long): Boolean = expiresAt != null && nowSeconds >= expiresAt - 60
    }

    private val random = SecureRandom()
    private val url = Base64.getUrlEncoder().withoutPadding()
    private val json = Json { ignoreUnknownKeys = true }

    /** 32 bytes aleatórios viram 43 caracteres, o mínimo do RFC 7636. */
    fun newVerifier(): String = ByteArray(32).also(random::nextBytes).let(url::encodeToString)

    fun challenge(verifier: String): String =
        url.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun start(client: Client): Pair<String, Pending> {
        val pending = Pending(client.service, newVerifier(), newVerifier())
        return authorizationUrl(client, pending) to pending
    }

    fun authorizationUrl(client: Client, pending: Pending): String {
        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to client.clientId,
            "redirect_uri" to client.redirectUri,
            "state" to pending.state,
            "code_challenge" to challenge(pending.verifier),
            "code_challenge_method" to "S256",
        )
        if (client.scopes.isNotEmpty()) params["scope"] = client.scopes.joinToString(" ")
        params.putAll(client.extra)
        val sep = if ('?' in client.authorizeUrl) '&' else '?'
        return client.authorizeUrl + sep + form(params)
    }

    sealed interface Callback {
        data class Code(val code: String) : Callback

        data class Refused(val reason: String) : Callback
    }

    /**
     * Confere a volta do navegador. Um state diferente quer dizer que outro app ou página
     * tentou entregar um código que não pedimos.
     */
    fun callback(redirect: String, pending: Pending): Callback {
        val query = redirect.substringAfter('?', "").substringBefore('#')
        val params = query.split('&').filter { '=' in it }.associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('='))
        }
        params["error"]?.let { return Callback.Refused(params["error_description"] ?: it) }
        if (params["state"] != pending.state) return Callback.Refused("A resposta do login não confere. Tente de novo.")
        val code = params["code"] ?: return Callback.Refused("O serviço não devolveu o código de acesso.")
        return Callback.Code(code)
    }

    fun codeBody(client: Client, code: String, pending: Pending): String = form(
        linkedMapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to client.redirectUri,
            "client_id" to client.clientId,
            "code_verifier" to pending.verifier,
        ),
    )

    fun refreshBody(client: Client, refresh: String): String = form(
        linkedMapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to client.clientId),
    )

    @Serializable
    private data class TokenResponse(
        val access_token: String? = null,
        val refresh_token: String? = null,
        val expires_in: Long? = null,
        val scope: String? = null,
        val error: String? = null,
        val error_description: String? = null,
    )

    /** Na renovação o serviço pode não mandar outro refresh; aí vale o [previous]. */
    fun parseToken(body: String, nowSeconds: Long, previous: Token? = null): Token {
        val r = json.decodeFromString(TokenResponse.serializer(), body)
        val access = r.access_token
            ?: throw IllegalStateException(r.error_description ?: r.error ?: "O serviço não devolveu o acesso.")
        return Token(
            access = access,
            refresh = r.refresh_token ?: previous?.refresh,
            expiresAt = r.expires_in?.let { nowSeconds + it },
            scope = r.scope ?: previous?.scope,
        )
    }

    fun encode(token: Token): String = json.encodeToString(Token.serializer(), token)

    fun decodeToken(text: String): Token = json.decodeFromString(Token.serializer(), text)

    private fun form(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    private fun decode(s: String) = java.net.URLDecoder.decode(s, Charsets.UTF_8)
}
