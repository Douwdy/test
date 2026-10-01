'use strict';

const http = require('node:http');
const https = require('node:https');
const zlib = require('node:zlib');
const crypto = require('node:crypto');
const { UsageSniffer } = require('./usage');

// Proxy local : il transmet les requêtes telles quelles à l'API réelle et lit, au passage,
// le nombre de tokens dans la réponse. Les clés d'API ne sont ni lues ni stockées.
//
//   OPENAI_BASE_URL=http://127.0.0.1:4141/openai/v1
//   ANTHROPIC_BASE_URL=http://127.0.0.1:4141/anthropic
//   …
//
// Routes internes : GET /health, POST /ingest (ajout manuel ou par un script / une extension).

const HOP_BY_HOP = new Set([
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'proxy-connection',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
  'host',
  'content-length',
  'accept-encoding',
  'x-token-island-app',
]);

// Fournisseurs compatibles OpenAI qui acceptent stream_options.include_usage.
const STREAM_USAGE_PROVIDERS = new Set(['openai', 'xai', 'deepseek']);
const MAX_BODY = 64 * 1024 * 1024;

function isLocalHost(hostHeader) {
  const host = String(hostHeader || '')
    .replace(/:\d+$/, '')
    .replace(/^\[|\]$/g, '');
  return host === '127.0.0.1' || host === 'localhost' || host === '::1';
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) {
        reject(Object.assign(new Error('Corps de requête trop gros'), { status: 413 }));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function sendJson(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'content-length': Buffer.byteLength(body) });
  res.end(body);
}

/** Prépare le corps envoyé à l'API : modèle demandé, et ajout éventuel de include_usage. */
function inspectRequest({ provider, path, body, contentType, injectStreamUsage }) {
  let model = null;
  let json = null;
  if (/json/i.test(contentType || '') && body.length) {
    try {
      json = JSON.parse(body.toString('utf8'));
    } catch {
      json = null;
    }
  }
  if (json && typeof json.model === 'string') model = json.model;
  const geminiModel = /\/models\/([^/:?]+)/.exec(path);
  if (!model && geminiModel) model = decodeURIComponent(geminiModel[1]);

  const openAiCompatible = STREAM_USAGE_PROVIDERS.has(provider) || (provider === 'gemini' && /\/openai\//.test(path));
  if (
    injectStreamUsage &&
    json &&
    json.stream === true &&
    openAiCompatible &&
    /\/chat\/completions\b/.test(path) &&
    !(json.stream_options && 'include_usage' in json.stream_options)
  ) {
    json.stream_options = { ...(json.stream_options || {}), include_usage: true };
    return { model, body: Buffer.from(JSON.stringify(json)) };
  }
  return { model, body };
}

function createProxy({ getConfig, onUsage, onActivity = () => {}, onIngest = onUsage }) {
  let server = null;
  let inFlight = 0;

  const activity = (provider) => onActivity({ inFlight, provider });

  async function handle(req, res) {
    if (!isLocalHost(req.headers.host)) return sendJson(res, 403, { error: 'Accès réservé à cette machine' });

    const url = new URL(req.url, 'http://127.0.0.1');
    const config = getConfig();

    if (url.pathname === '/health' || url.pathname === '/') {
      return sendJson(res, 200, {
        ok: true,
        app: 'Token Island',
        routes: Object.keys(config.upstreams).map((p) => `/${p}/… → ${config.upstreams[p]}/…`),
      });
    }

    if (url.pathname === '/ingest') {
      // Content-Type JSON obligatoire : une page web ne peut pas l'envoyer sans requête
      // préalable CORS, à laquelle on ne répond pas.
      if (req.method !== 'POST' || !/application\/json/i.test(req.headers['content-type'] || '')) {
        return sendJson(res, 400, { error: 'POST application/json attendu' });
      }
      try {
        const data = JSON.parse((await readBody(req)).toString('utf8'));
        const list = Array.isArray(data) ? data : [data];
        for (const item of list) {
          onIngest({
            id: item.id ? `ingest:${item.id}` : `ingest:${crypto.randomUUID()}`,
            ts: item.ts ? new Date(item.ts).getTime() : Date.now(),
            provider: item.provider,
            model: item.model,
            source: 'ingest',
            app: item.app,
            billing: item.billing,
            input: item.input,
            output: item.output,
            cacheRead: item.cacheRead,
            cacheWrite: item.cacheWrite,
            reasoning: item.reasoning,
          });
        }
        res.writeHead(204).end();
      } catch (err) {
        sendJson(res, err.status || 400, { error: String(err.message || err) });
      }
      return;
    }

    const [, provider, ...rest] = url.pathname.split('/');
    const upstreamBase = config.upstreams[provider];
    if (!upstreamBase) {
      return sendJson(res, 404, {
        error: `Fournisseur inconnu « ${provider} ». Préfixes disponibles : ${Object.keys(config.upstreams).join(', ')}`,
      });
    }

    let body;
    try {
      body = await readBody(req);
    } catch (err) {
      return sendJson(res, err.status || 400, { error: String(err.message || err) });
    }

    const base = new URL(upstreamBase);
    const upstreamPath = `${base.pathname.replace(/\/$/, '')}/${rest.join('/')}${url.search}`;
    const inspected = inspectRequest({
      provider,
      path: upstreamPath,
      body,
      contentType: req.headers['content-type'],
      injectStreamUsage: config.proxy.injectStreamUsage,
    });

    const headers = {};
    for (const [k, v] of Object.entries(req.headers)) if (!HOP_BY_HOP.has(k)) headers[k] = v;
    headers.host = base.host;
    headers['accept-encoding'] = 'identity';
    if (inspected.body.length || !['GET', 'HEAD'].includes(req.method)) {
      headers['content-length'] = inspected.body.length;
    }
    // Claude Code et Codex sont reconnus à leur User-Agent : quand leurs journaux sont déjà lus,
    // l'appelant peut ignorer ces requêtes pour ne pas les compter deux fois.
    const ua = String(req.headers['user-agent'] || '');
    const client = /claude-cli|claude-code/i.test(ua) ? 'claude-code' : /codex/i.test(ua) ? 'codex' : null;
    const app = req.headers['x-token-island-app'] || { 'claude-code': 'Claude Code', codex: 'Codex' }[client];

    const lib = base.protocol === 'http:' ? http : https;
    inFlight++;
    activity(provider);
    let finished = false;
    const done = () => {
      if (finished) return;
      finished = true;
      inFlight--;
      activity(provider);
    };

    const upstreamReq = lib.request(
      {
        protocol: base.protocol,
        hostname: base.hostname,
        port: base.port || undefined,
        method: req.method,
        path: upstreamPath,
        headers,
      },
      (upstreamRes) => {
        const outHeaders = {};
        for (const [k, v] of Object.entries(upstreamRes.headers)) {
          if (!HOP_BY_HOP.has(k) || k === 'content-length') outHeaders[k] = v;
        }
        res.writeHead(upstreamRes.statusCode, outHeaders);

        const contentType = upstreamRes.headers['content-type'] || '';
        const sniffable = /json|event-stream/i.test(contentType);
        const sniffer = new UsageSniffer({ provider, contentType });
        let tap = null;
        if (sniffable) {
          const enc = String(upstreamRes.headers['content-encoding'] || '').toLowerCase();
          if (enc === 'gzip' || enc === 'x-gzip') tap = zlib.createGunzip();
          else if (enc === 'br') tap = zlib.createBrotliDecompress();
          else if (enc === 'deflate') tap = zlib.createInflate();
          if (tap) {
            tap.on('data', (c) => sniffer.feed(c));
            tap.on('error', () => {});
          }
        }

        upstreamRes.on('data', (chunk) => {
          res.write(chunk);
          if (!sniffable) return;
          if (tap) tap.write(chunk);
          else sniffer.feed(chunk);
        });

        const finish = () => {
          const result = sniffer.end();
          if (result.usage) {
            onUsage({
              id: `proxy:${crypto.randomUUID()}`,
              ts: Date.now(),
              provider,
              model: result.model || inspected.model || 'inconnu',
              source: 'proxy',
              app,
              client,
              billing: 'api',
              ...result.usage,
            });
          }
          done();
        };

        upstreamRes.on('end', () => {
          res.end();
          if (tap) {
            tap.once('end', finish);
            tap.end();
          } else {
            finish();
          }
        });
        upstreamRes.on('error', () => {
          res.destroy();
          done();
        });
      },
    );

    upstreamReq.on('error', (err) => {
      done();
      if (!res.headersSent) sendJson(res, 502, { error: `API injoignable : ${err.message}` });
      else res.destroy();
    });
    res.on('close', () => {
      if (!res.writableFinished) upstreamReq.destroy();
    });
    upstreamReq.end(inspected.body);
  }

  return {
    start(port) {
      return new Promise((resolve, reject) => {
        server = http.createServer((req, res) => {
          handle(req, res).catch((err) => {
            if (!res.headersSent) sendJson(res, 500, { error: String(err.message || err) });
          });
        });
        server.once('error', reject);
        server.listen(port, '127.0.0.1', () => {
          server.off('error', reject);
          resolve(server.address().port);
        });
      });
    },
    stop() {
      return new Promise((resolve) => {
        if (!server) return resolve();
        server.close(() => resolve());
        server.closeAllConnections?.();
        server = null;
      });
    },
    get inFlight() {
      return inFlight;
    },
  };
}

module.exports = { createProxy, inspectRequest, isLocalHost };
