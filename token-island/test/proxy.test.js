'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const zlib = require('node:zlib');
const { createProxy, inspectRequest } = require('../src/core/proxy');
const { resolveConfig } = require('../src/core/config');

// Faux fournisseur : renvoie une réponse prévue selon le chemin, et garde la dernière requête reçue.
function fakeUpstream() {
  const state = { last: null };
  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      state.last = { method: req.method, url: req.url, headers: req.headers, body: Buffer.concat(chunks).toString() };
      if (req.url.startsWith('/v1/chat/completions')) {
        const body = JSON.parse(state.last.body);
        if (body.stream) {
          res.writeHead(200, { 'content-type': 'text/event-stream' });
          res.write(`data: ${JSON.stringify({ model: 'gpt-5-mini', choices: [{ delta: { content: 'a' } }] })}\n\n`);
          setTimeout(() => {
            if (body.stream_options?.include_usage) {
              res.write(`data: ${JSON.stringify({ model: 'gpt-5-mini', choices: [], usage: { prompt_tokens: 11, completion_tokens: 4 } })}\n\n`);
            }
            res.end('data: [DONE]\n\n');
          }, 20);
          return;
        }
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ model: 'gpt-5-mini', usage: { prompt_tokens: 7, completion_tokens: 2 } }));
        return;
      }
      if (req.url.startsWith('/v1/messages')) {
        // Réponse compressée malgré accept-encoding: identity, pour tester la décompression.
        const raw = JSON.stringify({ model: 'claude-haiku-4-5', usage: { input_tokens: 30, output_tokens: 6 } });
        res.writeHead(200, { 'content-type': 'application/json', 'content-encoding': 'gzip' });
        res.end(zlib.gzipSync(raw));
        return;
      }
      res.writeHead(404, { 'content-type': 'application/json' });
      res.end('{"error":"nope"}');
    });
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve({ server, state, port: server.address().port })));
}

async function setup() {
  const up = await fakeUpstream();
  const base = `http://127.0.0.1:${up.port}`;
  const config = resolveConfig({ upstreams: { openai: base, anthropic: base } });
  const usages = [];
  const activity = [];
  const proxy = createProxy({
    getConfig: () => config,
    onUsage: (e) => usages.push(e),
    onActivity: (a) => activity.push(a.inFlight),
  });
  const port = await proxy.start(0);
  const close = async () => {
    await proxy.stop();
    up.server.close();
  };
  return { up, proxy, port, usages, activity, close };
}

const waitFor = async (fn) => {
  for (let i = 0; i < 50 && !fn(); i++) await new Promise((r) => setTimeout(r, 10));
};

test('Proxy : JSON transmis tel quel, usage relevé, en-têtes conservés', async () => {
  const t = await setup();
  try {
    const res = await fetch(`http://127.0.0.1:${t.port}/openai/v1/chat/completions?x=1`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: 'Bearer sk-test', 'x-token-island-app': 'MonScript' },
      body: JSON.stringify({ model: 'gpt-5-mini', messages: [] }),
    });
    const body = await res.json();
    assert.equal(body.usage.prompt_tokens, 7);
    assert.equal(t.up.state.last.url, '/v1/chat/completions?x=1');
    assert.equal(t.up.state.last.headers.authorization, 'Bearer sk-test');
    assert.equal(t.up.state.last.headers['x-token-island-app'], undefined);
    await waitFor(() => t.usages.length);
    assert.equal(t.usages[0].provider, 'openai');
    assert.equal(t.usages[0].app, 'MonScript');
    assert.equal(t.usages[0].input, 7);
    assert.deepEqual(t.activity, [1, 0]);
  } finally {
    await t.close();
  }
});

test('Proxy : include_usage ajouté aux flux OpenAI', async () => {
  const t = await setup();
  try {
    const res = await fetch(`http://127.0.0.1:${t.port}/openai/v1/chat/completions`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ model: 'gpt-5-mini', stream: true, messages: [] }),
    });
    const text = await res.text();
    assert.match(text, /\[DONE\]/);
    assert.equal(JSON.parse(t.up.state.last.body).stream_options.include_usage, true);
    await waitFor(() => t.usages.length);
    assert.equal(t.usages[0].output, 4);
  } finally {
    await t.close();
  }
});

test('Proxy : réponse gzip transmise intacte et lue', async () => {
  const t = await setup();
  try {
    const res = await fetch(`http://127.0.0.1:${t.port}/anthropic/v1/messages`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'user-agent': 'mon-script/1.0' },
      body: JSON.stringify({ model: 'claude-haiku-4-5', max_tokens: 10, messages: [] }),
    });
    const body = await res.json(); // fetch décompresse
    assert.equal(body.model, 'claude-haiku-4-5');
    await waitFor(() => t.usages.length);
    assert.equal(t.usages[0].provider, 'anthropic');
    assert.equal(t.usages[0].input, 30);
    assert.equal(t.usages[0].client, null);
  } finally {
    await t.close();
  }
});

test('Proxy : Claude Code reconnu à son User-Agent', async () => {
  const t = await setup();
  try {
    await (
      await fetch(`http://127.0.0.1:${t.port}/anthropic/v1/messages`, {
        method: 'POST',
        headers: { 'content-type': 'application/json', 'user-agent': 'claude-cli/2.1.0 (external, cli)' },
        body: '{}',
      })
    ).text();
    await waitFor(() => t.usages.length);
    assert.equal(t.usages[0].client, 'claude-code');
    assert.equal(t.usages[0].app, 'Claude Code');
  } finally {
    await t.close();
  }
});

test('Proxy : préfixe inconnu, hôte non local, /ingest', async () => {
  const t = await setup();
  try {
    const r404 = await fetch(`http://127.0.0.1:${t.port}/inconnu/v1/x`);
    assert.equal(r404.status, 404);

    const r403 = await new Promise((resolve) => {
      http.get({ host: '127.0.0.1', port: t.port, path: '/health', headers: { host: 'evil.example' } }, (res) => {
        res.resume();
        resolve(res.statusCode);
      });
    });
    assert.equal(r403, 403);

    const bad = await fetch(`http://127.0.0.1:${t.port}/ingest`, { method: 'POST', body: '{}' });
    assert.equal(bad.status, 400);
    const ok = await fetch(`http://127.0.0.1:${t.port}/ingest`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify([{ id: 'x1', provider: 'gemini', model: 'gemini-2.5-pro', input: 5, output: 6, app: 'Extension' }]),
    });
    assert.equal(ok.status, 204);
    assert.equal(t.usages[0].id, 'ingest:x1');
    assert.equal(t.usages[0].source, 'ingest');
  } finally {
    await t.close();
  }
});

test('inspectRequest : modèle Gemini lu dans le chemin, pas d’injection hors chat/completions', () => {
  const r = inspectRequest({
    provider: 'gemini',
    path: '/v1beta/models/gemini-2.5-pro:streamGenerateContent?alt=sse',
    body: Buffer.from('{"contents":[]}'),
    contentType: 'application/json',
    injectStreamUsage: true,
  });
  assert.equal(r.model, 'gemini-2.5-pro');
  const body = Buffer.from('{"model":"gpt-5","stream":true,"stream_options":{"include_usage":false}}');
  const kept = inspectRequest({ provider: 'openai', path: '/v1/chat/completions', body, contentType: 'application/json', injectStreamUsage: true });
  assert.equal(kept.body, body);
});
