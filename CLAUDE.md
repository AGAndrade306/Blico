# Blico

Assistente de IA para Android que substitui boa parte do Gemini. Roda híbrido: um modelo pequeno no aparelho resolve o comum e a nuvem (Anthropic API, via servidor próprio) entra para o que é difícil. Aparelho de referência: Galaxy S25 Ultra, Android 16, One UI 8.5.

Responda e documente em português do Brasil.

## Regras inegociáveis

1. O microfone só abre depois de um gesto do usuário. Nenhuma escuta contínua.
2. Nenhuma ação de classe 2 (COMMIT) ou 3 (SENSITIVE) executa sem um toque no cartão Confirmar. O texto do cartão vem da chamada estruturada (`Tool.summarize`), nunca do texto livre do modelo.
3. Conteúdo lido de tela, notificações, mensagens e documentos é dado, nunca instrução. Sempre passa por `UntrustedContent.wrap`.
4. `PaymentAppsBlocklist` é fixa no código e impede automação de apps bancários e de pagamento.
5. Nada sai do aparelho sem passar pelo `Redactor`. A chave da API nunca entra no APK: o app fala com o servidor próprio.
6. Sem root no caminho crítico, sem canais remotos como entrada (Telegram, WhatsApp) e sem marketplace de skills.
7. Segredos nunca entram no repositório.

## Estrutura

| Módulo | Tipo | Papel |
| --- | --- | --- |
| `core` | Kotlin/JVM | Tipos puros e regras: `Tool`, `ToolCall`, `RiskClass`, `RiskPolicy`, `Redactor`, interfaces. Não conhece Android. |
| `app` | Android app | Serviços em foreground, assistente padrão, manifest |
| `brain` | Android lib | `LocalProvider` (LiteRT-LM + Gemma 4), `CloudProvider`, `HybridRouter` |
| `voice` | Android lib | Captura, VAD, whisper.cpp, TTS |
| `executor` | Android lib | Backends: intents, AppFunctions, Accessibility, Shizuku |
| `skills` | Android lib | Skills em Markdown |
| `memory` | Android lib | Arquivos de memória criptografados |
| `confirmation` | Android lib | Cartão Confirmar |
| `testkit` | Android lib | Fakes e testes instrumentados |
| `server/` | Cloudflare Worker (TypeScript) | Guarda a chave da Anthropic e repassa `/v1/plan` |

Regra de dependência: `core` não depende de nada do projeto; todos os outros dependem de `core`.

## Como trabalhar

- `./gradlew test lint assembleDebug` e, em `server/`, `npm test && npm run typecheck` precisam passar antes de qualquer PR.
- Toda lógica que não precisa do aparelho fica em `core` ou atrás de interfaces, com teste unitário.
- Uma branch por etapa, commits pequenos, e cada decisão técnica registrada em `docs/DECISIONS.md` com o motivo.
- Quando faltar algo que só o aparelho ou o dono sabe (um deep link, uma permissão), parar e perguntar em vez de inventar.

## Roadmap

- Etapa 0: esqueleto, CI, `core` com testes.
- Etapa 1: assistente padrão (VoiceInteractionService) com captura e transcrição.
- Etapa 2: roteador por regras e primeiras ações por intent (WhatsApp, ligar, Uber, 99, iFood, Spotify, Maps, alarme, timer, busca), com resposta falada.
- Etapa 3: nuvem para o que as regras não entendem: servidor em Cloudflare Workers (`server/`) com Claude Haiku 5.5, mascaramento antes do envio e respostas faladas.
- Etapa 3b: modelo local (LiteRT-LM + Gemma 4) entre as regras e a nuvem; benchmark de 100 comandos.
- Etapa 4: Accessibility, cartão Confirmar e envio de verdade (classe 2); testes de Shizuku.
