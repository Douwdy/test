'use strict';

const { totalTokens } = require('./usage');

const HOUR = 3600e3;
const BLOCK = 5 * HOUR;

// Quand l'API d'administration d'un fournisseur est active, elle voit déjà tout son trafic :
// les requêtes du même fournisseur passées par le proxy sont alors exclues des totaux.
const ADMIN_SOURCES = { openai: 'openaiAdmin', anthropic: 'anthropicAdmin' };

function startOfDay(now) {
  const d = new Date(now);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

function startOfMonth(now) {
  const d = new Date(now);
  d.setHours(0, 0, 0, 0);
  d.setDate(1);
  return d.getTime();
}

function bucket() {
  return { tokens: 0, cost: 0, unpriced: 0, requests: 0, input: 0, output: 0, cacheRead: 0, cacheWrite: 0 };
}

function addTo(b, ev, cost) {
  b.tokens += totalTokens(ev);
  b.input += ev.input;
  b.output += ev.output;
  b.cacheRead += ev.cacheRead;
  b.cacheWrite += ev.cacheWrite;
  b.requests += 1;
  if (cost === null) b.unpriced += totalTokens(ev);
  else b.cost += cost;
}

/** Blocs glissants de 5 h à la manière des limites Claude (début arrondi à l'heure). */
function currentBlock(events, now) {
  let start = null;
  let last = null;
  let block = [];
  for (const ev of events) {
    if (start === null || ev.ts >= start + BLOCK || ev.ts - last >= BLOCK) {
      start = Math.floor(ev.ts / HOUR) * HOUR;
      block = [];
    }
    block.push(ev);
    last = ev.ts;
  }
  if (start === null || now >= start + BLOCK) return null;
  return { start, end: start + BLOCK, events: block };
}

/**
 * Calcule tout ce qu'affiche l'encoche à partir du journal.
 * `extras` porte les infos qui ne sont pas des évènements (limites Codex…).
 */
function buildSnapshot({ events, now = Date.now(), config, pricing, extras = {} }) {
  const dayStart = startOfDay(now);
  const monthStart = startOfMonth(now);
  const excluded = new Set(
    Object.entries(ADMIN_SOURCES)
      .filter(([, key]) => config.sources?.[key]?.enabled)
      .map(([provider]) => provider),
  );
  const counted = (ev) => !(ev.source === 'proxy' && excluded.has(ev.provider));
  const inBudget = (ev) => config.budget.includeSubscription || ev.billing !== 'subscription';

  const today = bucket();
  const month = bucket();
  const monthBudget = bucket();
  const todayBudget = bucket();
  const providers = new Map();
  const models = new Map();
  const hourly = new Array(24).fill(0);
  const hourZero = Math.floor(now / HOUR) * HOUR - 23 * HOUR;
  let recent = 0;
  let last = null;
  const claudeCode = [];

  const sorted = events.filter((e) => e.ts >= Math.min(monthStart, hourZero, now - 2 * BLOCK) && counted(e));
  sorted.sort((a, b) => a.ts - b.ts);

  for (const ev of sorted) {
    const cost = pricing.cost(ev.model, ev);
    const tokens = totalTokens(ev);
    if (ev.ts >= monthStart) {
      addTo(month, ev, cost);
      if (inBudget(ev)) addTo(monthBudget, ev, cost);
    }
    if (ev.ts >= dayStart) {
      addTo(today, ev, cost);
      if (inBudget(ev)) addTo(todayBudget, ev, cost);
      if (!providers.has(ev.provider)) providers.set(ev.provider, bucket());
      addTo(providers.get(ev.provider), ev, cost);
      const key = `${ev.provider}\u0000${ev.model}`;
      if (!models.has(key)) models.set(key, { provider: ev.provider, model: ev.model, ...bucket() });
      addTo(models.get(key), ev, cost);
    }
    if (ev.ts >= hourZero) hourly[Math.min(23, Math.floor((ev.ts - hourZero) / HOUR))] += tokens;
    if (ev.ts >= now - 5 * 60e3) recent += tokens;
    if (ev.source === 'claude-code') claudeCode.push(ev);
    if (!last || ev.ts >= last.ts) last = { ...ev, tokens, cost };
  }

  let claudeBlock = null;
  const block = currentBlock(claudeCode, now);
  if (block) {
    const b = bucket();
    for (const ev of block.events) addTo(b, ev, pricing.cost(ev.model, ev));
    const limit = config.claudeBlockTokenLimit || 0;
    claudeBlock = {
      start: block.start,
      end: block.end,
      remainingMs: block.end - now,
      tokens: b.tokens,
      cost: b.cost,
      limit,
      pct: limit ? (b.tokens / limit) * 100 : null,
    };
  }

  const budgetUsd = config.budget.monthlyUsd || 0;
  const dailyTokens = config.budget.dailyTokens || 0;
  const pcts = [];
  if (budgetUsd) pcts.push((monthBudget.cost / budgetUsd) * 100);
  if (dailyTokens) pcts.push((todayBudget.tokens / dailyTokens) * 100);

  // Projection simple : dépense moyenne par jour écoulé × nombre de jours du mois.
  const d = new Date(now);
  const daysInMonth = new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate();
  const daysElapsed = Math.max(1, (now - monthStart) / 86400e3);
  const projection = (monthBudget.cost / daysElapsed) * daysInMonth;

  const byCost = (a, b) => b.cost - a.cost || b.tokens - a.tokens;
  return {
    now,
    today,
    month,
    budget: {
      monthlyUsd: budgetUsd,
      monthSpent: monthBudget.cost,
      dailyTokens,
      todayTokens: todayBudget.tokens,
      projection,
      pct: pcts.length ? Math.max(...pcts) : null,
    },
    providers: [...providers.entries()].map(([provider, b]) => ({ provider, ...b })).sort(byCost),
    models: [...models.values()].sort(byCost).slice(0, 6),
    hourly,
    tokensPerMin: recent / 5,
    claudeBlock,
    last,
    ...extras,
  };
}

module.exports = { buildSnapshot, currentBlock, startOfDay, startOfMonth };
