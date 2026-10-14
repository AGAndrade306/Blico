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

## 2026-10-14 — Busca de arquivos e escolha de voz

- **Arquivos pelo MediaStore com "acesso a todos os arquivos"** (`MANAGE_EXTERNAL_STORAGE`). Sem ele o Android só mostra fotos, vídeos e áudios a outros apps; PDFs e documentos ficam invisíveis. O Blico é instalado por APK e não vai para a Play Store, então a política da loja para essa permissão não se aplica. A busca roda no aparelho e nenhum nome de arquivo vai para a nuvem.
- **O índice do aparelho traz candidatos e o `core` decide** (`FileSearch.rank`): termos sem acento, caixa ou separadores, todos os termos no nome, do mais recente ao mais antigo. Assim a regra é testada sem Android. No MediaStore, letras acentuadas viram curinga do `LIKE`, para "relatório" achar "relatorio".
- **Ações sobre um arquivo só nascem do toque na lista** (`exposedToModels = false`). Elas usam o id do item mostrado, que o modelo nunca vê; assim a nuvem não pode inventar um id. Abrir é READ, compartilhar é PREPARE (você escolhe o destino) e renomear é COMMIT, com o cartão Confirmar mostrando o nome antigo e o novo. A extensão é mantida quando o nome novo vem sem ela.
- **Renomear tenta o MediaStore e cai para o arquivo em disco** com reindexação, se o sistema recusar o update.
- **Excluir fica de fora por enquanto**: no Android isso passa por uma tela do sistema, e o cartão Confirmar não substitui a lixeira.
- **Voz: só vozes que funcionam sem internet.** O motor (Samsung, Google…) e a voz são escolhidos na tela inicial, com velocidade e tom. Vozes de rede mandariam o texto falado para fora do aparelho (regra 5), então nem aparecem na lista.

## 2026-10-14 — Identidade visual em risografia

- **Estilo de risografia a partir do logo do André:** papel creme, duas tintas (o verde e o rosa do logo), grão de impressão e a segunda cor levemente fora de registro nos títulos, cartões e botões. Os componentes ficam em `app/ui/Riso.kt` (`RisoTheme`, `RisoCard`, `RisoButton`, `RisoChip`, `RisoTitle`), e o tema do Material é mapeado para a mesma paleta, para campos e sliders combinarem.
- **Fontes empacotadas, não baixadas em tempo de execução:** Bagel Fat One para títulos (redonda e gorda como o logo) e Rubik para o texto, as duas com licença OFL (em `docs/licencas`). Foram cortadas para o alfabeto latino, com cerca de 50 KB cada, e o app não depende do Google Play Services para as fontes.
- **O grão é gerado no código**, como uma textura pequena repetida, e não como imagem; assim ele funciona em qualquer tamanho de tela e no modo escuro.
- **Ícone adaptativo com o logo**, com fundo de papel sempre claro e uma versão monocromática para os ícones temáticos do One UI.

## App sempre no papel claro

O fundo escuro do modo noturno ficou difícil de ler. A tela do app usa sempre o papel creme, com ícones escuros nas barras do sistema. O cartão do agente continua seguindo o tema do sistema, porque nele o escuro funcionou bem.

## Cérebro no celular (Etapa 3b)

- **Gemma 4 pelo LiteRT-LM 0.18**, a mesma versão do app AI Edge Gallery do Google. E2B (2,6 GB) é o padrão por ser mais rápido; E4B (3,7 GB) é opcional e cabe nos 12 GB do S25 Ultra.
- **O modelo é baixado pelo app, não vai no APK.** Alguns GB não cabem num APK. O download usa o DownloadManager do Android (continua com o app fechado, retoma, só no Wi-Fi) e o endereço é preso a um commit do Hugging Face, para o arquivo ser sempre o testado.
- **Ordem: regras, depois Gemma, depois nuvem.** A nuvem só entra se estiver configurada e se a pessoa deixar ("Perguntar à nuvem" ou "Só no celular"). O modelo local não passa pelo Redactor porque nada sai do aparelho.
- **Chamada de ferramenta nativa, em modo manual.** O Gemma recebe o mesmo catálogo da nuvem e só propõe a chamada; quem executa é o `Assistant`, com a mesma política de risco. Ferramenta inventada é descartada; chamada sem argumento obrigatório e "NAO_SEI" (informação atual) passam a vez para a nuvem.
- **Amostragem determinística** (topK 1, temperatura 0) e conversa nova por pedido: o mesmo pedido escolhe sempre a mesma ferramenta, sem histórico misturando pedidos.
- **GPU primeiro, CPU de reserva.** Se o driver recusar a GPU, o motor carrega na CPU. O modelo começa a carregar quando o cartão abre, enquanto a pessoa fala.
- **Benchmark de 100 comandos** em `core/src/main/resources/benchmark/comandos.tsv`: ações ditas de jeitos que as regras não cobrem, perguntas de conhecimento geral e perguntas que precisam de informação atual (o certo é passar a vez). Roda no app, em Testar.
- **Kotlin 2.4.20.** O LiteRT-LM 0.18 é compilado com Kotlin 2.4 e traz o `kotlin-stdlib` 2.4; o compilador 2.2 não lê esses metadados.
