import assert from "node:assert/strict";
import { test } from "node:test";
import worker from "../src/index.ts";

const env = { ANTHROPIC_API_KEY: "x", DEVICE_TOKEN: "segredo", MODEL: "claude-haiku-5-5", DAILY_LIMIT: "1" } as any;
const call = (path: string, init?: RequestInit, e = env) => worker.fetch(new Request(`https://blico.test${path}`, init), e);

test("health responde sem token", async () => {
  assert.equal((await call("/health")).status, 200);
});

test("sem token correto é 401", async () => {
  const r = await call("/v1/plan", { method: "POST", headers: { authorization: "Bearer errado" }, body: "{}" });
  assert.equal(r.status, 401);
});

test("corpo inválido é 400 antes de chamar a nuvem", async () => {
  const r = await call("/v1/plan", { method: "POST", headers: { authorization: "Bearer segredo" }, body: JSON.stringify({ text: "" }) });
  assert.equal(r.status, 400);
});

test("limite diário com KV", async () => {
  const store = new Map<string, string>([[`dia:${new Date().toISOString().slice(0, 10)}`, "1"]]);
  const USAGE = { get: async (k: string) => store.get(k) ?? null, put: async (k: string, v: string) => void store.set(k, v) };
  const r = await call("/v1/plan", { method: "POST", headers: { authorization: "Bearer segredo" }, body: "{}" }, { ...env, USAGE });
  assert.equal(r.status, 429);
});
