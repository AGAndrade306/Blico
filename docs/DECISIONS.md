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
- **O modelo é baixado pelo app, não vai no APK.** Alguns GB não cabem num APK. O download usa o DownloadManager do Android (continua com o app fechado e retoma; espera o Wi-Fi, mas a tela explica a espera e deixa liberar os dados móveis) e o endereço é preso a um commit do Hugging Face, para o arquivo ser sempre o testado.
- **Ordem: regras, depois Gemma, depois nuvem.** A nuvem só entra se estiver configurada e se a pessoa deixar ("Perguntar à nuvem" ou "Só no celular"). O modelo local não passa pelo Redactor porque nada sai do aparelho.
- **Chamada de ferramenta nativa, em modo manual.** O Gemma recebe o mesmo catálogo da nuvem e só propõe a chamada; quem executa é o `Assistant`, com a mesma política de risco. Ferramenta inventada é descartada; chamada sem argumento obrigatório e "NAO_SEI" (informação atual) passam a vez para a nuvem.
- **Amostragem determinística** (topK 1, temperatura 0) e conversa nova por pedido: o mesmo pedido escolhe sempre a mesma ferramenta, sem histórico misturando pedidos.
- **GPU primeiro, CPU de reserva.** Se o driver recusar a GPU, o motor carrega na CPU. O modelo começa a carregar quando o cartão abre, enquanto a pessoa fala.
- **Benchmark de 100 comandos** em `core/src/main/resources/benchmark/comandos.tsv`: ações ditas de jeitos que as regras não cobrem, perguntas de conhecimento geral e perguntas que precisam de informação atual (o certo é passar a vez). Roda no app, em Testar.
- **Kotlin 2.4.20.** O LiteRT-LM 0.18 é compilado com Kotlin 2.4 e traz o `kotlin-stdlib` 2.4; o compilador 2.2 não lê esses metadados.

## 2026-10-14 — Visual no padrão Apple (substitui a risografia)

André não gostou do rosa nem dos cantos de pílula e pediu algo mais próximo da Apple.

- **Lista agrupada do iOS:** fundo `#F2F2F7`, cartões brancos sem borda nem sombra, cantos de 12 dp (20 dp no cartão do agente), botões retangulares de cantos suaves. No escuro, preto e `#1C1C1E`.
- **Uma cor de destaque só:** o verde do logo. O rosa saiu da interface; continua só no logo e no ícone, que são do André.
- **Inter no lugar de SF Pro:** a SF só pode ser usada em plataformas da Apple. A Inter (OFL) tem o mesmo desenho e foi empacotada em quatro pesos, cortada para o alfabeto latino. A escala segue a do iOS: título grande 34, título 22, destaque 17, texto 17, nota 13.
- Os componentes ficam em `app/ui/Theme.kt` (`BlicoTheme`, `BlicoCard`, `BlicoButton`, `BlicoChip`, `BlicoMark`, `BlicoProgress`, `LargeTitle`).

## 2026-10-14 — Ligar e enviar no WhatsApp até o fim (Etapa 4)

André pediu para não ter que tocar em "enviar" no WhatsApp nem no botão de ligar.

- **Um toque continua obrigatório, no cartão Confirmar do Blico** (regra 2). Ele substitui o toque no app: o cartão mostra o nome, o número e a mensagem, todos tirados da chamada estruturada (`Tool.summarize`), e só depois disso o Blico liga ou envia.
- **Duas ferramentas de classe 2 que os modelos não enxergam:** `whatsapp_enviar` e `ligar_agora`. Elas só nascem de `whatsapp_preparar` e `ligar` depois que o contato vira um número (`ToolResult.Then`), e o passo seguinte passa pela `RiskPolicy` como qualquer chamada. Um passo só, sem encadear mais.
- **Ligação pelo `TelecomManager.placeCall`, com a permissão `CALL_PHONE`.** O Blico não precisa ser o app de telefone padrão para começar uma ligação; ser o discador padrão só serviria para atender ou trocar a tela de chamada, o que não foi pedido. Sem a permissão, o número vai para o discador, como antes.
- **WhatsApp pela acessibilidade, com escopo mínimo.** O WhatsApp não tem API para enviar de um app pessoal; o caminho é abrir a conversa pelo link oficial (`api.whatsapp.com/send`, com o texto) e tocar em enviar. O serviço `BlicoAccessibilityService` só recebe eventos do WhatsApp, só age numa conversa que o Blico acabou de abrir, só toca se o texto na caixa for exatamente o confirmado (`DirectSend.sameText`) e confere que a caixa esvaziou. O texto da tela nunca vai para um modelo.
- **Botão enviar pelo id `com.whatsapp:id/send`**, com a descrição "Enviar" como reserva, e a caixa pelo id `com.whatsapp:id/entry`. São os ids públicos do WhatsApp há anos; se mudarem, o Blico não envia e deixa a mensagem pronta, avisando por voz.
- **Ligado por padrão quando a pessoa libera** (permissão ou acessibilidade), com a opção "Só deixar pronto" no cartão da tela inicial.
- **No Android 13+ um APK instalado fora da loja precisa de "Permitir configurações restritas"** antes de ligar a acessibilidade; a tela explica o caminho.

## 2026-10-15 — Agentes: laço próprio no `core`, sem framework

André pediu agentes configuráveis no estilo do OpenClaw e do Hermes. O plano completo está em `plano/agentes.md` (pasta do projeto).

- **Sem LangChain, CrewAI, OpenClaw ou Hermes no app.** São Python ou Node, pensados para um computador, e o OpenClaw e o Hermes recebem ordens por apps de mensagem (regra 6). Deles vêm só os formatos: agentes e skills em Markdown e memória em arquivos. O Koog (JetBrains, Kotlin, roda no Android) fica como plano B se o laço próprio crescer demais; não entrou agora porque traria o próprio formato de ferramenta e teríamos de reencaixar a política de risco, o cartão e o mascaramento.
- **`AgentRunner` no `core`**: pede o próximo passo ao cérebro, executa pelo `Assistant.execute` (mesma `RiskPolicy`), devolve o resultado e repete até uma resposta, uma falha ou o limite de passos (padrão 5, teto 10). Um passo de cada vez. Classe 2 e escolhas pausam o laço (`AgentResult.paused`) e ele só continua com `resume` depois do toque; recusar o cartão encerra o agente.
- **Só modelos no laço, sem as regras**: as regras entenderiam o mesmo pedido de novo a cada passo.
- **O agente só usa as ferramentas que lista.** Fora delas, a chamada é recusada. As que só nascem de outra pelo cartão (whatsapp_enviar, ligar_agora) entram porque vêm do toque em Confirmar.
- **Falha encerra o agente**, sem replanejar sozinho: a pessoa vê o erro e decide. O mesmo passo duas vezes seguidas também encerra (modelo andando em círculo).
- **Resultados de ferramenta voltam ao cérebro como dado não confiável** (`UntrustedContent.wrap`), porque podem trazer nomes de arquivo, contatos ou texto lido do aparelho.
- **O servidor continua sem estado.** A cada passo o app manda o pedido, as instruções do agente e o histórico curto; o Worker monta os passos como `tool_use`/`tool_result` e as instruções vão no `system`. O modelo local recebe o mesmo histórico em texto (`LocalBrain.userMessage`).
- **Mascaramento numa passada só** (`Redactor.redactAll`): pedido, contexto, instruções e passos usam os mesmos apelidos, para o `telefone_1` do histórico ser o mesmo do pedido. Isso também corrige a colisão de apelidos que existia entre o pedido e o contexto.

## 2026-10-15 — Agentes em arquivos Markdown

- **Um arquivo por agente em `files/agentes/` (pasta privada do app)**, com cabeçalho curto (`nome`, `descricao`, `gatilhos`, `ferramentas`, `max_passos`) e as instruções em texto livre abaixo, no estilo das skills do OpenClaw e do Hermes. O formato é lido no `core` (`AgentFile`) e testado sem aparelho. Campo desconhecido é erro, para um erro de digitação não virar silêncio.
- **Sem cifra por enquanto.** Os arquivos ficam na área privada do app, que só o Blico lê; a cifra entra junto com a memória em arquivos, para as duas usarem o mesmo esquema.
- **Como um agente é chamado:** "agente <nome>, ..." (ou "usa o agente <nome>") ou um dos gatilhos dito no pedido, como palavra inteira, sem diferença de acento ou caixa. Sem agente, o pedido segue o caminho de sempre (regras, Gemma, nuvem).
- **Dois agentes de exemplo criados na primeira vez** (`manha` com alarme e timer, `avisos` com WhatsApp), para servir de modelo. Podem ser editados ou apagados na tela.
- **Tela de Agentes** em Personalizar: lista, edição do arquivo inteiro com validação ao salvar, novo agente a partir de um modelo e um teste digitado que mostra cada passo e o cartão Confirmar.

## 2026-10-17 — Equipes de agentes e tela própria

André pediu uma tela só para os agentes, com agentes que conversam entre si e fazem tarefas juntos.

- **Equipe é um campo do agente (`equipe: [manha, avisos]`), não um conceito à parte.** Qualquer agente com equipe vira líder: cada membro aparece para ele como uma ferramenta `agente_<nome>` com um parâmetro `pedido`. O membro roda o próprio laço, só com as ferramentas dele, e a resposta volta ao líder como dado não confiável. Assim um agente nunca ganha as ferramentas do outro.
- **Um nível só.** Membros não chamam a equipe deles: evita ciclos e cadeias longas, que custam passos e chamadas à nuvem.
- **O Confirmar continua valendo dentro da equipe.** Se um membro para no cartão, a equipe inteira para (`AgentRun.delegation`) e continua do mesmo ponto depois do toque.
- **Falha de membro não encerra a equipe:** o líder recebe "não conseguiu" e decide o próximo passo. Falha do próprio líder encerra, como antes.
- **`AgentEvent`** descreve a conversa (quem pediu a quem, ferramentas usadas, respostas, espera pelo toque, uso da nuvem) para a tela mostrar como um grupo. O aviso de cérebro só aparece quando o passo foi para a nuvem.
- **Tela própria em `app/agents/`** (`AgentsScreen`, pronta para virar aba): equipes no topo, lista de agentes, editor por campos (nome, o que faz, gatilhos, ferramentas, equipe, passos, instruções) com a opção de editar o arquivo inteiro, e uma conversa em balões com o cartão Confirmar no meio. Enquanto não há abas, abre por `AgentsActivity`, a partir de um cartão na tela inicial. A casca de navegação e o `Theme.kt` ficam com o redesenho visual.
- **Exemplos novos aparecem em atualizações** (`files/agentes/.exemplos` guarda os já criados), e um exemplo apagado não volta. Entrou o `organizador`, líder de `manha` e `avisos`.
- **O Worker aceita no histórico ferramentas fora do catálogo** (como `whatsapp_enviar`) com uma descrição mínima, para cada `tool_use` ter a sua definição; elas não podem ser chamadas.

## 2026-10-15 — Busca no DuckDuckGo e benchmark visível

- **Pesquisas vão para o DuckDuckGo**, por privacidade: ele não monta perfil de quem pesquisa. Abre no app do DuckDuckGo se estiver instalado; senão, no navegador padrão. Antes, a busca ia para o app de busca do sistema (o Google).
- **O benchmark roda no escopo do app, não da tela** (`BenchmarkRunner`). Com o Gemma, 100 pedidos levam minutos; antes, sair da tela cancelava sem mostrar nada. Agora os resultados aparecem um a um (✓ ou ✗, o que veio e o tempo), e o resumo e os erros do último benchmark completo ficam guardados.

## 2026-10-17 — Conversa em grupo com os agentes

Inspirada no Grok Bot da xAI, que o André mostrou: vários agentes na mesma conversa, passando trabalho entre si.

- **@nome escolhe quem fala** (`Mentions.split`): "@manha alarme às 6 @avisos avisa o João" vira um pedido para cada um, na ordem. Sem @, vai para o agente escolhido embaixo da conversa. Acento e caixa não importam.
- **Um agente passa a vez citando @outro na resposta** (`GroupRunner`), até 3 vezes por mensagem sua, para dois agentes não conversarem sem fim gastando a nuvem.
- **Cada agente vê as últimas 12 falas como dado não confiável** (`UntrustedContent`, origem `conversa`) e age só com as próprias ferramentas. O que um agente pede a outro pode vir de algo que ele leu; por isso o envio e a ligação continuam presos ao Confirmar, que para o grupo inteiro e continua do mesmo ponto.
- **Do Grok Bot ficam de fora**, por privacidade (regras 5 e 6): o computador na nuvem ligado 24 horas, o login dos agentes nos seus sites e o aprendizado vendo a sua tela. Rotinas agendadas e papéis prontos entram depois, cada um no seu PR.

## 2026-10-17 — Vozes neurais no celular

A voz do sistema soava robótica.

- **sherpa-onnx 1.13.8 como motor** (Apache 2.0): roda Kokoro e Piper no aparelho, sem internet. O AAR oficial não está no Maven Central; o `settings.gradle.kts` baixa da release do GitHub, confere o SHA-256 e serve de um repositório Maven local em `.thirdparty/` (fora do git).
- **Duas famílias de voz.** Kokoro v1.0 int8 (132 MB, três vozes em português: Dora, Alex e Santa) é a mais natural, mas gera devagar: cerca de 1 s de processamento por segundo de fala num servidor de 4 núcleos. Piper (67 MB por voz) gera em 5% do tempo da fala. Ficaram de fora a Edresson (sem o til nasal no vocabulário, o "ão" sai errado) e as vozes de Portugal.
- **Fala frase por frase.** O texto é quebrado em frases (`NeuralVoices.chunks`) e a primeira toca enquanto a próxima é gerada, numa thread só do motor. Isso esconde a lentidão do Kokoro em respostas curtas.
- **Pacotes baixados pelo DownloadManager**, com os dados móveis liberados (são bem menores que o Gemma), e abertos no celular só com o que o português usa. A extração recusa caminhos que saem da pasta da voz, no `core` (testado) e de novo no caminho resolvido pelo sistema.
- **A voz do sistema continua como reserva**: sem voz neural baixada e escolhida, o Blico fala com o motor do Android, como antes.
- **APK só com arm64-v8a.** O sherpa-onnx traz 120 MB de bibliotecas nativas para quatro arquiteturas; o S25 Ultra usa uma só.

## 2026-10-17 — Login nos serviços: Google primeiro, pelo Play Services

O André quer que os agentes entrem nas contas dele. A escolha foi o Google (Agenda e Gmail) primeiro.

- **O login é o do próprio Android** (`AuthorizationClient` do Play Services, em `GoogleLogin`). A pessoa escolhe a conta e aceita os acessos numa tela do Google; o Blico recebe só um acesso de uma hora, que fica na memória e é renovado em silêncio. Não há senha, segredo de cliente nem ID no código: o Google reconhece o app pelo pacote `app.blico` e pela assinatura.
- **Assinatura fixa no CI**, vinda dos segredos do repositório (`BLICO_KEYSTORE_*`). Antes, cada build saía com uma chave de debug nova, e o Google recusaria o login. Sem os segredos, o build continua com a chave de debug padrão.
- **O Blico fala direto com o Google**, sem passar pelo servidor próprio, que segue sem guardar nada de ninguém. O que vem da Agenda e do Gmail é dado não confiável no laço dos agentes e passa pelo `Redactor` antes de ir para a nuvem (regras 3 e 5). Enviar o e-mail ou criar o evento na conta da pessoa é a própria ação pedida, e por isso passa pelo Confirmar em vez do mascaramento.
- **Classes de risco:** ver agenda e ler e-mails são leitura (0); rascunho no Gmail é preparar (1); criar evento e enviar e-mail são compromisso (2) e param no Confirmar, com o texto montado da chamada.
- **Ferramentas próprias sobre a API REST do Google, e não um servidor MCP**: a API é estável e documentada, e o celular fala com ela direto, sem precisar de outro servidor no meio. Sem login, as ferramentas nem aparecem aos modelos.
- **Cliente MCP próprio e mínimo no `core`** (`McpClient`, `McpTool`) para os próximos serviços, como o Notion: initialize, tools/list e tools/call por Streamable HTTP, além do OAuth com PKCE (`OAuth`). Ficou no lugar do `kotlin-sdk` porque o `core` não depende de Ktor e esse subconjunto é pequeno e testável com fakes. Uma ferramenta de servidor MCP só executa direto se o servidor a marcar como leitura (`readOnlyHint`); o resto para no Confirmar. A descrição vinda do servidor é cortada e limpa, porque pode tentar dar ordens ao modelo.
- **Novo exemplo `secretaria`**, que só usa as ferramentas do Google e prefere deixar rascunho a enviar.

## 2026-10-17 — Visual "Painel" e abas

André achou o app com cara de genérico e escolheu a direção "Painel" entre três propostas.

- **Três abas: Início, Agentes e Ajustes** (`Shell.kt`). O Início virou o assistente: um bloco escuro para falar (o microfone só abre no toque, e fecha quando o app sai da frente), um campo para digitar, o próximo alarme do sistema, quantas ações o Blico fez hoje e a lista do que ele fez. A configuração que era a tela inicial foi para Ajustes. Agentes é a aba da conversa com agentes, que vem do pacote `app.blico.agents`.
- **Tipografia:** Doto (matriz de pontos) só na marca, nos títulos das abas e nos números grandes; IBM Plex Sans no texto; IBM Plex Mono nos rótulos, nas opções e na lista de atividade. As três são OFL, cortadas para o alfabeto latino, em pesos fixos. A Inter saiu.
- **Cores:** fundo cinza-pedra, cartões brancos como widgets, botão principal em tinta escura e o verde do logo só para o que está vivo (status, progresso, pronto). O app agora segue o modo escuro do sistema, com a mesma paleta em tons escuros.
- **Atividade só no celular** (`ActivityLog`, testado no `core`): até 30 linhas com a frase que o Blico falou e a hora, só de ações concluídas, num arquivo de preferências do app (sem backup). Dá para limpar na própria lista.
- Os componentes continuam em `app/ui/Theme.kt` com as mesmas assinaturas, mais `ScreenHeader`, `SectionLabel`, `DashedRule`, `LiveDot`, `DottedRing` e `BlicoAvatar` (cor tirada do nome, ou passada).

## 2026-10-17 — ASCII no lugar dos pontinhos

André achou os pontos (fonte Doto, anel pontilhado, progresso em pontos) parecidos demais com o visual da Nothing e pediu ASCII, mantendo a diagramação do Painel.

- **Mesma diagramação, outro acabamento:** os widgets, o bloco escuro, as abas e a lista de atividade continuam no lugar.
- **Marca em letreiro ASCII** (figlet "small"), títulos de aba em IBM Plex Mono sublinhados com `=`, seções como `// ATIVIDADE`, aba aberta como `[ INÍCIO ]`, status `[*]`, marcas `[x]` e `[ ]`, progresso `[#####.....]` que ocupa a largura disponível.
- **O botão de falar é um microfone em ASCII que vira um equalizador** de barras `|` enquanto o Blico ouve.
- **A Doto saiu**; números grandes ficam em IBM Plex Mono SemiBold. O texto corrido continua em IBM Plex Sans.

## 2026-10-17 — Muletas da fala fora do pedido

André disse "pesquisa pra mim qual é a capital da Dinamarca" e o DuckDuckGo abriu com "para mim qual é a capital da Dinamarca": a regra de busca pegava tudo depois do verbo.

- **Muletas no `RuleRouter`, logo depois do verbo** ("pra mim", "para mim", "pra gente", "aí", "por favor", "rapidinho", "agora", "já"), em todas as regras e não só na busca. Ali elas nunca são conteúdo, então podem sair sem risco.
- **Nas pontas do valor só sai um conjunto menor** ("pra mim", "aí", "por favor", "rapidinho"): "abre o WhatsApp pra mim" vira "WhatsApp", mas "notícias de agora" fica inteira.
- **O texto de uma mensagem não é limpo**: "manda pro João que ele traga o livro pra mim" leva "ele traga o livro pra mim", porque é a fala da pessoa para outra pessoa.
- **Mais jeitos de pedir**: "você pode/poderia", "dá pra", "tem como", "quero que você", "me faz um favor", infinitivos ("pesquisar", "mandar", "me acordar") e "dá uma pesquisada". "aí" sem acento ficou de fora por colidir com "AI".

## 2026-10-17 — Respostas direto no card

André pediu que pergunta pontual seja respondida no card, sem mandar para o navegador, e que pesquisa ampla abra o navegador e ainda deixe um resumo no card.

- **Pontual ou ampla** (`WebQuery`, no `core`): regra simples sobre a consulta. Conta, "qual", "quanto", "quem", "quando", "o que é", "capital de", "cotação de" são pontuais; "receita", "dicas", "melhores", "como fazer", "história", "notícias" e o que não se encaixar são amplos. Na dúvida fica ampla, que ao menos abre o navegador como antes.
- **Pontual**: primeiro o Gemma do celular; se ele disser que não sabe, a resposta instantânea do DuckDuckGo (`api.duckduckgo.com`, sem conta nem chave). Se nenhum souber, abre o navegador como antes. O card mostra a fonte e "Ver no navegador".
- **Ampla**: abre o navegador primeiro e, enquanto ele carrega, o card pede ao Gemma um resumo raso ou por onde começar, usando o trecho do DuckDuckGo como referência. O cartão fica por cima do navegador até a pessoa fechar.
- **Privacidade**: a consulta só vai para o DuckDuckGo se o `Redactor` não achar nada pessoal nela (nome da agenda, endereço cadastrado, telefone, e-mail). O trecho que volta entra no prompt por `UntrustedContent.wrap`. A nuvem não entra nesse caminho.
- **Pergunta que ninguém entendeu** (o Gemma disse que não sabe e a nuvem está desligada ou não decidiu): se parece pergunta, vira busca em vez de "Ainda não sei fazer isso".
- **Card de resposta**: o que a pessoa disse fica pequeno em cima, a resposta é o destaque (rolável quando é longa), com a fonte em mono, "Copiar" e o caminho para o navegador. As métricas do microfone desceram para o rodapé. O tema (pontos ou ASCII) é assunto de outra etapa e não muda aqui.

## 2026-10-18 — Pergunta pontual sem Gemma nem nuvem

No teste do André, "qual a capital da Mongólia" abriu o navegador. Sem o Gemma pronto e sem a nuvem, a única fonte do card era a resposta instantânea do DuckDuckGo, que quase nunca responde pergunta em frase.

- **Página de resultados do DuckDuckGo como segunda fonte** (`html.duckduckgo.com`, sem JavaScript, sem conta): o card mostra o trecho do primeiro resultado que não é anúncio, com o site de onde veio. Passa pelo mesmo filtro do `Redactor` antes de sair.
- **Com o trecho em mãos, o Gemma tenta de novo** e responde em uma frase, com o trecho entrando como dado não confiável. Sem o Gemma, o card mostra o trecho como veio.
- O navegador só abre quando nenhuma das fontes tem nada.

## 2026-10-18 — Calma luminosa, aurora e ícone vivo

André aprovou a direção "Calma luminosa" (thread "Revisão e plano do Blico") e pediu que o card do botão lateral tivesse movimento, como a borda do Gemini e o ícone animado do Claude. A diagramação Painel e o ASCII continuam.

- **Paleta com significado** (`Theme.kt`): fundo de argila clara `#EFE9E3`, tinta `#1E1B19`, verde `#2F7A4F` só para o que roda no celular, azul `#3155E8` para ouvindo, pensando e nuvem, laranja `#B9461A` só no Confirmar e em alertas. O escuro é grafite quente `#1A1715`, sem preto puro. O sublinhado `=` dos títulos saiu do verde, que agora quer dizer "no celular".
- **Selo de origem** (`Origin`, no `core`, com teste): todo resultado leva `[celular]`, `[nuvem]`, `[web]` ou `[google]`, no card e na atividade. O selo diz para onde o dado foi, então vale o que mais saiu: serviço, depois nuvem, depois web. `[web]` aparece quando a busca do DuckDuckGo saiu do aparelho, mesmo que o Gemma tenha escrito a frase. Ferramentas MCP ainda saem como `[celular]` ou `[nuvem]`, porque o nome delas não diz o serviço; fica para quando o registro souber.
- **Aurora** (`AuroraEdge`): um gradiente cônico gira numa faixa na borda da tela, acompanhando o canto real do aparelho, com um brilho que respira por dentro. Repouso não tem luz; ouvindo é azul com verde e gira mais rápido; no celular é verde; nuvem e web são azuis; o Confirmar é laranja e pulsa. Com "Remover animações" ligado, a luz fica parada.
- **Ícone vivo** (`BlicoEyes`): dois olhos num anel que gira na cor do estado. Piscam em repouso, viram barras de voz ao ouvir, olham para cima pensando e na nuvem, pulsam no Confirmar.
- **Sem desfoque da tela de trás**: o Android só desfoca a janela inteira, e isso esconderia o app que o André quer ver por trás do card. O card ficou 92% opaco, com borda fina, e o véu escuro caiu de 25% para 8%.
- **Confirmar à prova de sobreposição** (P0-5, combinado com o thread de revisão): o botão recusa o toque quando outra janela cobre o Blico, mesmo em parte, e explica por quê; a janela do botão lateral ignora toques quando está toda coberta; enquanto o cartão está aberto, a janela fica fora de capturas de tela (`FLAG_SECURE`), no botão lateral e no app.
- **Passos do agente como recibo**: cada ferramenta usada vira uma linha `agenda_ver ........ ok` em mono, e a que espera o Confirmar fica em laranja com "seu toque". A sua mensagem na conversa passou do verde para a tinta.

## 2026-10-18 — Mascaramento de documentos, cartão, CEP e PIX

A revisão de privacidade (`plano/revisao-e-plano.md`, pasta do projeto) achou que o `Redactor` só conhecia contatos, endereços, telefones e e-mails.

- **Novos apelidos:** `cpf_N`, `cnpj_N`, `cartao_N`, `cep_N` e `chave_pix_N`, na mesma passada dos outros, e restaurados na volta.
- **Ordem do mais específico ao mais genérico** (chave PIX, e-mail, CNPJ, CPF, cartão, CEP, telefone), para um CPF ou cartão nunca virar "telefone" pela metade.
- **Sem pontuação, só com dígito verificador.** CPF e CNPJ com pontuação são sempre mascarados; 11 ou 14 dígitos seguidos só quando o verificador confere, e cartão só quando passa no Luhn. Assim um número de pedido ou protocolo não some do texto.
- **CEP só com hífen:** oito dígitos seguidos se confundem com telefone e número de pedido.
- **Chave PIX aleatória é um UUID**; e-mail e telefone como chave PIX já eram mascarados.
- Como o `WebAnswers` só manda ao DuckDuckGo consultas em que o `Redactor` não acha nada, consultas com esses dados também deixam de sair.

## 2026-10-18 — Dado pessoal lido por um agente não sai sem o Confirmar

Achado da revisão: um agente lia um e-mail (`gmail_ler`, classe 0) e, a pedido do próprio texto do e-mail, chamava `buscar_web` ou uma ferramenta MCP marcada como leitura, que executavam direto e mandavam o argumento para o DuckDuckGo ou para o servidor do serviço. O `Redactor` não pega códigos nem textos inteiros.

- **Duas marcas novas em `Tool`:** `egress` (manda os argumentos a um terceiro fora do aparelho e fora das contas da pessoa: `buscar_web`, `abrir_pesquisa`, toda ferramenta MCP) e `readsPrivate` (devolve dado pessoal: `agenda_ver`, `gmail_ler`, `buscar_arquivos`, toda ferramenta MCP).
- **O agente fica marcado** (`AgentRun.privateData`) a partir do primeiro resultado de uma ferramenta `readsPrivate`, ou quando um membro da equipe que leu dado pessoal responde. Na conversa em grupo, a marca passa aos agentes seguintes, porque o que o primeiro disse vai no contexto deles.
- **Marcado, toda ferramenta de saída para no Confirmar**, mesmo sendo leitura, com os argumentos à vista no cartão (texto da chamada, regra 2). Sem marca, nada muda: a pesquisa de um agente de pesquisa segue direta.
- **Agenda e Gmail não são saída**: falam com a conta da própria pessoa, e o que escrevem já é classe 2.
- **Pedido direto, fora de agente, não muda**: ali o argumento vem da fala da pessoa, não de algo lido.

## 2026-10-18 — Nada sai na cópia entre aparelhos

- **`data_extraction_rules.xml` exclui todos os domínios** do backup na nuvem e da cópia de celular para celular. No Android 12+, `allowBackup="false"` desliga só o backup na nuvem; o Smart Switch levaria agentes, lugares, atividade e preferências para outro aparelho. Os segredos cifrados nem funcionariam lá (a chave do Keystore não viaja), mas o resto iria em claro.

## 2026-10-18 — Funcionalidades em pacotes e o pacote Sistema

André trouxe um catálogo de funcionalidades de um assistente líder de mercado e pediu que o Blico fosse estruturado para recebê-las sem quebrar. O mapa de lacunas e a ordem de entrega estão em `docs/FUNCIONALIDADES.md`.

- **`Capability` no `core`**: cada área (sistema, memória, agenda, notificações) traz as próprias ferramentas, regras de fala, acessos e disponibilidade, em arquivo próprio. Antes, toda funcionalidade mexia em `builtinTools()`, na lista do `RuleRouter` e no `when` do `AndroidDeviceGateway`, e dois threads colidiam.
- **Portas por pedido em `ExecContext.ports`**: o que é do Android fica atrás de uma interface, buscada por tipo. Assim a porta usa quem abre as telas naquele pedido (o botão lateral ou o app), e uma porta nova não muda o construtor do `ExecContext`.
- **Regras das funcionalidades antes das de base**, com teste de regressão sobre todas as frases do `RuleRouterTest` e os 100 comandos do benchmark: nenhuma muda de destino. "abre as configurações" é a única que muda de propósito (vai para `abrir_ajuste`), e o benchmark foi ajustado.
- **Desligar cada funcionalidade** em Ajustes › Funcionalidades (item 21 e 22 do catálogo). Desligada, as ferramentas somem dos modelos e a frase dita responde que está desligada.
- **`DeviceResult.NeedsPermission`**: falta de acesso vira fala com o nome do acesso e o porquê, e a tela oferece "Liberar".
- **Pacote Sistema**: bateria, armazenamento e conexão (com sugestão quando algo está ruim), lanterna, volume (mídia, toque, alarme), modo de som (normal, vibrar, silencioso, Não perturbe), brilho e 24 telas de Configurações. Wi-Fi, Bluetooth, NFC e modo avião o Android 10+ não deixa um app ligar: o Blico abre o painel (por cima do app atual quando existe) e diz isso. Silencioso e Não perturbe pedem o acesso especial ao Não perturbe; brilho pede "modificar configurações do sistema". Nenhuma permissão perigosa nova.
- **Riscos**: ler estado é classe 0; lanterna, volume, som e brilho são classe 1 (mudam o aparelho, se desfazem com outra frase), como alarme e timer.

## 2026-10-18 — Memória pessoal

Fase 1 do catálogo (item 4) e P1-4 do plano de revisão.

- **Por voz**: "lembra que…" guarda, "o que você sabe sobre…" consulta, "esquece que…" apaga. "Me lembra de…" fica livre para os lembretes com hora.
- **Guardar e esquecer passam pelo Confirmar** com o texto exato do fato. Assim nada entra na memória sem a pessoa ver, nem um fato plantado por um e-mail que um agente leu. Esquecer acha o fato primeiro e, com vários parecidos, pergunta qual.
- **Sensível é marcado**: documentos, telefones e e-mails (pelo `Redactor`) e assuntos como senha, saúde, dinheiro e crença. O cartão avisa antes de guardar.
- **Cifrada no aparelho** (`EncryptedMemoryStore`): AES-GCM com chave própria no Keystore, num arquivo do app que já fica fora de backup e de cópia entre aparelhos. Se a chave se perder (app reinstalado), a memória começa vazia em vez de quebrar.
- **Só o Gemma do celular lê**: `MemoryAwareProvider` leva até três fatos ligados ao pedido como contexto, para "manda pra minha irmã" achar a Ana. A nuvem nunca recebe a memória. Consultar marca o pedido como dado pessoal, então uma ferramenta de saída depois disso para no Confirmar.
- **O contexto agora chega ao Gemma**: `LocalBrain.userMessage` passou a incluir `LlmRequest.context`, que antes só a nuvem lia. A conversa em grupo também se beneficia.
- **Tela em Ajustes** para ver, acrescentar e apagar fatos, um por um ou todos. Ali quem digita ou toca é a própria pessoa, então vale direto.

## 2026-10-18 — Agenda do celular e lembretes com hora

Fase 1 do catálogo (itens 6 e 7).

- **Agenda pelo CalendarContract**, sem login: as contas sincronizadas no aparelho (Google, Samsung, Outlook) já aparecem ali. Ver o dia ou a semana, horários livres entre 8h e 20h e conflitos são classe 0 e marcam o pedido como dado pessoal. Criar compromisso é classe 2: o cartão mostra título, quando, duração e local, e a resposta avisa se bate com outro. As ferramentas do Google continuam para quem entrou na conta.
- **Lembretes com hora pelo AlarmManager**, avisados por notificação. "Me lembra…" com hora ou prazo vira lembrete; sem hora, fica para o modelo. A lista fica nas preferências do app para listar, cancelar e remarcar depois de reiniciar o celular. `USE_EXACT_ALARM` porque o Blico não vai para a Play Store e lembrete é uma das funções dele; sem ele, o aviso chega perto da hora.
- **`PtWhen` no `core`**: "amanhã às 9", "daqui a 20 minutos", "sexta às 15h", "dia 25 ao meio-dia", "hoje à noite". As regras separam o quando do resto sem relógio, e a ferramenta resolve com o relógio do pedido. Hora sem dia que já passou de manhã vira a mesma hora da tarde ("às 3", às 10h, é 15h); com "da manhã" ou 13h em diante, vai para amanhã.
- **Permissões** (agenda e notificações) aparecem em Ajustes › Funcionalidades com o porquê; negadas de vez, o botão abre os dados do app.

## 2026-10-18 — SMS com Confirmar

- **"manda um SMS pro João dizendo…"** acha o contato como o WhatsApp faz. Com a permissão de SMS dada em Ajustes › Funcionalidades, o próximo passo é enviar, que passa pelo cartão Confirmar com o número e o texto. Sem a permissão, abre o app de mensagens com o texto pronto.
- **Só quando a pessoa diz SMS** (ou "mensagem de texto"): "manda mensagem pro João" continua indo para o WhatsApp.

## 2026-10-18 — Ferramentas por pedido

Com os pacotes, os modelos passariam de 20 para mais de 35 ferramentas, e o Gemma de 4B erra mais com prompt longo (o servidor também recusa acima de 64).

- **Cada funcionalidade diz suas palavras-chave** (pedaços sem acento: "lanterna", "bateria", "agenda", "lembr", "sms"). Se nenhuma aparece no pedido, as ferramentas dela ficam fora do prompt daquele pedido. As ferramentas de base e as do Google e do MCP ficam sempre.
- **Só no pedido direto**: os agentes continuam vendo as ferramentas que o perfil deles libera, e o benchmark mede com todas.
- As regras não mudam: elas já entendem as frases comuns sem modelo.
