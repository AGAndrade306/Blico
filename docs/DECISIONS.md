# Decisões

Cada decisão técnica, com data e motivo. Mudar uma delas exige uma nova entrada aqui.

## 2026-10-14 — Stack inicial

- **App:** Kotlin, Jetpack Compose, coroutines/Flow, Gradle Kotlin DSL com version catalog. compileSdk/targetSdk 36, minSdk 34. Motivo: o aparelho de referência está no Android 16 e as APIs de assistente, Accessibility e serviços em foreground pedem código nativo.
- **`core` em Kotlin/JVM puro**, e não Kotlin Multiplatform. Motivo: app e servidor (Ktor) rodam na JVM, então um módulo JVM já é compartilhado pelos dois, sem o custo do KMP.
- **Modelo local:** LiteRT-LM com Gemma 4 E4B empacotado no app (E2B como modo rápido); Qwen3-4B via llama.cpp como comparação no benchmark. Motivo: o AICore ainda não suporta tool calling nem lista aparelhos Samsung.
- **Nuvem:** Claude Haiku 5.5 por padrão e Sonnet 5.5 para planos longos, sempre via servidor próprio. Servidor em Ktor no Google Cloud Run.
- **Nuvem já na Etapa 2**, em modo restrito (mascaramento obrigatório, só quando o local não resolve), em vez da fase 3 do documento original. Motivo: o Blico é híbrido desde o início e o benchmark compara local e nuvem.
- **`LlmProvider.chooseTools` devolve `LlmDecision`** (chamadas + confiança), e não só a lista de chamadas. Motivo: o roteador híbrido precisa da confiança para decidir se escala.
- **Hilt e o módulo `server` ficam para quando forem usados** (Etapas 1 e 2), para manter o esqueleto pequeno.
