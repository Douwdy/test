'use strict';

const $ = (id) => document.getElementById(id);
let data = null;

const lines = (text) =>
  text
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter(Boolean);

function showResult(node, ok, text) {
  node.textContent = text;
  node.className = `result ${ok ? 'ok' : 'bad'}`;
}

function detectedList(node, items) {
  node.replaceChildren(
    ...items.map((d) => {
      const div = document.createElement('div');
      div.textContent = `${d.exists ? '✓' : '·'} ${d.path}${d.exists ? '' : ' (absent)'}`;
      if (d.exists) div.className = 'ok';
      return div;
    }),
  );
}

function renderProxy(cfg) {
  const port = cfg.proxy.port;
  const base = `http://127.0.0.1:${port}`;
  const st = $('proxy-status');
  if (data.proxy.running) {
    st.textContent = `● En écoute sur ${base}`;
    st.className = 'status ok';
  } else {
    st.textContent = data.proxy.error ? `● ${data.proxy.error}` : '● Proxy arrêté';
    st.className = `status ${data.proxy.error ? 'bad' : ''}`;
  }

  const examples = {
    openai: `${base}/openai/v1`,
    anthropic: `${base}/anthropic`,
    gemini: `${base}/gemini (API native) ou ${base}/gemini/v1beta/openai (compatible OpenAI)`,
    xai: `${base}/xai/v1`,
    openrouter: `${base}/openrouter/v1`,
    mistral: `${base}/mistral/v1`,
    deepseek: `${base}/deepseek/v1`,
    groq: `${base}/groq/v1`,
  };
  const table = $('routes');
  table.replaceChildren(
    ...Object.keys(cfg.upstreams).map((p) => {
      const tr = document.createElement('tr');
      const a = document.createElement('td');
      a.textContent = window.TIFormat.provider(p).name;
      const b = document.createElement('td');
      b.textContent = examples[p] || `${base}/${p}`;
      tr.append(a, b);
      return tr;
    }),
  );

  $('env-snippet').textContent = [
    `[Environment]::SetEnvironmentVariable('OPENAI_BASE_URL', '${base}/openai/v1', 'User')`,
    `[Environment]::SetEnvironmentVariable('ANTHROPIC_BASE_URL', '${base}/anthropic', 'User')`,
    '',
    '# Python : OpenAI(base_url="' + base + '/xai/v1", api_key=XAI_API_KEY) pour Grok, etc.',
  ].join('\n');
}

function fill() {
  const c = data.resolved;
  renderProxy(c);
  $('proxy-enabled').checked = c.proxy.enabled;
  $('proxy-port').value = c.proxy.port;
  $('proxy-inject').checked = c.proxy.injectStreamUsage;

  detectedList($('cc-detected'), data.detected.claudeCode);
  $('cc-enabled').checked = c.sources.claudeCode.enabled;
  $('cc-sub').checked = c.sources.claudeCode.subscription;
  $('cc-paths').value = (c.sources.claudeCode.paths || []).join('\n');
  $('cc-limit').value = c.claudeBlockTokenLimit || 0;

  detectedList($('codex-detected'), data.detected.codex);
  $('codex-enabled').checked = c.sources.codex.enabled;
  $('codex-sub').checked = c.sources.codex.subscription;
  $('codex-paths').value = (c.sources.codex.paths || []).join('\n');

  for (const name of ['anthropicAdmin', 'openaiAdmin']) {
    $(`${name}-enabled`).checked = c.sources[name].enabled;
    $(`${name}-key`).placeholder = data.secrets[name] ? '•••••••• (enregistrée)' : 'colle la clé ici';
    const status = data.sources[name === 'anthropicAdmin' ? 'anthropic-admin' : 'openai-admin'];
    if (status) {
      const when = new Date(status.at).toLocaleTimeString('fr-FR');
      showResult($(`${name}-result`), status.ok, status.ok ? `Dernière synchro à ${when}` : `Erreur à ${when} : ${status.error}`);
    }
  }
  $('encryption-hint').textContent = data.encryption
    ? 'Les clés sont chiffrées avec ton compte Windows (DPAPI) et ne quittent pas cet ordinateur, sauf vers l\'API concernée.'
    : 'Chiffrement indisponible : impossible d\'enregistrer une clé sur ce système.';

  $('budget-usd').value = c.budget.monthlyUsd || 0;
  $('budget-tokens').value = c.budget.dailyTokens || 0;
  $('budget-alerts').value = (c.budget.alerts || []).join(', ');
  $('budget-sub').checked = c.budget.includeSubscription;

  $('currency').value = c.display.currency;
  $('usd-eur').value = c.display.usdToEur || '';
  const sel = $('display');
  sel.replaceChildren(
    new Option('Écran principal', '0'),
    ...data.displays.map((d) => new Option(`Écran ${d.index} (${d.label})`, String(d.index))),
  );
  sel.value = String(c.display.displayIndex || 0);
  $('offset-y').value = c.display.offsetY;
  $('shortcut').value = c.shortcut || '';
  $('show-cost').checked = c.display.showCost;
  $('live').checked = c.display.liveActivity;
  $('autostart').checked = c.startWithWindows;

  $('pricing').value = c.pricing.length ? JSON.stringify(c.pricing, null, 2) : '';
  $('data-info').textContent = `${data.eventCount.toLocaleString('fr-FR')} évènements enregistrés dans ${data.dataDir}`;
}

function collect() {
  const next = structuredClone(data.config || {});
  const num = (id, fallback = 0) => {
    const v = Number($(id).value);
    return Number.isFinite(v) && v >= 0 ? v : fallback;
  };

  let pricing = [];
  const raw = $('pricing').value.trim();
  if (raw) {
    pricing = JSON.parse(raw); // l'erreur éventuelle est affichée par l'appelant
    if (!Array.isArray(pricing)) throw new Error('Les tarifs doivent être une liste [ … ]');
  }

  next.proxy = {
    enabled: $('proxy-enabled').checked,
    port: Math.round(num('proxy-port', 4141)) || 4141,
    injectStreamUsage: $('proxy-inject').checked,
  };
  next.sources = {
    ...(next.sources || {}),
    claudeCode: { enabled: $('cc-enabled').checked, subscription: $('cc-sub').checked, paths: lines($('cc-paths').value) },
    codex: { enabled: $('codex-enabled').checked, subscription: $('codex-sub').checked, paths: lines($('codex-paths').value) },
    anthropicAdmin: { ...(next.sources?.anthropicAdmin || {}), enabled: $('anthropicAdmin-enabled').checked },
    openaiAdmin: { ...(next.sources?.openaiAdmin || {}), enabled: $('openaiAdmin-enabled').checked },
  };
  next.claudeBlockTokenLimit = num('cc-limit');
  next.budget = {
    monthlyUsd: num('budget-usd'),
    dailyTokens: num('budget-tokens'),
    alerts: $('budget-alerts')
      .value.split(/[,;\s]+/)
      .map(Number)
      .filter((v) => Number.isFinite(v) && v > 0)
      .sort((a, b) => a - b),
    includeSubscription: $('budget-sub').checked,
  };
  next.display = {
    currency: $('currency').value,
    usdToEur: num('usd-eur'),
    displayIndex: Number($('display').value) || 0,
    offsetY: num('offset-y'),
    showCost: $('show-cost').checked,
    liveActivity: $('live').checked,
  };
  next.shortcut = $('shortcut').value.trim();
  next.startWithWindows = $('autostart').checked;
  next.pricing = pricing;
  return next;
}

async function load() {
  data = await window.settings.get();
  fill();
}

$('save').addEventListener('click', async () => {
  $('pricing-error').textContent = '';
  let next;
  try {
    next = collect();
  } catch (err) {
    showResult($('pricing-error'), false, `Tarifs invalides : ${err.message}`);
    showResult($('save-result'), false, 'Non enregistré');
    return;
  }
  await window.settings.save(next);
  showResult($('save-result'), true, 'Enregistré');
  await load();
});

$('copy-env').addEventListener('click', async () => {
  await navigator.clipboard.writeText($('env-snippet').textContent);
  $('copy-env').textContent = 'Copié';
  setTimeout(() => ($('copy-env').textContent = 'Copier'), 1500);
});

for (const box of document.querySelectorAll('[data-admin]')) {
  const name = box.dataset.admin;
  const result = $(`${name}-result`);
  box.addEventListener('click', async (e) => {
    const action = e.target.dataset?.action;
    if (!action) return;
    try {
      if (action === 'save-key') {
        const key = $(`${name}-key`).value.trim();
        if (!key) return showResult(result, false, 'Colle la clé d\'abord');
        await window.settings.setSecret(name, key);
        $(`${name}-key`).value = '';
        showResult(result, true, 'Clé enregistrée');
      } else if (action === 'forget') {
        await window.settings.setSecret(name, '');
        showResult(result, true, 'Clé supprimée');
      } else if (action === 'test') {
        showResult(result, true, 'Test en cours…');
        const r = await window.settings.testAdmin(name);
        showResult(result, r.ok, r.ok ? `OK : ${r.count} lignes ce mois-ci` : r.error);
        return;
      }
      await load();
    } catch (err) {
      showResult(result, false, String(err.message || err));
    }
  });
}

$('export').addEventListener('click', async () => {
  const file = await window.settings.exportCsv();
  if (file) $('data-info').textContent = `Exporté : ${file}`;
});
$('open-data').addEventListener('click', () => window.settings.openData());

load();
