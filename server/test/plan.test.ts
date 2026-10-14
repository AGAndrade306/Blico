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
