'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createPricing } = require('../src/core/pricing');
const { buildSnapshot, currentBlock } = require('../src/core/aggregate');
const { resolveConfig } = require('../src/core/config');
const F = require('../src/renderer/format');

test('Tarifs : les variantes précises passent avant les génériques', () => {
  const p = createPricing();
  assert.equal(p.priceFor('gpt-5-mini-2025-08-07').input, 0.25);
  assert.equal(p.priceFor('gpt-5').input, 1.25);
  assert.equal(p.priceFor('claude-opus-5-5').input, 4);
  assert.equal(p.priceFor('claude-opus-4-1-20250805').input, 15);
  assert.equal(p.priceFor('anthropic/claude-sonnet-4.5').input, 3);
  assert.equal(p.priceFor('models/gemini-2.5-flash-lite').input, 0.1);
  assert.equal(p.priceFor('modele-inconnu'), null);
});

test('Tarifs personnalisés prioritaires, motif invalide ignoré', () => {
  const p = createPricing([{ match: '([', input: 1, output: 1 }, { match: '^gpt-5$', input: 9, output: 9 }]);
  assert.equal(p.priceFor('gpt-5').input, 9);
  assert.equal(p.priceFor('gpt-5-mini').input, 0.25);
});

test('Coût : entrée, cache et sortie', () => {
  const p = createPricing([{ match: 'test', input: 2, output: 10, cacheRead: 0.2, cacheWrite: 2.5 }]);
  const c = p.cost('test', { input: 1e6, cacheRead: 1e6, cacheWrite: 1e6, output: 1e6 });
  assert.equal(c, 2 + 0.2 + 2.5 + 10);
  assert.equal(p.cost('inconnu', { input: 1, cacheRead: 0, cacheWrite: 0, output: 0 }), null);
});

const ev = (over) => ({ input: 0, cacheRead: 0, cacheWrite: 0, output: 0, reasoning: 0, billing: 'api', source: 'proxy', ...over });

test('Instantané : jour, fournisseurs, budget, abonnements hors budget', () => {
  const now = new Date(2026, 9, 15, 15, 30).getTime();
  const pricing = createPricing([{ match: 'm', input: 1, output: 1 }]);
  const config = resolveConfig({ budget: { monthlyUsd: 10 } });
  const events = [
    ev({ id: 'a', ts: now - 3600e3, provider: 'openai', model: 'm', input: 1e6 }),
    ev({ id: 'b', ts: now - 60e3, provider: 'anthropic', model: 'm', output: 2e6, source: 'claude-code', billing: 'subscription' }),
    ev({ id: 'c', ts: new Date(2026, 9, 2).getTime(), provider: 'openai', model: 'm', input: 3e6 }),
    ev({ id: 'd', ts: new Date(2026, 8, 30).getTime(), provider: 'openai', model: 'm', input: 9e6 }),
  ];
  const s = buildSnapshot({ events, now, config, pricing });
  assert.equal(s.today.tokens, 3e6);
  assert.equal(s.today.cost, 3);
  assert.equal(s.month.tokens, 6e6);
  assert.equal(s.budget.monthSpent, 4); // l'abonnement n'entre pas dans le budget
  assert.equal(s.budget.pct, 40);
  assert.deepEqual(s.providers.map((p) => p.provider), ['anthropic', 'openai']);
  assert.equal(s.hourly[23], 2e6);
  assert.equal(s.hourly[22], 1e6);
  assert.ok(s.claudeBlock);
  assert.equal(s.claudeBlock.tokens, 2e6);
});

test('API admin active : le proxy du même fournisseur n’est plus compté', () => {
  const now = Date.now();
  const pricing = createPricing();
  const config = resolveConfig({ sources: { openaiAdmin: { enabled: true } } });
  const events = [
    ev({ id: 'p', ts: now, provider: 'openai', model: 'gpt-5', input: 100 }),
    ev({ id: 'x', ts: now, provider: 'openai', model: 'gpt-5', input: 100, source: 'openai-admin' }),
    ev({ id: 'g', ts: now, provider: 'gemini', model: 'gemini-2.5-pro', input: 50 }),
  ];
  const s = buildSnapshot({ events, now, config, pricing });
  assert.equal(s.today.tokens, 150);
});

test('Blocs de 5 h', () => {
  const H = 3600e3;
  const t0 = Date.UTC(2026, 9, 1, 8, 20);
  const evs = [{ ts: t0 }, { ts: t0 + 2 * H }, { ts: t0 + 6 * H }].map((e, i) => ev({ id: String(i), ...e }));
  const b = currentBlock(evs, t0 + 6.5 * H);
  assert.equal(b.events.length, 1);
  assert.equal(b.start, Date.UTC(2026, 9, 1, 14));
  assert.equal(currentBlock(evs, t0 + 12 * H), null);
});

test('Format', () => {
  assert.equal(F.tokens(950), '950');
  assert.match(F.tokens(1234567), /^1,2\sM$/);
  assert.match(F.money(3.4, { currency: 'USD' }), /3,40\s\$/);
  assert.match(F.money(10, { currency: 'EUR', usdToEur: 0.5 }), /5,00\s€/);
  assert.match(F.money(10, { currency: 'EUR', usdToEur: 0 }), /\$/); // pas de taux : on reste en dollars
  assert.equal(F.model('claude-sonnet-4-5-20250929'), 'sonnet-4-5');
  assert.equal(F.duration(2 * 3600e3 + 5 * 60e3), '2 h 05');
});
