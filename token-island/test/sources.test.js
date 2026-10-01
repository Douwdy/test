'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { parseClaudeCodeLine } = require('../src/sources/claude-code');
const { parseCodexLine } = require('../src/sources/codex');
const { createJsonlTailer } = require('../src/sources/jsonl-tailer');
const { UsageStore } = require('../src/core/store');
const { fetchAnthropicUsage, fetchOpenAIUsage } = require('../src/sources/admin-apis');

const ccLine = (id, req, usage, extra = {}) => ({
  type: 'assistant',
  timestamp: '2026-10-01T10:00:00.000Z',
  requestId: req,
  cwd: 'C:\\Users\\moi\\projets\\rhynote',
  message: { id, model: 'claude-opus-4-8', usage },
  ...extra,
});

test('Claude Code : une ligne → un évènement, identifiant stable', () => {
  const ev = parseClaudeCodeLine(ccLine('msg_1', 'req_1', { input_tokens: 3, cache_read_input_tokens: 9000, output_tokens: 120 }), {
    subscription: true,
  });
  assert.equal(ev.id, 'cc:msg_1:req_1');
  assert.equal(ev.provider, 'anthropic');
  assert.equal(ev.billing, 'subscription');
  assert.equal(ev.cacheRead, 9000);
  assert.equal(ev.output, 120);
  assert.equal(parseClaudeCodeLine({ type: 'user', message: {} }, {}), null);
  assert.equal(parseClaudeCodeLine(ccLine('m', 'r', { input_tokens: 1 }, { message: { model: '<synthetic>', usage: {} } }), {}), null);
});

test('Codex : différences entre totaux cumulés, doublons ignorés, limites lues', () => {
  const ctx = {};
  const file = '/x/rollout-2026-10-01.jsonl';
  const opts = { subscription: true };
  parseCodexLine({ type: 'turn_context', payload: { model: 'gpt-5-codex', cwd: '/home/moi/app' } }, ctx, file, opts);
  const tc = (total, extra) => ({
    timestamp: '2026-10-01T10:00:00.000Z',
    type: 'event_msg',
    payload: { type: 'token_count', info: { total_token_usage: total }, ...extra },
  });
  const r1 = parseCodexLine(
    tc(
      { input_tokens: 1000, cached_input_tokens: 400, output_tokens: 50, reasoning_output_tokens: 20, total_tokens: 1050 },
      { rate_limits: { primary: { used_percent: 12.5, window_minutes: 300, resets_in_seconds: 3600 } } },
    ),
    ctx,
    file,
    opts,
  );
  assert.equal(r1.event.model, 'gpt-5-codex');
  assert.equal(r1.event.input, 600);
  assert.equal(r1.event.cacheRead, 400);
  assert.equal(r1.event.output, 50);
  assert.equal(r1.limits.primary.usedPercent, 12.5);
  assert.equal(r1.limits.primary.resetsAt, Date.parse('2026-10-01T11:00:00.000Z'));

  const same = parseCodexLine(
    tc({ input_tokens: 1000, cached_input_tokens: 400, output_tokens: 50, reasoning_output_tokens: 20, total_tokens: 1050 }),
    ctx,
    file,
    opts,
  );
  assert.equal(same.event, undefined);

  const r2 = parseCodexLine(
    tc({ input_tokens: 1500, cached_input_tokens: 900, output_tokens: 80, reasoning_output_tokens: 20, total_tokens: 1580 }),
    ctx,
    file,
    opts,
  );
  assert.equal(r2.event.input, 0);
  assert.equal(r2.event.cacheRead, 500);
  assert.equal(r2.event.output, 30);
});

test('Lecture incrémentale : lignes partielles et UTF-8 coupé entre deux lectures', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ti-'));
  const file = path.join(dir, 'a.jsonl');
  const state = {};
  const seen = [];
  const tailer = createJsonlTailer({
    roots: () => [dir],
    accept: (f) => f.endsWith('.jsonl'),
    prefilter: '"usage"',
    state,
    parseLine: (obj) => [obj],
  });
  const l1 = JSON.stringify({ usage: 1, txt: 'é'.repeat(10) });
  const l2 = JSON.stringify({ usage: 2 });
  fs.writeFileSync(file, `${l1}\n{"skip":true}\n${l2.slice(0, 5)}`);
  await tailer.scan((o) => seen.push(o.usage));
  assert.deepEqual(seen, [1]);
  fs.appendFileSync(file, `${l2.slice(5)}\n`);
  await tailer.scan((o) => seen.push(o.usage));
  assert.deepEqual(seen, [1, 2]);
  await tailer.scan((o) => seen.push(o.usage));
  assert.deepEqual(seen, [1, 2]);
  fs.rmSync(dir, { recursive: true });
});

test('Journal : doublons, mise à jour, rechargement, CSV', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ti-'));
  const file = path.join(dir, 'usage.jsonl');
  const s = new UsageStore(file).load();
  const base = { ts: Date.now(), provider: 'openai', model: 'gpt-5', input: 10, output: 5 };
  assert.equal(s.add({ id: 'a', ...base }), true);
  assert.equal(s.add({ id: 'a', ...base }), false);
  assert.equal(s.upsert({ id: 'day', ...base }), true);
  assert.equal(s.upsert({ id: 'day', ...base }), false);
  assert.equal(s.upsert({ id: 'day', ...base, input: 99 }), true);
  const again = new UsageStore(file).load();
  assert.equal(again.events.length, 2);
  assert.equal(again.events.find((e) => e.id === 'day').input, 99);
  const csv = again.toCsv({ cost: () => 0.5 });
  assert.match(csv.split('\n')[0], /^date,fournisseur/);
  assert.equal(csv.trim().split('\n').length, 3);
  fs.rmSync(dir, { recursive: true });
});

function fakeFetch(pages) {
  const calls = [];
  const impl = async (url) => {
    calls.push(String(url));
    const body = pages.shift();
    return { ok: true, status: 200, text: async () => JSON.stringify(body) };
  };
  return { impl, calls };
}

test('API admin Anthropic : agrégats par jour et par modèle, pagination', async () => {
  const { impl, calls } = fakeFetch([
    {
      data: [
        {
          starting_at: '2026-10-01T00:00:00Z',
          results: [
            {
              model: 'claude-sonnet-5',
              uncached_input_tokens: 100,
              cache_read_input_tokens: 50,
              cache_creation: { ephemeral_5m_input_tokens: 10, ephemeral_1h_input_tokens: 5 },
              output_tokens: 20,
            },
          ],
        },
      ],
      has_more: true,
      next_page: 'p2',
    },
    { data: [], has_more: false },
  ]);
  const evs = await fetchAnthropicUsage({ key: 'k', now: Date.UTC(2026, 9, 1, 12), fetchImpl: impl });
  assert.equal(evs.length, 1);
  assert.equal(evs[0].id, 'anthropic-admin:2026-10-01:claude-sonnet-5');
  assert.equal(evs[0].cacheWrite, 15);
  assert.match(calls[0], /group_by%5B%5D=model/);
  assert.match(calls[1], /page=p2/);
});

test('API admin OpenAI : le cache est retiré de l’entrée', async () => {
  const { impl } = fakeFetch([
    {
      data: [{ start_time: Date.UTC(2026, 9, 1) / 1000, results: [{ model: 'gpt-5', input_tokens: 100, input_cached_tokens: 30, output_tokens: 7 }] }],
      has_more: false,
    },
  ]);
  const [e] = await fetchOpenAIUsage({ key: 'k', now: Date.UTC(2026, 9, 1, 12), fetchImpl: impl });
  assert.equal(e.input, 70);
  assert.equal(e.cacheRead, 30);
  assert.equal(e.id, 'openai-admin:2026-10-01:gpt-5');
});
