'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { normalizeUsage, UsageSniffer } = require('../src/core/usage');

const sse = (events) => events.map((e) => `data: ${typeof e === 'string' ? e : JSON.stringify(e)}\n\n`).join('');

function sniff(provider, contentType, body, chunkSize = 7) {
  const s = new UsageSniffer({ provider, contentType });
  // Découpe arbitraire pour vérifier la reprise entre deux morceaux.
  const buf = Buffer.from(body);
  for (let i = 0; i < buf.length; i += chunkSize) s.feed(buf.subarray(i, i + chunkSize));
  return s.end();
}

test('OpenAI Chat Completions : le cache est retiré de l’entrée', () => {
  const u = normalizeUsage({
    prompt_tokens: 1000,
    completion_tokens: 200,
    prompt_tokens_details: { cached_tokens: 600 },
    completion_tokens_details: { reasoning_tokens: 50 },
  });
  assert.deepEqual(u, { input: 400, cacheRead: 600, cacheWrite: 0, output: 200, reasoning: 50 });
});

test('OpenAI Responses vs Anthropic : même champ input_tokens, sens différent', () => {
  const openai = normalizeUsage(
    { input_tokens: 500, output_tokens: 10, input_tokens_details: { cached_tokens: 100 }, total_tokens: 510 },
    'openai',
  );
  assert.equal(openai.input, 400);
  assert.equal(openai.cacheRead, 100);
  const anthropic = normalizeUsage(
    { input_tokens: 500, output_tokens: 10, cache_read_input_tokens: 100, cache_creation_input_tokens: 20 },
    'anthropic',
  );
  assert.deepEqual(anthropic, { input: 500, cacheRead: 100, cacheWrite: 20, output: 10, reasoning: 0 });
});

test('Gemini : réflexion comptée en sortie, cache retiré de l’entrée', () => {
  const u = normalizeUsage({ promptTokenCount: 300, cachedContentTokenCount: 100, candidatesTokenCount: 40, thoughtsTokenCount: 60 });
  assert.deepEqual(u, { input: 200, cacheRead: 100, cacheWrite: 0, output: 100, reasoning: 60 });
});

test('JSON classique (OpenAI)', () => {
  const r = sniff('openai', 'application/json', JSON.stringify({ model: 'gpt-4o-2024-08-06', usage: { prompt_tokens: 12, completion_tokens: 3 } }));
  assert.equal(r.model, 'gpt-4o-2024-08-06');
  assert.equal(r.usage.input, 12);
  assert.equal(r.usage.output, 3);
});

test('Flux OpenAI avec include_usage : dernier morceau', () => {
  const body = sse([
    { model: 'gpt-5-mini', choices: [{ delta: { content: 'Bon' } }], usage: null },
    { model: 'gpt-5-mini', choices: [{ delta: { content: 'jour' } }], usage: null },
    { model: 'gpt-5-mini', choices: [], usage: { prompt_tokens: 20, completion_tokens: 5 } },
    '[DONE]',
  ]);
  const r = sniff('openai', 'text/event-stream; charset=utf-8', body);
  assert.equal(r.model, 'gpt-5-mini');
  assert.deepEqual(r.usage, { input: 20, cacheRead: 0, cacheWrite: 0, output: 5, reasoning: 0 });
});

test('Flux Anthropic : entrée dans message_start, sortie cumulée dans message_delta', () => {
  const body = [
    'event: message_start',
    `data: ${JSON.stringify({ type: 'message_start', message: { model: 'claude-sonnet-4-6', usage: { input_tokens: 25, cache_read_input_tokens: 1000, cache_creation_input_tokens: 0, output_tokens: 1 } } })}`,
    '',
    'event: content_block_delta',
    `data: ${JSON.stringify({ type: 'content_block_delta', delta: { type: 'text_delta', text: 'Salut 👋' } })}`,
    '',
    'event: message_delta',
    `data: ${JSON.stringify({ type: 'message_delta', usage: { output_tokens: 42 } })}`,
    '',
  ].join('\r\n');
  const r = sniff('anthropic', 'text/event-stream', body, 5);
  assert.equal(r.model, 'claude-sonnet-4-6');
  assert.deepEqual(r.usage, { input: 25, cacheRead: 1000, cacheWrite: 0, output: 42, reasoning: 0 });
});

test('Flux de l’API Responses (OpenAI)', () => {
  const body = sse([
    { type: 'response.created', response: { model: 'gpt-5', usage: null } },
    { type: 'response.output_text.delta', delta: 'x' },
    {
      type: 'response.completed',
      response: {
        model: 'gpt-5',
        usage: { input_tokens: 70, input_tokens_details: { cached_tokens: 0 }, output_tokens: 9, output_tokens_details: { reasoning_tokens: 4 }, total_tokens: 79 },
      },
    },
  ]);
  const r = sniff('openai', 'text/event-stream', body);
  assert.deepEqual(r.usage, { input: 70, cacheRead: 0, cacheWrite: 0, output: 9, reasoning: 4 });
});

test('Gemini en flux SSE et en tableau JSON', () => {
  const chunks = [
    { candidates: [{}], usageMetadata: { promptTokenCount: 8, candidatesTokenCount: 2 }, modelVersion: 'gemini-2.5-flash' },
    { candidates: [{}], usageMetadata: { promptTokenCount: 8, candidatesTokenCount: 30, thoughtsTokenCount: 5 }, modelVersion: 'gemini-2.5-flash' },
  ];
  const a = sniff('gemini', 'text/event-stream', sse(chunks));
  const b = sniff('gemini', 'application/json', JSON.stringify(chunks));
  for (const r of [a, b]) {
    assert.equal(r.model, 'gemini-2.5-flash');
    assert.deepEqual(r.usage, { input: 8, cacheRead: 0, cacheWrite: 0, output: 35, reasoning: 5 });
  }
});

test('Réponse sans usage ni JSON : rien n’est compté', () => {
  assert.equal(sniff('openai', 'application/json', '{"data":[]}').usage, null);
  assert.equal(sniff('openai', 'application/json', '<html>502</html>').usage, null);
});
