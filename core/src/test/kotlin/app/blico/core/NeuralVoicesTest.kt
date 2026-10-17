package app.blico.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NeuralVoicesTest {
    private val kokoro = NeuralVoices.KOKORO

    @Test
    fun `catalogo tem ids unicos e as tres vozes do kokoro dividem o pacote`() {
        assertEquals(NeuralVoices.all.size, NeuralVoices.all.map { it.id }.toSet().size)
        assertEquals(listOf(42, 43, 44), NeuralVoices.all.filter { it.pack == kokoro }.map { it.speaker })
        assertEquals(6, NeuralVoices.packs.size)
        assertEquals(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pt_BR-faber-medium.tar.bz2",
            NeuralVoices.byId("piper-faber")!!.pack.url,
        )
    }

    @Test
    fun `extrai so o que o portugues usa e tira a pasta de topo`() {
        assertEquals("model.int8.onnx", NeuralVoices.extractPath(kokoro, "kokoro-int8-multi-lang-v1_0/model.int8.onnx"))
        assertEquals("espeak-ng-data/pt_dict", NeuralVoices.extractPath(kokoro, "kokoro-int8-multi-lang-v1_0/espeak-ng-data/pt_dict"))
        assertNull(NeuralVoices.extractPath(kokoro, "kokoro-int8-multi-lang-v1_0/lexicon-zh.txt"))
        assertNull(NeuralVoices.extractPath(kokoro, "kokoro-int8-multi-lang-v1_0/date-zh.fst"))
        assertNull(NeuralVoices.extractPath(kokoro, "kokoro-int8-multi-lang-v1_0/dict/jieba.dict.utf8"))
        assertNull(NeuralVoices.extractPath(kokoro, "kokoro-int8-multi-lang-v1_0/"))
    }

    @Test
    fun `recusa caminhos que saem da pasta da voz`() {
        assertNull(NeuralVoices.extractPath(kokoro, "kokoro-int8-multi-lang-v1_0/../../shared_prefs/x.xml"))
        assertNull(NeuralVoices.extractPath(kokoro, "/data/data/app.blico/x"))
        assertNull(NeuralVoices.extractPath(kokoro, "outra-pasta/model.onnx"))
    }

    @Test
    fun `quebra em frases e junta as muito curtas`() {
        val c = NeuralVoices.chunks("Oi. Mandei a mensagem para o João.   Alarme às 7:30! Tudo certo?")
        assertEquals(listOf("Oi. Mandei a mensagem para o João.", "Alarme às 7:30! Tudo certo?"), c)
        assertEquals(emptyList(), NeuralVoices.chunks("   "))
        assertEquals(listOf("Pronto."), NeuralVoices.chunks("Pronto."))
    }

    @Test
    fun `trecho longo sem ponto e cortado entre palavras`() {
        val long = (1..80).joinToString(" ") { "palavra$it" }
        val c = NeuralVoices.chunks(long, maxChars = 100)
        assertTrue(c.size > 1)
        assertTrue(c.all { it.length <= 100 }, c.toString())
        assertEquals(long, c.joinToString(" "))
    }
}
