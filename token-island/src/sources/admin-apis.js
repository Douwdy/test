'use strict';

// API d'administration : elles voient toute la consommation d'une organisation, quel que soit
// l'outil qui a fait les appels (Cursor, scripts, serveurs…). Elles demandent une clé
// d'administration, distincte des clés d'API classiques :
//   OpenAI    : platform.openai.com → Settings → Organization → Admin keys
//   Anthropic : console Claude → Settings → Admin keys (sk-ant-admin…)
// Les données arrivent avec quelques minutes de retard ; on interroge les jours du mois en
// cours, agrégés par jour et par modèle, et on remplace l'agrégat de chaque jour à chaque passage.

const DAY = 86400e3;

function monthStartUtc(now) {
  const d = new Date(now);
  // Un jour de marge pour couvrir le décalage horaire avec UTC.
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1) - DAY;
}

async function getJson(fetchImpl, url, headers) {
  const res = await fetchImpl(url, { headers });
  const text = await res.text();
  if (!res.ok) {
    let msg = text.slice(0, 200);
    try {
      msg = JSON.parse(text).error?.message || msg;
    } catch {
      // corps non JSON
    }
    throw new Error(`HTTP ${res.status} : ${msg}`);
  }
  return JSON.parse(text);
}

function eventKey(prefix, startMs, model) {
  return `${prefix}:${new Date(startMs).toISOString().slice(0, 10)}:${model || 'inconnu'}`;
}

/** Anthropic : GET /v1/organizations/usage_report/messages */
async function fetchAnthropicUsage({ key, now = Date.now(), fetchImpl = fetch }) {
  const events = [];
  let page = null;
  for (let i = 0; i < 10; i++) {
    const url = new URL('https://api.anthropic.com/v1/organizations/usage_report/messages');
    url.searchParams.set('starting_at', new Date(monthStartUtc(now)).toISOString());
    url.searchParams.set('bucket_width', '1d');
    url.searchParams.set('limit', '31');
    url.searchParams.append('group_by[]', 'model');
    if (page) url.searchParams.set('page', page);
    const body = await getJson(fetchImpl, url, { 'x-api-key': key, 'anthropic-version': '2023-06-01' });
    for (const b of body.data || []) {
      const start = Date.parse(b.starting_at);
      for (const r of b.results || []) {
        const cc = r.cache_creation || {};
        events.push({
          id: eventKey('anthropic-admin', start, r.model),
          ts: start + DAY / 2,
          provider: 'anthropic',
          model: r.model || 'inconnu',
          source: 'anthropic-admin',
          billing: 'api',
          input: r.uncached_input_tokens || 0,
          cacheRead: r.cache_read_input_tokens || 0,
          cacheWrite: (cc.ephemeral_5m_input_tokens || 0) + (cc.ephemeral_1h_input_tokens || 0),
          output: r.output_tokens || 0,
          reasoning: 0,
        });
      }
    }
    if (!body.has_more || !body.next_page) break;
    page = body.next_page;
  }
  return events;
}

/** OpenAI : GET /v1/organization/usage/completions */
async function fetchOpenAIUsage({ key, now = Date.now(), fetchImpl = fetch }) {
  const events = [];
  let page = null;
  for (let i = 0; i < 10; i++) {
    const url = new URL('https://api.openai.com/v1/organization/usage/completions');
    url.searchParams.set('start_time', String(Math.floor(monthStartUtc(now) / 1000)));
    url.searchParams.set('bucket_width', '1d');
    url.searchParams.set('limit', '31');
    url.searchParams.append('group_by', 'model');
    if (page) url.searchParams.set('page', page);
    const body = await getJson(fetchImpl, url, { authorization: `Bearer ${key}` });
    for (const b of body.data || []) {
      const start = b.start_time * 1000;
      for (const r of b.results || []) {
        const cached = r.input_cached_tokens || 0;
        events.push({
          id: eventKey('openai-admin', start, r.model),
          ts: start + DAY / 2,
          provider: 'openai',
          model: r.model || 'inconnu',
          source: 'openai-admin',
          billing: 'api',
          input: Math.max(0, (r.input_tokens || 0) - cached),
          cacheRead: cached,
          cacheWrite: 0,
          output: r.output_tokens || 0,
          reasoning: 0,
        });
      }
    }
    if (!body.has_more || !body.next_page) break;
    page = body.next_page;
  }
  return events;
}

function createAdminSource({ name, configKey, fetchUsage, getConfig, getSecret, onEvents, onStatus }) {
  let timer = null;
  let running = false;

  async function poll() {
    if (running) return;
    const cfg = getConfig().sources[configKey];
    const key = getSecret(configKey);
    if (!cfg.enabled || !key) return;
    running = true;
    try {
      const events = await fetchUsage({ key });
      onEvents(events);
      onStatus(name, { ok: true, at: Date.now() });
    } catch (err) {
      onStatus(name, { ok: false, at: Date.now(), error: String(err.message || err) });
    } finally {
      running = false;
    }
  }

  return {
    name,
    poll,
    start() {
      this.stop();
      const cfg = getConfig().sources[configKey];
      if (!cfg.enabled) return;
      poll();
      timer = setInterval(poll, Math.max(1, cfg.intervalMin || 5) * 60e3);
    },
    stop() {
      if (timer) clearInterval(timer);
      timer = null;
    },
  };
}

module.exports = { fetchAnthropicUsage, fetchOpenAIUsage, createAdminSource };
