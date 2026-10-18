package app.blico.core.tools

import java.text.Normalizer

/** Pacotes dos apps que o Blico conhece. */
object Apps {
    const val WHATSAPP = "com.whatsapp"
    const val UBER = "com.ubercab"
    const val NOVENTA_E_NOVE = "com.taxis99"
    const val IFOOD = "br.com.brainweb.ifood"
    const val SPOTIFY = "com.spotify.music"
    const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
    const val YOUTUBE = "com.google.android.youtube"
    const val DEEZER = "deezer.android.app"
    const val APPLE_MUSIC = "com.apple.android.music"
    const val AMAZON_MUSIC = "com.amazon.mp3"
    const val GOOGLE_MAPS = "com.google.android.apps.maps"
    const val WAZE = "com.waze"
    const val DUCKDUCKGO = "com.duckduckgo.mobile.android"
}

/**
 * Apps de música que o Blico sabe mandar tocar. [names] é como a pessoa fala o nome,
 * já em minúsculas; [searchUri] é a busca no próprio app, para quando ele não aceitar o "tocar" do Android.
 */
enum class Player(val label: String, val packageName: String, val names: List<String>, private val search: (String) -> String) {
    SPOTIFY("Spotify", Apps.SPOTIFY, listOf("spotify"), { "spotify:search:$it" }),
    YOUTUBE_MUSIC(
        "YouTube Music", Apps.YOUTUBE_MUSIC,
        listOf("youtube music", "youtube música", "youtube musica", "yt music", "you tube music"),
        { "https://music.youtube.com/search?q=$it" },
    ),
    DEEZER("Deezer", Apps.DEEZER, listOf("deezer"), { "deezer://www.deezer.com/search/$it" }),
    APPLE_MUSIC("Apple Music", Apps.APPLE_MUSIC, listOf("apple music"), { "https://music.apple.com/search?term=$it" }),
    AMAZON_MUSIC("Amazon Music", Apps.AMAZON_MUSIC, listOf("amazon music", "amazon"), { "https://music.amazon.com.br/search/$it" }),
    YOUTUBE("YouTube", Apps.YOUTUBE, listOf("youtube", "you tube"), { "https://www.youtube.com/results?search_query=$it" }),
    ;

    fun searchUri(query: String): String = search(encode(query))

    companion object {
        /** O player pelo nome falado ("o YouTube Music", "deezer"), ou null se não for um que o Blico conhece. */
        fun find(name: String): Player? {
            val wanted = simplify(name).removePrefix("o ").removePrefix("a ")
            return entries.firstOrNull { p -> p.names.any { simplify(it) == wanted } || simplify(p.label) == wanted }
        }

        /** Todos os nomes, dos mais longos aos mais curtos, para "youtube music" ganhar de "youtube" numa regex. */
        val spokenNames: List<String> get() = entries.flatMap { it.names }.sortedByDescending { it.length }

        private fun simplify(s: String): String =
            Normalizer.normalize(s.lowercase().trim().replace(Regex("\\s+"), " "), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    }
}
