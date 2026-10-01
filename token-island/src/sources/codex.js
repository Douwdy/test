'use strict';

const os = require('node:os');
const path = require('node:path');
const fs = require('node:fs');
const { createJsonlTailer } = require('./jsonl-tailer');

// Codex CLI (OpenAI) écrit ses sessions dans <CODEX_HOME>/sessions/AAAA/MM/JJ/rollout-*.jsonl.
// Les évènements `token_count` portent le total cumulé de la session : on compte la
// différence avec le total précédent, ce qui ignore les évènements répétés. Ils portent
// aussi l'état des limites du forfait ChatGPT (fenêtre de 5 h et hebdomadaire).

function defaultRoots(env = process.env, home = os.homedir()) {
  const base = env.CODEX_HOME || path.join(home, '.codex');
  return [path.join(base, 'sessions')];
}

const FIELDS = ['input_tokens', 'cached_input_tokens', 'output_tokens', 'reasoning_output_tokens'];

function readLimit(l, lineTs) {
  if (!l || typeof l.used_percent !== 'number') return null;
  let resetsAt = null;
  if (typeof l.resets_at === 'number') resetsAt = l.resets_at < 1e12 ? l.resets_at * 1000 : l.resets_at;
  else if (typeof l.resets_in_seconds === 'number') resetsAt = lineTs + l.resets_in_seconds * 1000;
  return { usedPercent: l.used_percent, windowMinutes: l.window_minutes ?? null, resetsAt };
}

/** Renvoie { event?, limits? } pour une ligne ; `ctx` garde le modèle et le dernier total. */
function parseCodexLine(obj, ctx, file, { subscription }) {
  const p = obj.payload;
  if (!p) return {};
  if (obj.type === 'turn_context' && p.model) {
    ctx.model = p.model;
    if (p.cwd) ctx.cwd = p.cwd;
    return {};
  }
  if (obj.type === 'session_meta' && p.cwd) {
    ctx.cwd = p.cwd;
    return {};
  }
  if (p.type !== 'token_count') return {};

  const ts = Date.parse(obj.timestamp) || Date.now();
  const out = {};
  if (p.rate_limits) {
    out.limits = {
      primary: readLimit(p.rate_limits.primary, ts),
      secondary: readLimit(p.rate_limits.secondary, ts),
      updatedAt: ts,
    };
  }
  const total = p.info?.total_token_usage;
  if (!total) return out;

  const prev = ctx.total || {};
  const reset = FIELDS.some((f) => (total[f] || 0) < (prev[f] || 0));
  const delta = {};
  for (const f of FIELDS) delta[f] = (total[f] || 0) - (reset ? 0 : prev[f] || 0);
  ctx.total = Object.fromEntries(FIELDS.map((f) => [f, total[f] || 0]));
  if (FIELDS.every((f) => delta[f] <= 0)) return out;

  const cached = Math.max(0, delta.cached_input_tokens);
  out.event = {
    id: `codex:${path.basename(file)}:${total.total_tokens ?? FIELDS.map((f) => total[f] || 0).join('-')}`,
    ts,
    provider: 'openai',
    model: ctx.model || 'codex',
    source: 'codex',
    app: ctx.cwd ? `Codex · ${path.basename(ctx.cwd)}` : 'Codex',
    billing: subscription ? 'subscription' : 'api',
    input: Math.max(0, delta.input_tokens - cached),
    cacheRead: cached,
    cacheWrite: 0,
    output: Math.max(0, delta.output_tokens),
    reasoning: Math.max(0, delta.reasoning_output_tokens),
  };
  return out;
}

function createCodexSource({ getConfig, state }) {
  let limits = state.limits || null;
  const tailer = createJsonlTailer({
    roots: () => [...defaultRoots(), ...(getConfig().sources.codex.paths || [])].filter((p) => fs.existsSync(p)),
    accept: (f) => f.endsWith('.jsonl'),
    state,
    parseLine: (obj, ctx, file) => {
      const res = parseCodexLine(obj, ctx, file, { subscription: getConfig().sources.codex.subscription });
      if (res.limits && (!limits || res.limits.updatedAt >= limits.updatedAt)) {
        limits = res.limits;
        state.limits = limits;
      }
      return res.event ? [res.event] : null;
    },
  });
  return {
    name: 'codex',
    scan: (onEvent) => tailer.scan(onEvent),
    get limits() {
      return limits;
    },
  };
}

module.exports = { createCodexSource, parseCodexLine, defaultRoots };
