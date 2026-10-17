import assert from "node:assert/strict";
import { test } from "node:test";
import type Anthropic from "@anthropic-ai/sdk";
import { BadRequest, parseRequest, plan, toAnthropicTools, type PlanRequest } from "../src/plan.ts";

const tools: PlanRequest["tools"] = [
  {
    name: "whatsapp_preparar",
    description: "Abre a conversa do WhatsApp com a mensagem pronta",
    parameters: { properties: { contato: { description: "nome" }, texto: { description: "mensagem" } }, required: ["contato"] },
  },
];

/** Cliente falso: devolve a resposta dada e guarda o pedido feito. */
function fakeClient(content: unknown[], stop_reason = "end_turn") {
  const sent: any[] = [];
  const client = { messages: { create: async (p: any) => (sent.push(p), { content, stop_reason }) } } as unknown as Anthropic;
  return { client, sent };
}

test("chamada de ferramenta vira call com argumentos em texto", async () => {
  const { client, sent } = fakeClient([
    { type: "tool_use", id: "t1", name: "whatsapp_preparar", input: { contato: "contato_1", texto: "chego às 7" } },
  ], "tool_use");
  const r = await plan(client, "claude-haiku-5-5", { text: "manda pro contato_1 que chego às 7", tools });
  assert.deepEqual(r.calls, [{ tool: "whatsapp_preparar", args: { contato: "contato_1", texto: "chego às 7" } }]);
  assert.equal(r.answer, null);
  assert.equal(sent[0].model, "claude-haiku-5-5");
  assert.equal(sent[0].tools[0].input_schema.required[0], "contato");
});

test("ferramenta que o app não mandou é ignorada", async () => {
  const { client } = fakeClient([{ type: "tool_use", id: "t1", name: "apagar_tudo", input: {} }], "tool_use");
  const r = await plan(client, "m", { text: "x", tools });
  assert.deepEqual(r.calls, []);
  assert.equal(r.confidence, 0);
});

test("pergunta vira resposta falada", async () => {
  const { client } = fakeClient([{ type: "text", text: "Quinze por cento de oitenta é doze." }]);
  const r = await plan(client, "m", { text: "quanto é 15% de 80", tools });
  assert.equal(r.answer, "Quinze por cento de oitenta é doze.");
  assert.equal(r.confidence, 0.9);
});

test("recusa vira frase curta", async () => {
  const { client } = fakeClient([], "refusal");
  const r = await plan(client, "m", { text: "x", tools });
  assert.equal(r.calls.length, 0);
  assert.ok(r.answer);
});

test("contexto não confiável vai antes do pedido", async () => {
  const { client, sent } = fakeClient([{ type: "text", text: "ok" }]);
  await plan(client, "m", { text: "resume", tools, context: ["<<<DADO_NAO_CONFIAVEL origem=notificacao\noi\nDADO_NAO_CONFIAVEL>>>"] });
  assert.match(sent[0].messages[0].content, /^<<<DADO_NAO_CONFIAVEL[\s\S]*Pedido: resume$/);
});

test("validação do corpo", () => {
  assert.throws(() => parseRequest(null), BadRequest);
  assert.throws(() => parseRequest({ text: "", tools }), BadRequest);
  assert.throws(() => parseRequest({ text: "x".repeat(2001), tools }), BadRequest);
  assert.throws(() => parseRequest({ text: "oi", tools: [{ name: "Nome Inválido", description: "", parameters: { properties: {} } }] }), BadRequest);
  assert.equal(parseRequest({ text: "oi", tools }).text, "oi");
});

test("schema convertido sempre usa string", () => {
  const [t] = toAnthropicTools(tools);
  assert.deepEqual(t.input_schema.properties, { contato: { type: "string", description: "nome" }, texto: { type: "string", description: "mensagem" } });
});

test("agente: instruções vão no system e cada passo vira chamada e resultado", async () => {
  const { client, sent } = fakeClient([{ type: "text", text: "Avisei contato_1." }]);
  const r = await plan(client, "m", {
    text: "avisa o contato_1",
    tools,
    instructions: "Seja breve.",
    history: [{ tool: "whatsapp_enviar", args: { contato: "contato_1", texto: "oi" }, result: "<<<DADO_NAO_CONFIAVEL origem=ferramenta:whatsapp_enviar\nEnviei.\nDADO_NAO_CONFIAVEL>>>" }],
  });
  assert.equal(r.answer, "Avisei contato_1.");
  assert.match(sent[0].system, /Instruções do agente[\s\S]*Seja breve\.$/);
  const [user, call, result] = sent[0].messages;
  assert.equal(user.content, "avisa o contato_1");
  assert.deepEqual(call, { role: "assistant", content: [{ type: "tool_use", id: "passo_1", name: "whatsapp_enviar", input: { contato: "contato_1", texto: "oi" } }] });
  assert.equal(result.content[0].tool_use_id, "passo_1");
  assert.match(result.content[0].content, /^<<<DADO_NAO_CONFIAVEL/);
});

test("sem agente, o pedido segue como antes", async () => {
  const { client, sent } = fakeClient([{ type: "text", text: "ok" }]);
  await plan(client, "m", { text: "oi", tools });
  assert.equal(sent[0].messages.length, 1);
  assert.doesNotMatch(sent[0].system, /Instruções do agente/);
});

test("validação do histórico e das instruções", () => {
  const step = { tool: "timer", args: { segundos: "60" }, result: "ok" };
  assert.equal(parseRequest({ text: "oi", tools, instructions: null, history: [step] }).history?.length, 1);
  assert.throws(() => parseRequest({ text: "oi", tools, history: Array(11).fill(step) }), BadRequest);
  assert.throws(() => parseRequest({ text: "oi", tools, history: [{ ...step, args: { segundos: 60 } }] }), BadRequest);
  assert.throws(() => parseRequest({ text: "oi", tools, history: [{ ...step, tool: "Errado!" }] }), BadRequest);
  assert.throws(() => parseRequest({ text: "oi", tools, instructions: "x".repeat(4001) }), BadRequest);
});

test("ferramenta do histórico fora do catálogo entra só como descrição e não pode ser chamada", async () => {
  const { client, sent } = fakeClient([{ type: "tool_use", id: "t1", name: "whatsapp_enviar", input: {} }], "tool_use");
  const r = await plan(client, "m", { text: "x", tools, history: [{ tool: "whatsapp_enviar", args: {}, result: "ok" }] });
  assert.deepEqual(sent[0].tools.map((t: any) => t.name), ["whatsapp_preparar", "whatsapp_enviar"]);
  assert.deepEqual(r.calls, []);
});
