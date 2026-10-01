'use strict';

const F = window.TIFormat;
const $ = (id) => document.getElementById(id);
const el = $('island');

let snap = null;
let hovering = false;
let pinned = false;
let liveTimer = null;
let collapseTimer = null;
let inflight = 0;

// ---------- États : compact, live (activité en direct), expanded ----------
function setState(state) {
  if (state === 'expanded') measureExpanded();
  el.dataset.state = state;
}

function restingState() {
  if (hovering || pinned) return 'expanded';
  if (liveTimer) return 'live';
  return 'compact';
}

function refreshState() {
  setState(restingState());
}

el.addEventListener('mouseenter', () => {
  clearTimeout(collapseTimer);
  hovering = true;
  window.island.hover(true);
  refreshState();
});

el.addEventListener('mouseleave', () => {
  hovering = false;
  window.island.hover(false);
  collapseTimer = setTimeout(refreshState, 250);
});

el.addEventListener('click', (e) => {
  if (e.target.closest('button')) return;
  pinned = !pinned;
  refreshState();
});

$('btn-settings').addEventListener('click', () => window.island.openSettings());

window.island.onToggleExpand(() => {
  pinned = !pinned;
  refreshState();
});

// Hauteur de la vue dépliée calculée d'après son contenu.
function measureExpanded() {
  const box = el.querySelector('.expanded');
  const style = getComputedStyle(box);
  const gap = parseFloat(style.rowGap) || 0;
  const kids = [...box.children].filter((c) => getComputedStyle(c).display !== 'none');
  const h =
    kids.reduce((sum, c) => sum + c.offsetHeight, 0) +
    gap * Math.max(0, kids.length - 1) +
    parseFloat(style.paddingTop) +
    parseFloat(style.paddingBottom);
  el.style.setProperty('--expanded-h', `${Math.min(470, Math.ceil(h))}px`);
}

// ---------- Rendu ----------
function levelColor(pct) {
  if (pct === null || pct === undefined) return 'var(--ok)';
  if (pct >= 100) return 'var(--bad)';
  if (pct >= 80) return 'var(--warn)';
  return 'var(--ok)';
}

function render() {
  if (!snap) return;
  const d = snap.display || {};
  const showCost = d.showCost !== false;
  const pct = snap.budget.pct;

  el.style.setProperty('--accent', levelColor(pct));
  el.style.setProperty('--budget', pct === null ? 0 : Math.min(1, pct / 100));

  // Compact
  $('c-tokens').textContent = `${F.tokens(snap.today.tokens)} tok`;
  $('c-cost').textContent = showCost ? F.money(snap.today.cost, d) : `${snap.today.requests} req.`;
  $('c-rate').textContent = snap.tokensPerMin >= 1 ? `${F.tokens(snap.tokensPerMin)}/min` : '';

  // En-tête
  $('x-tokens').textContent = F.tokens(snap.today.tokens);
  const t = snap.today;
  const parts = [`${F.tokens(t.input + t.cacheWrite)} entrée`, `${F.tokens(t.output)} sortie`];
  if (t.cacheRead) parts.push(`${F.tokens(t.cacheRead)} cache`);
  parts.push(`${t.requests} req.`);
  if (showCost) parts.push(F.money(t.cost, d));
  $('x-split').textContent = parts.join(' · ');

  // Anneau : budget du mois s'il existe, sinon simple total du mois.
  const ring = $('ring-fg');
  const C = 113.1;
  if (snap.budget.monthlyUsd) {
    $('x-cost').textContent = F.money(snap.budget.monthSpent, d);
    $('x-cost-sub').textContent = `sur ${F.money(snap.budget.monthlyUsd, d)}`;
    ring.style.strokeDashoffset = String(C * (1 - Math.min(1, snap.budget.monthSpent / snap.budget.monthlyUsd)));
  } else {
    $('x-cost').textContent = showCost ? F.money(snap.month.cost, d) : F.tokens(snap.month.tokens);
    $('x-cost-sub').textContent = 'ce mois';
    ring.style.strokeDashoffset = String(C);
  }

  renderSpark();
  renderProviders(d, showCost);
  renderModels(d, showCost);
  renderLimits();

  const p = snap.proxy || {};
  const ps = $('x-proxy');
  if (p.running) {
    ps.textContent = `Proxy :${p.port}${inflight ? ` · ${inflight} en cours` : ''}`;
    ps.style.setProperty('--st', 'var(--ok)');
  } else {
    ps.textContent = p.error ? `Proxy : ${p.error}` : 'Proxy arrêté';
    ps.style.setProperty('--st', p.error ? 'var(--bad)' : 'var(--muted)');
  }
  $('x-projection').textContent =
    showCost && snap.budget.projection > 0 ? `Projection fin de mois : ${F.money(snap.budget.projection, d)}` : '';

  if (el.dataset.state === 'expanded') measureExpanded();
}

function renderSpark() {
  const svg = $('spark');
  const max = Math.max(1, ...snap.hourly);
  const w = 240 / 24;
  svg.replaceChildren(
    ...snap.hourly.map((v, i) => {
      const r = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
      const h = v ? Math.max(2, (v / max) * 34) : 1;
      r.setAttribute('x', String(i * w + 1));
      r.setAttribute('y', String(36 - h));
      r.setAttribute('width', String(w - 2));
      r.setAttribute('height', String(h));
      r.setAttribute('rx', '1.5');
      if (i === 23) r.setAttribute('class', 'now');
      const title = document.createElementNS('http://www.w3.org/2000/svg', 'title');
      title.textContent = `${F.tokens(v)} tokens`;
      r.append(title);
      return r;
    }),
  );
  $('x-rate').textContent = snap.tokensPerMin >= 1 ? `${F.tokens(snap.tokensPerMin)} tok/min` : '';
}

function li(html) {
  const node = document.createElement('li');
  node.append(...html);
  return node;
}

function span(text, cls) {
  const s = document.createElement('span');
  s.textContent = text;
  if (cls) s.className = cls;
  return s;
}

function renderProviders(d, showCost) {
  const ul = $('providers');
  if (!snap.providers.length) {
    ul.replaceChildren(li([span('Rien pour le moment', 'empty')]));
    return;
  }
  const max = Math.max(...snap.providers.map((p) => p.tokens), 1);
  ul.replaceChildren(
    ...snap.providers.slice(0, 5).map((p) => {
      const info = F.provider(p.provider);
      const track = document.createElement('div');
      track.className = 'track';
      const fill = document.createElement('div');
      fill.className = 'fill';
      fill.style.width = `${(p.tokens / max) * 100}%`;
      track.append(fill);
      const node = li([span(info.name), span(showCost ? `${F.tokens(p.tokens)} · ${F.money(p.cost, d)}` : F.tokens(p.tokens)), track]);
      node.style.setProperty('--pc', info.color);
      return node;
    }),
  );
}

function renderModels(d, showCost) {
  const ul = $('models');
  if (!snap.models.length) {
    ul.replaceChildren(li([span('—', 'empty')]));
    return;
  }
  ul.replaceChildren(
    ...snap.models.slice(0, 4).map((m) => {
      const name = span(F.model(m.model), 'm-name');
      name.title = m.model;
      const node = li([name, span(showCost && m.unpriced === 0 ? F.money(m.cost, d) : F.tokens(m.tokens), 'muted')]);
      node.style.setProperty('--pc', F.provider(m.provider).color);
      return node;
    }),
  );
}

function limitCard(title, right, value, pct) {
  const card = document.createElement('div');
  card.className = 'limit';
  card.append(span(title, 'l-title'), span(value, 'l-value'));
  if (right) card.append(span(right, 'l-reset'));
  if (pct !== null && pct !== undefined) {
    const track = document.createElement('div');
    track.className = 'track';
    const fill = document.createElement('div');
    fill.className = 'fill';
    fill.style.width = `${Math.min(100, pct)}%`;
    card.style.setProperty('--lc', levelColor(pct));
    track.append(fill);
    card.append(track);
  }
  return card;
}

function renderLimits() {
  const box = $('limits');
  const cards = [];
  const b = snap.claudeBlock;
  if (b) {
    const value = b.limit ? `${F.tokens(b.tokens)} / ${F.tokens(b.limit)}` : `${F.tokens(b.tokens)} tokens`;
    cards.push(limitCard('Claude Code · 5 h', `↻ dans ${F.duration(b.remainingMs)}`, value, b.pct));
  }
  const c = snap.codexLimits;
  if (c && c.updatedAt > snap.now - 7 * 86400e3) {
    for (const [key, label] of [
      ['primary', 'Codex · 5 h'],
      ['secondary', 'Codex · semaine'],
    ]) {
      const l = c[key];
      if (!l) continue;
      const reset = l.resetsAt ? `↻ dans ${F.duration(l.resetsAt - snap.now)}` : '';
      cards.push(limitCard(label, reset, `${Math.round(l.usedPercent)} % utilisés`, l.usedPercent));
    }
  }
  box.replaceChildren(...cards.slice(0, 3));
}

// ---------- Évènements du processus principal ----------
window.island.onSnapshot((s) => {
  snap = s;
  render();
});

window.island.onInflight(({ inFlight, provider }) => {
  inflight = inFlight;
  const dot = $('dot');
  dot.classList.toggle('busy', inFlight > 0);
  if (inFlight > 0 && provider) dot.style.background = F.provider(provider).color;
  else dot.style.background = '';
});

window.island.onActivity((ev) => {
  const info = F.provider(ev.provider);
  el.style.setProperty('--pc', info.color);
  $('live-provider').textContent = info.name;
  $('live-model').textContent = F.model(ev.model) + (ev.app ? ` · ${ev.app}` : '');
  $('live-tokens').textContent = `+${F.tokens(ev.tokens)} tok`;
  $('live-cost').textContent = snap && snap.display && snap.display.showCost === false ? '' : F.money(ev.cost, snap?.display);
  clearTimeout(liveTimer);
  liveTimer = setTimeout(() => {
    liveTimer = null;
    refreshState();
  }, 3500);
  refreshState();
});

window.island.onAlert(() => {
  el.classList.remove('alert');
  void el.offsetWidth;
  el.classList.add('alert');
});

window.island.snapshot().then((s) => {
  if (s) {
    snap = s;
    render();
  }
});
