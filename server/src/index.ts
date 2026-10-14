import Anthropic from "@anthropic-ai/sdk";
import { BadRequest, parseRequest, plan } from "./plan.ts";

export interface Env {
  ANTHROPIC_API_KEY: string;
  DEVICE_TOKEN: string;
  MODEL: string;
  DAILY_LIMIT: string;
  USAGE?: KVNamespace;
}

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8" } });

/** Comparação em tempo constante, para o token não vazar por tempo de resposta. */
function sameToken(a: string, b: string): boolean {
  const ea = new TextEncoder().encode(a);
  const eb = new TextEncoder().encode(b);
  if (ea.length !== eb.length) return false;
  let diff = 0;
  for (let i = 0; i < ea.length; i++) diff |= ea[i] ^ eb[i];
  return diff === 0;
}

/** Conta pedidos por dia no KV, se ele estiver configurado. Retorna false quando passou do limite. */
async function withinDailyLimit(env: Env): Promise<boolean> {
  if (!env.USAGE) return true;
  const key = `dia:${new Date().toISOString().slice(0, 10)}`;
  const used = Number((await env.USAGE.get(key)) ?? "0");
  if (used >= Number(env.DAILY_LIMIT || "300")) return false;
  await env.USAGE.put(key, String(used + 1), { expirationTtl: 60 * 60 * 48 });
  return true;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    // Só diz se os segredos existem, nunca o valor. Ajuda a conferir a configuração pelo navegador.
    if (url.pathname === "/health") return json(200, { ok: true, token: Boolean(env.DEVICE_TOKEN?.trim()), chave: Boolean(env.ANTHROPIC_API_KEY?.trim()) });
    if (url.pathname !== "/v1/plan" || request.method !== "POST") return json(404, { error: "não encontrado" });

    const expected = env.DEVICE_TOKEN?.trim();
    if (!expected) return json(503, { error: "DEVICE_TOKEN não configurado no servidor" });
    const auth = (request.headers.get("authorization") ?? "").trim();
    if (!sameToken(auth, `Bearer ${expected}`)) return json(401, { error: "não autorizado" });
    if (!(await withinDailyLimit(env))) return json(429, { error: "limite diário atingido" });

    let req;
    try {
      req = parseRequest(await request.json());
    } catch (e) {
      return json(400, { error: e instanceof BadRequest ? e.message : "json inválido" });
    }

    const started = Date.now();
    try {
      const client = new Anthropic({ apiKey: env.ANTHROPIC_API_KEY?.trim(), maxRetries: 1, timeout: 20_000 });
      const result = await plan(client, env.MODEL || "claude-haiku-5-5", req);
      // Só metadados no log: nunca o texto do pedido nem a resposta.
      console.log(JSON.stringify({ ms: Date.now() - started, tools: req.tools.length, calls: result.calls.map((c) => c.tool), answered: result.answer !== null }));
      return json(200, result);
    } catch (e) {
      if (e instanceof Anthropic.RateLimitError) return json(429, { error: "nuvem ocupada, tente de novo" });
      if (e instanceof Anthropic.AuthenticationError) return json(502, { error: "chave da Anthropic inválida no servidor" });
      if (e instanceof Anthropic.APIError) return json(502, { error: `erro da nuvem (${e.status ?? "rede"})` });
      return json(500, { error: "erro interno" });
    }
  },
} satisfies ExportedHandler<Env>;
