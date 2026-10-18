# Funcionalidades: o catálogo contra o Blico

18 out 2026. Revisão do "Catálogo de funcionalidades para um assistente virtual de celular de nível mundial" (escrito pelo ChatGPT a pedido do André) contra o que o Blico faz em `main`. Complementa `plano/revisao-e-plano.md` (pasta do projeto), que continua valendo para privacidade e para o que outros threads já estão fazendo.

Legenda: **tem** (funciona hoje), **parte** (existe, mas incompleto), **falta**, **fora** (as regras do projeto ou o Android não deixam; o motivo vem junto).

## 1. Resumo

O Blico já cobre bem o núcleo de "comando de voz que vira ação": abrir apps, mensagem e ligação com Confirmar, transporte, alarme, timer, música, navegação, busca com resposta no card, arquivos, Agenda e Gmail pela conta Google, agentes com equipes e MCP. O catálogo pede três coisas que ainda não existem e que mudam a categoria do produto:

1. **Contexto pessoal**: memória, agenda e lembretes do próprio aparelho, notificações e mensagens recebidas.
2. **Controle do celular**: ajustes do sistema, entender a tela atual, preencher campos.
3. **Autonomia com acompanhamento**: planos de várias etapas com validação, rotinas, tarefas em segundo plano e um botão de parar.

Das 25 áreas, 7 estão por fazer (memória, lembretes, câmera, finanças, casa, saúde e proatividade) e as outras têm só parte do que o catálogo pede. Três itens ficam fora pelas regras do projeto: palavra de ativação (regra 1: microfone só depois de um gesto), automação de bancos e pagamentos (regra 4) e canais remotos como entrada (regra 6).

## 2. Lacunas por área

| # | Área | Estado | O que já tem | O que falta (prioridade) |
| --- | --- | --- | --- | --- |
| 1 | Controle do celular | parte → **tem o básico neste PR** | Abrir apps, arquivos | Lanterna, volume, brilho, Não perturbe, bateria, espaço, conexão e telas de Configurações (**este PR**). Depois: notificações (ler, resumir, urgentes), fechar e alternar apps (Accessibility). Wi-Fi, Bluetooth e modo avião o Android 10+ não deixa ligar sozinho: o Blico abre o painel e diz isso |
| 2 | Comunicação e contatos | parte | Ligar, WhatsApp e SMS com Confirmar, achar contato | Ler mensagens recebidas pelas notificações (F2), sugerir resposta para aprovar (F2), lembrar de responder (F4). Criar e editar contatos (F2). Atender ligação (F3, permissão ANSWER_PHONE_CALLS) |
| 3 | WhatsApp e redes | parte | Enviar com Confirmar | Não há API oficial para ler conversas. Dá para trabalhar com o que chega por notificação (resumo, perguntas sem resposta, golpe em link) (F2). Buscar histórico antigo: **fora** sem API |
| 4 | Memória pessoal | **tem o básico** | Lugares; memória cifrada com lembrar, consultar e esquecer por voz, Confirmar em cada fato, aviso de sensível, tela para ver e apagar, o Gemma lê | Separar pessoal e profissional, aprender estilo de escrita (F4) |
| 5 | Voz | parte | Gesto do botão lateral, transcrição no aparelho, vozes neurais, parar a fala no gesto | Ida e volta curta na mesma sessão (P1-7 do plano), comandos compostos ("liga a lanterna e põe no silencioso") (F1), referências ("ele", "o segundo") com contexto da sessão (F3). Palavra de ativação: **fora** pela regra 1 |
| 6 | Agenda | **tem o básico** | Agenda do celular sem login: dia, semana, horários livres, conflitos, criar com Confirmar; Google Agenda com login | Resumo ao acordar e aviso de sair (F4), criar evento a partir de mensagem (F2) |
| 7 | Tarefas e lembretes | parte | Alarme, timer e lembretes com hora por notificação | Listas (compras, tarefas) e pendências do dia (F1), lembrete por lugar (F4) |
| 8 | E-mail e trabalho | parte | Ler, rascunhar e enviar no Gmail com Confirmar | Triagem do urgente, tom, extrair tarefas (F3, com os agentes). Microsoft 365, Slack, Teams: via MCP quando o André escolher |
| 9 | Web e pesquisa | parte | DuckDuckGo com resposta no card e fonte | Pesquisa com várias fontes e "não há evidência suficiente" (F3, nuvem). Monitorar preços: rotina (F4) |
| 10 | Câmera e visão | **falta** | Nada | Foto para o Gemma (ele é multimodal): ler texto, explicar, traduzir (F2, P1-10 do plano) |
| 11 | Arquivos | parte | Buscar, abrir, compartilhar e renomear com Confirmar | Arquivos grandes para liberar espaço (F2), resumir PDF compartilhado com o Blico (F1, "compartilhar para o Blico"), mover e apagar com Confirmar (F2) |
| 12 | Música e vídeo | parte | Tocar no Spotify (PR #23 leva ao player que a pessoa disser) | Pausar, pular e voltar na sessão de mídia ativa (F2, MediaSession, sem Accessibility) |
| 13 | Mapas | parte | Navegar no Maps, lugares com apelido, Uber e 99 | Postos e farmácias perto (F2), lembrete por lugar (F4) |
| 14 | Finanças | **falta** | Lista de bancos bloqueados | Calcular parcelas e juros (já dá pela pergunta), registrar gasto por voz (F3). Pagamentos e transferências: **fora** pela regra 4 |
| 15 | Casa inteligente | **falta** | Nada | Controles de dispositivos do Android ou Google Home (F4, depende do que o André tiver em casa) |
| 16 | Saúde | **falta** | Nada | Lembrete de remédio cai em lembretes (F1). Health Connect (F4). Sem diagnóstico, nunca |
| 17 | Tradução | parte | Pergunta ao Gemma ou à nuvem | Traduzir texto compartilhado ou da tela (F2) |
| 18 | Educação | parte | Perguntas e explicações | Quiz e plano de estudo como modelo de agente (F3) |
| 19 | Escrita | parte | Nuvem e agentes escrevem | Reescrever texto compartilhado com o Blico (F1, "compartilhar para o Blico") |
| 20 | Automação de apps | parte | WhatsApp enviar pela Accessibility, só nele | Entender a tela (`onHandleAssist`, F2), preencher campos (F4, Accessibility app a app) |
| 21 | Proatividade | **falta** | Nada | Rotinas agendadas com resultado em notificação, sem abrir o microfone (F4, P1-6 do plano), cada categoria com seu desligar e horário de silêncio |
| 22 | Segurança e privacidade | parte, forte | Confirmar com texto da chamada, `RiskPolicy`, lista de bancos, `UntrustedContent`, `Redactor`, segredos no Keystore, saída depois de dado pessoal no Confirmar | Registro de auditoria exportável (com o thread de revisão), botão de parar uma execução (F3), desligar cada funcionalidade (**este PR**) |
| 23 | IA avançada | parte | Regras, Gemma e nuvem em cascata, agentes com passos | Plano com pré-condições e validação, retomada após falha (F3); escolher ferramentas por pedido para o Gemma não se perder com muitas (F1, ver 3.4) |
| 24 | Diferenciais | parte | Agentes e equipes | Comando universal e tarefas autônomas vêm de F3. Continuidade entre aparelhos: **fora** por ora (só Android) |
| 25 | Métricas | parte | Benchmark de 100 comandos | Taxa de conclusão e de desfeitas por funcionalidade, a partir do registro de auditoria (F3) |

## 3. Arquitetura para receber tudo isso sem quebrar

O problema hoje: toda funcionalidade nova mexe nos mesmos três arquivos (`builtinTools()`, a lista `RULES` do `RuleRouter` e o `when` do `AndroidDeviceGateway`). Dois threads trabalhando ao mesmo tempo colidem, e uma regra nova pode roubar a frase de outra sem ninguém perceber. Este PR muda isso.

### 3.1 Funcionalidade como pacote (`core/capability`)

Cada área vira uma `Capability`, em arquivo próprio:

- `tools`: as ferramentas, que passam pela mesma `RiskPolicy` de sempre (classe de risco, lista de bancos, validação, Confirmar).
- `rules`: as frases entendidas sem modelo (`SpeechRule`), com os mesmos trechos das regras de base (`{F}` para "pra mim", "aí", "por favor"; `{PRO}`; `{TO}`; `{ART}`).
- `permissions`: os acessos que liberam partes dela, para a tela de Ajustes pedir com o porquê.
- `availability()`: pronta, falta permissão ou o aparelho não tem.

`Capabilities` junta todas, alimenta o `ToolRegistry`, o `RuleRouter` e a tela "Funcionalidades" em Ajustes. Uma funcionalidade nova é um arquivo no `core`, um no `executor` e uma linha no `BlicoApp`.

### 3.2 Portas por pedido (`ExecContext.ports`)

O que é do Android fica atrás de uma interface (a "porta"), com fake nos testes. As que precisam de quem abre telas (o botão lateral ou o app) chegam por `ExecContext.ports`, um mapa por tipo: `ctx.ports.get<SystemPort>()`. Porta nova não muda o construtor do `ExecContext` nem quem o chama. Sem a porta, a ferramenta explica em vez de quebrar.

### 3.3 Proteções contra quebrar

- **Regras das funcionalidades rodam antes das de base** ("liga a lanterna" não pode virar ligação para "a lanterna"), e um teste de regressão roda todas as frases dos testes do `RuleRouter` e os 100 do benchmark com todas as funcionalidades ligadas: nenhuma pode mudar de destino.
- **Nomes de ferramenta únicos**: o `ToolRegistry` recusa duplicata, e um teste junta as de base com as de todas as funcionalidades.
- **Desligar é seguro**: desligada, a ferramenta some dos modelos e a frase dita responde "está desligado nos Ajustes", em vez de cair em outra regra.
- **Degradação elegante**: falta de permissão vira `DeviceResult.NeedsPermission`, que a fala traduz em "Preciso da permissão X para Y. Libere em Ajustes".

### 3.4 Próximos passos de arquitetura (entram quando a primeira funcionalidade precisar)

- **Ferramentas por pedido**: com dezenas de ferramentas, o Gemma de 4B erra mais e o servidor recusa acima de 64. O `Capabilities` vai escolher as funcionalidades relevantes ao pedido (palavras-chave de cada uma) antes de montar o prompt.
- **Plano de várias etapas** (`Plan` no `core`): passos com pré-condição e verificação, cada um pela `RiskPolicy`, parada a qualquer momento e retomada. É a base de "comandos compostos", "tarefas autônomas" e "recuperação após falha".
- **Fontes de contexto** (memória, agenda, notificações, tela): uma interface comum que devolve trechos já embrulhados por `UntrustedContent.wrap` e marcados como dado pessoal, para a regra de saída do PR #21 valer para todos.
- **Proatividade**: rotinas no WorkManager com resultado em notificação, uma categoria por funcionalidade, cada uma desligável, com horário de silêncio.

## 4. Ordem de entrega

Seguindo as fases do próprio catálogo, um PR por item. O que outro thread já está fazendo fica com ele.

| Fase | PR | Conteúdo |
| --- | --- | --- |
| Base | **Este** | Funcionalidades em pacotes, portas por pedido, tela Funcionalidades e o pacote Sistema |
| F1 útil | Memória (pronta) | Memória pessoal cifrada: "lembra que…", "o que você sabe sobre…", "esquece…", tela para ver e apagar, o Gemma lê |
| F1 útil | Agenda e lembretes (prontos) | Agenda do aparelho (ver, criar com Confirmar, horários livres, conflitos) e lembretes com hora em notificação |
| F1 útil | Mensagens | SMS com Confirmar (pronto) e "compartilhar para o Blico" (resumir, reescrever, traduzir, criar lembrete) |
| F1 útil | Ferramentas por pedido | Seleção de ferramentas por relevância (3.4) |
| F2 celular | Notificações | Resumo, urgentes, golpe em link, abrir o app certo, tudo no aparelho |
| F2 celular | Tela e câmera | Contexto da tela pelo papel de assistente e foto para o Gemma |
| F2 celular | Mídia | Pausar, pular, voltar e volume na sessão ativa |
| F3 agente | Planos | `Plan` com validação, comandos compostos, parar e retomar |
| F4 proativo | Rotinas | Resumo da manhã, aviso de sair, conflitos, cada um desligável |

Com o thread "Revisão e plano do Blico": registro de auditoria exportável, SHA-256 dos downloads e o PR #23 (música no player certo e Waze) seguem com ele.
