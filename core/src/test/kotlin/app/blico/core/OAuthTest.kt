package app.blico.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OAuthTest {
    private val client = OAuth.Client(
        service = "todoist",
        authorizeUrl = "https://exemplo.com/oauth/authorize",
        tokenUrl = "https://exemplo.com/oauth/token",
        clientId = "abc",
        redirectUri = "app.blico:/oauth",
        scopes = listOf("ler", "escrever"),
    )

    @Test
    fun `challenge segue o exemplo do RFC 7636`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            OAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `verifier novo a cada login e com tamanho valido`() {
        val a = OAuth.newVerifier()
        assertEquals(43, a.length)
        assertNotEquals(a, OAuth.newVerifier())
    }

    @Test
    fun `url de login leva pkce, state e escopos`() {
        val pending = OAuth.Pending("todoist", "st", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        val url = OAuth.authorizationUrl(client, pending)
        assertTrue(url.startsWith("https://exemplo.com/oauth/authorize?response_type=code&client_id=abc"))
        assertTrue("redirect_uri=app.blico%3A%2Foauth" in url)
        assertTrue("state=st" in url)
        assertTrue("code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256" in url)
        assertTrue("scope=ler%20escrever" in url)
    }

    @Test
    fun `volta do navegador com outro state e recusada`() {
        val pending = OAuth.Pending("todoist", "certo", "v")
        assertIs<OAuth.Callback.Refused>(OAuth.callback("app.blico:/oauth?code=x&state=errado", pending))
        assertEquals(OAuth.Callback.Code("x y"), OAuth.callback("app.blico:/oauth?code=x%20y&state=certo", pending))
        val denied = OAuth.callback("app.blico:/oauth?error=access_denied&state=certo", pending)
        assertEquals(OAuth.Callback.Refused("access_denied"), denied)
    }

    @Test
    fun `troca do codigo manda o verifier`() {
        val body = OAuth.codeBody(client, "c0d", OAuth.Pending("todoist", "s", "ver"))
        assertEquals("grant_type=authorization_code&code=c0d&redirect_uri=app.blico%3A%2Foauth&client_id=abc&code_verifier=ver", body)
    }

    @Test
    fun `token vence com folga e a renovacao guarda o refresh antigo`() {
        val t = OAuth.parseToken("""{"access_token":"a1","refresh_token":"r1","expires_in":3600}""", nowSeconds = 1000)
        assertEquals(OAuth.Token("a1", "r1", 4600), t)
        assertFalse(t.expired(4500))
        assertTrue(t.expired(4541))
        val renewed = OAuth.parseToken("""{"access_token":"a2","expires_in":3600}""", nowSeconds = 5000, previous = t)
        assertEquals("r1", renewed.refresh)
        assertEquals(renewed, OAuth.decodeToken(OAuth.encode(renewed)))
    }

    @Test
    fun `erro do servico vira excecao com a descricao`() {
        val e = runCatching { OAuth.parseToken("""{"error":"invalid_grant","error_description":"Código vencido"}""", 0) }
        assertEquals("Código vencido", e.exceptionOrNull()?.message)
    }
}
