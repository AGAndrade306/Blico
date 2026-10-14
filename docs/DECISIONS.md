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

## 2026-10-14 — Etapa 1: transcrição pelo reconhecedor on-device do Android

- **A primeira versão transcreve com `SpeechRecognizer.createOnDeviceSpeechRecognizer`**, e não com whisper.cpp. Motivo: já vem no aparelho, tem detecção de fim de fala embutida e resultados parciais, e não exige NDK nem um modelo de ~500 MB no APK. Assim o assistente fica testável no S25 desde já. O whisper.cpp continua como alternativa atrás da interface `Transcriber`, se a qualidade em pt-BR ou a latência não servirem.
- **Sem fallback para reconhecimento em nuvem.** Se o reconhecedor local não existir, o Blico avisa em vez de mandar o áudio para fora (regra 5).
- **Assistente padrão via `VoiceInteractionService`**, com sessão em Compose. O papel `ROLE_ASSISTANT` não pode ser pedido por diálogo; a tela de configuração leva às Configurações de apps padrão.

## 2026-10-14 — Etapa 2: roteador por regras antes de qualquer modelo

- **Os comandos mais comuns são entendidos por regras (`RuleRouter`)**, sem modelo. Motivo: são instantâneos, offline, previsíveis e testáveis; o modelo local e a nuvem entram depois, só para o que as regras não cobrem. O `Assistant` tenta os provedores em ordem e para no primeiro com confiança suficiente.
- **Ferramentas descrevem ações como dados (`DeviceAction`)** e um único backend Android (`AndroidDeviceGateway`) as executa por intents. Assim os links montados (WhatsApp, Uber, Maps) são testados sem aparelho.
- **Nenhuma ferramenta desta etapa envia nada sozinha.** WhatsApp abre a conversa com o texto pronto, ligar abre o discador, Uber abre com o destino preenchido: o último toque é sempre seu (classe 1). O envio automático, que exige Accessibility e o cartão Confirmar, fica para depois.
- **99 e iFood só abrem o app** até o levantamento dos deep links no aparelho.
- **Lugares (casa, trabalho) em SharedPreferences privadas**, sem criptografia por enquanto; a criptografia entra com a memória em arquivos.
- **Roadmap renumerado:** o cérebro híbrido (modelo local + servidor + nuvem) passa a ser a Etapa 3.

## 2026-10-14 — Etapa 3: nuvem via Cloudflare Workers

- **Servidor em Cloudflare Workers (TypeScript, `server/`)**, e não Ktor no Cloud Run. Escolha do André: plano gratuito generoso, deploy com um comando e sem servidor para manter. Por isso o servidor não compartilha código com `core`; o contrato é o JSON de `CloudProtocol`, testado dos dois lados.
- **A chave da Anthropic e o token do aparelho são segredos do Worker** (`wrangler secret put`), nunca no repositório nem no APK. O app guarda só a URL e o token do aparelho, este cifrado com uma chave do Android Keystore (`SecretStore`).
- **Modelo `claude-haiku-5-5` com esforço baixo**, uma ferramenta por vez (`disable_parallel_tool_use`). Motivo: a tarefa é escolher uma ferramenta ou responder curto, e latência importa mais que profundidade. O modelo é uma variável do `wrangler.toml`.
- **Mascaramento no aparelho (`RedactingProvider`)**: antes do envio, endereços salvos viram `endereco_<apelido>` e nomes de contatos viram `contato_N`, além do `Redactor` para telefones e e-mails; a volta restaura os valores nos argumentos. A nuvem nunca vê a agenda inteira, só o pedido.
- **A nuvem também responde perguntas** (`LlmDecision.answer`), e o app fala a resposta. As ferramentas pedidas pela nuvem passam pela mesma `RiskPolicy` das regras.
- **Limite diário opcional por KV** e logs só com metadados (latência, quantas ferramentas foram oferecidas, qual foi escolhida), nunca o texto do pedido.
- **O modelo local fica para a Etapa 3b**, porque o LiteRT-LM depende do Google Maven, que este ambiente de desenvolvimento não alcança; a nuvem entrega valor antes.
