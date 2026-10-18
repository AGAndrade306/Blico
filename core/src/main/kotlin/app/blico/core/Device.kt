package app.blico.core

/** Ações no aparelho descritas como dados, para as ferramentas serem testáveis sem Android. */
sealed interface DeviceAction {
    /** Abre um link ou deep link, opcionalmente forçando o app que deve abrir. */
    data class OpenUri(val uri: String, val packageName: String? = null) : DeviceAction

    /**
     * Toca [query] pelo pedido padrão do Android ("tocar a partir da busca"), em [packageName] quando a pessoa
     * disse o app, ou no player que o sistema escolher. Se o app não aceitar o pedido, abre [fallbackUri].
     */
    data class PlayMedia(val query: String, val packageName: String? = null, val fallbackUri: String? = null) : DeviceAction

    /** Abre um app pelo pacote. */
    data class LaunchPackage(val packageName: String) : DeviceAction

    /** Abre um app pelo nome que aparece na tela inicial. */
    data class LaunchByName(val name: String) : DeviceAction

    /** Abre o discador com o número preenchido. Não liga sozinho. */
    data class Dial(val number: String) : DeviceAction

    /** Completa a ligação. Só depois do toque em Confirmar. */
    data class Call(val number: String) : DeviceAction

    /**
     * Abre a conversa com [text] na caixa e toca em enviar, pela acessibilidade.
     * Só toca se o texto na tela for exatamente [text]. [phone] só com dígitos e DDI.
     */
    data class SendWhatsApp(val phone: String, val text: String) : DeviceAction

    data class SetAlarm(val hour: Int, val minute: Int, val label: String? = null) : DeviceAction

    data class SetTimer(val seconds: Int, val label: String? = null) : DeviceAction

    /** Pesquisa no DuckDuckGo, que não monta perfil de quem pesquisa. Abre no app dele, se houver, ou no navegador. */
    data class WebSearch(val query: String) : DeviceAction {
        val url: String
            get() = "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(query, Charsets.UTF_8).replace("+", "%20")
    }

    /** Compartilha texto com um app; o usuário escolhe o destinatário lá dentro. */
    data class ShareText(val text: String, val packageName: String? = null) : DeviceAction

    /** Abre um arquivo do [FileIndex] no app que o sistema escolher. */
    data class OpenFile(val id: String, val mime: String) : DeviceAction

    /** Abre a folha de compartilhamento com o arquivo; o usuário escolhe o destino. */
    data class ShareFile(val id: String, val mime: String) : DeviceAction

    /** Troca o nome do arquivo, mantendo a pasta. */
    data class RenameFile(val id: String, val newName: String) : DeviceAction
}

sealed interface DeviceResult {
    data object Done : DeviceResult

    data class NotInstalled(val what: String) : DeviceResult

    data class Failed(val reason: String) : DeviceResult
}

/** Regras do envio direto que não dependem do Android. */
object DirectSend {
    /**
     * O texto na caixa do app é o que foi confirmado? Espaços e quebras de linha nas pontas
     * e repetidos não contam; o resto, sim (letra, acento, pontuação).
     */
    fun sameText(onScreen: String?, confirmed: String): Boolean =
        onScreen != null && normalize(onScreen) == normalize(confirmed) && normalize(confirmed).isNotEmpty()

    private fun normalize(s: String) = s.replace(Regex("\\s+"), " ").trim()
}

interface DeviceGateway {
    suspend fun perform(action: DeviceAction): DeviceResult
}

data class Contact(val name: String, val phone: String)

interface ContactDirectory {
    /** Contatos cujo nome bate com [query]. Lista vazia se não houver permissão. */
    suspend fun find(query: String): List<Contact>

    /** Nomes da agenda, usados para mascarar o pedido antes de ir para a nuvem. */
    suspend fun names(): List<String> = emptyList()
}

/** Lugares com apelido: "casa", "trabalho". */
interface PlaceBook {
    fun resolve(alias: String): String?

    /** Endereços cadastrados, por apelido. Também mascarados antes da nuvem. */
    fun all(): Map<String, String> = emptyMap()
}
