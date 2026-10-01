'use strict';

const path = require('node:path');
const fs = require('node:fs');
const {
  app,
  BrowserWindow,
  Tray,
  Menu,
  ipcMain,
  screen,
  nativeImage,
  globalShortcut,
  Notification,
  safeStorage,
  shell,
  dialog,
} = require('electron');

const { resolveConfig, DEFAULT_CONFIG } = require('../core/config');
const { UsageStore } = require('../core/store');
const { createPricing } = require('../core/pricing');
const { buildSnapshot, startOfMonth } = require('../core/aggregate');
const { createProxy } = require('../core/proxy');
const { createClaudeCodeSource, defaultRoots: claudeRoots } = require('../sources/claude-code');
const { createCodexSource, defaultRoots: codexRoots } = require('../sources/codex');
const { fetchAnthropicUsage, fetchOpenAIUsage, createAdminSource } = require('../sources/admin-apis');
const { trayBitmap } = require('./tray-icon');

if (!app.requestSingleInstanceLock()) {
  app.quit();
  process.exit(0);
}
app.setAppUserModelId('app.tokenisland');

// Taille fixe de la fenêtre transparente ; l'encoche, elle, s'anime en CSS à l'intérieur.
const WIN_W = 560;
const WIN_H = 480;
const LOG_SCAN_MS = 4000;

const dataDir = app.getPath('userData');
const files = {
  config: path.join(dataDir, 'config.json'),
  usage: path.join(dataDir, 'usage.jsonl'),
  tail: path.join(dataDir, 'tail-state.json'),
  secrets: path.join(dataDir, 'secrets.json'),
  alerts: path.join(dataDir, 'alerts-state.json'),
};

const readJson = (file, fallback) => {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    return fallback;
  }
};
const writeJson = (file, data) => {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(data, null, 2));
};

let userConfig = readJson(files.config, {});
let config = resolveConfig(userConfig);
let pricing = createPricing(config.pricing);
const getConfig = () => config;

// --- Secrets (clés d'administration), chiffrés par Windows (DPAPI) via safeStorage ---
const secrets = readJson(files.secrets, {});
function getSecret(name) {
  const v = secrets[name];
  if (!v) return null;
  try {
    return safeStorage.decryptString(Buffer.from(v, 'base64'));
  } catch {
    return null;
  }
}
function setSecret(name, value) {
  if (!value) delete secrets[name];
  else {
    if (!safeStorage.isEncryptionAvailable()) throw new Error('Chiffrement indisponible sur ce système');
    secrets[name] = safeStorage.encryptString(value).toString('base64');
  }
  writeJson(files.secrets, secrets);
}

// --- Données ---
const store = new UsageStore(files.usage).load();
const tailState = readJson(files.tail, { claudeCode: {}, codex: {} });
const sourceStatus = {};

const claudeCode = createClaudeCodeSource({ getConfig, state: (tailState.claudeCode ||= {}) });
const codex = createCodexSource({ getConfig, state: (tailState.codex ||= {}) });
const adminSources = [
  createAdminSource({
    name: 'anthropic-admin',
    configKey: 'anthropicAdmin',
    fetchUsage: fetchAnthropicUsage,
    getConfig,
    getSecret,
    onEvents: (evs) => evs.forEach((e) => store.upsert(e)),
    onStatus: (n, s) => setStatus(n, s),
  }),
  createAdminSource({
    name: 'openai-admin',
    configKey: 'openaiAdmin',
    fetchUsage: fetchOpenAIUsage,
    getConfig,
    getSecret,
    onEvents: (evs) => evs.forEach((e) => store.upsert(e)),
    onStatus: (n, s) => setStatus(n, s),
  }),
];

let island = null;
let settingsWin = null;
let tray = null;
let proxy = null;
let proxyState = { running: false, port: null, error: null, inFlight: 0 };

function setStatus(name, status) {
  sourceStatus[name] = status;
  scheduleSnapshot();
}

// --- Encoche ---
function targetDisplay() {
  const all = screen.getAllDisplays();
  const idx = config.display.displayIndex || 0;
  if (idx > 0 && all[idx - 1]) return all[idx - 1];
  return screen.getPrimaryDisplay();
}

function placeIsland() {
  if (!island) return;
  const { bounds } = targetDisplay();
  island.setBounds({
    x: Math.round(bounds.x + (bounds.width - WIN_W) / 2),
    y: bounds.y + (config.display.offsetY || 0),
    width: WIN_W,
    height: WIN_H,
  });
}

function createIsland() {
  island = new BrowserWindow({
    width: WIN_W,
    height: WIN_H,
    frame: false,
    transparent: true,
    resizable: false,
    movable: false,
    minimizable: false,
    maximizable: false,
    fullscreenable: false,
    skipTaskbar: true,
    hasShadow: false,
    focusable: false,
    alwaysOnTop: true,
    show: false,
    backgroundColor: '#00000000',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      sandbox: true,
      backgroundThrottling: false,
    },
  });
  island.setAlwaysOnTop(true, 'screen-saver');
  island.setVisibleOnAllWorkspaces(true);
  // Les clics traversent la fenêtre, sauf au-dessus de l'encoche (voir island:hover).
  island.setIgnoreMouseEvents(true, { forward: true });
  placeIsland();
  island.loadFile(path.join(__dirname, '../renderer/island.html'));
  island.once('ready-to-show', () => {
    island.showInactive();
    pushSnapshot();
  });
  island.on('closed', () => (island = null));
}

function openSettings() {
  if (settingsWin) {
    settingsWin.show();
    settingsWin.focus();
    return;
  }
  settingsWin = new BrowserWindow({
    width: 760,
    height: 820,
    minWidth: 560,
    minHeight: 500,
    title: 'Token Island — Réglages',
    backgroundColor: '#0d0f12',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      sandbox: true,
    },
  });
  settingsWin.loadFile(path.join(__dirname, '../renderer/settings.html'));
  settingsWin.on('closed', () => (settingsWin = null));
}

// --- Instantané envoyé à l'encoche ---
let snapshotTimer = null;
function scheduleSnapshot() {
  if (snapshotTimer) return;
  snapshotTimer = setTimeout(() => {
    snapshotTimer = null;
    pushSnapshot();
  }, 400);
}

function pushSnapshot() {
  const snap = buildSnapshot({
    events: store.events,
    config,
    pricing,
    extras: {
      codexLimits: codex.limits,
      proxy: proxyState,
      sources: sourceStatus,
      display: config.display,
    },
  });
  checkAlerts(snap);
  if (island && !island.isDestroyed()) island.webContents.send('snapshot', snap);
  if (tray) tray.setToolTip(trayTooltip(snap));
  return snap;
}

function trayTooltip(s) {
  const tok = new Intl.NumberFormat('fr-FR', { notation: 'compact', maximumFractionDigits: 1 }).format(s.today.tokens);
  return `Token Island — aujourd'hui : ${tok} tokens · ${s.today.cost.toFixed(2)} $`;
}

// --- Alertes de budget (une seule fois par seuil et par mois) ---
function checkAlerts(snap) {
  if (snap.budget.pct === null || !Notification.isSupported()) return;
  const month = new Date(startOfMonth(snap.now)).toISOString().slice(0, 7);
  const state = readJson(files.alerts, {});
  if (state.month !== month) {
    state.month = month;
    state.fired = [];
  }
  const due = [...(config.budget.alerts || [])].sort((a, b) => b - a).find((t) => snap.budget.pct >= t);
  if (due === undefined || state.fired.includes(due)) return;
  state.fired.push(...config.budget.alerts.filter((t) => t <= due && !state.fired.includes(t)));
  writeJson(files.alerts, state);
  const spent = snap.budget.monthlyUsd ? `${snap.budget.monthSpent.toFixed(2)} $ sur ${snap.budget.monthlyUsd} $` : '';
  new Notification({
    title: due >= 100 ? 'Budget IA dépassé' : `Budget IA à ${due} %`,
    body: spent || `Limite de tokens du jour atteinte à ${Math.round(snap.budget.pct)} %`,
    silent: false,
  }).show();
  if (island && !island.isDestroyed()) island.webContents.send('alert', { threshold: due });
}

// --- Évènements entrants ---
let initialScanDone = false;
function record(ev, { live = true } = {}) {
  const added = store.add(ev, { silent: true });
  if (!added) return;
  scheduleSnapshot();
  const fresh = Date.now() - ev.ts < 2 * 60e3;
  if (live && fresh && config.display.liveActivity && island && !island.isDestroyed()) {
    const tokens = ev.input + ev.cacheRead + ev.cacheWrite + ev.output;
    island.webContents.send('activity', { ...ev, tokens, cost: pricing.cost(ev.model, ev) });
  }
}

let scanning = false;
async function scanLogs() {
  if (scanning) return;
  scanning = true;
  try {
    const live = initialScanDone;
    if (config.sources.claudeCode.enabled) await claudeCode.scan((ev) => record(ev, { live }));
    if (config.sources.codex.enabled) await codex.scan((ev) => record(ev, { live }));
    writeJson(files.tail, tailState);
    if (!initialScanDone) {
      initialScanDone = true;
      pushSnapshot();
    }
  } catch (err) {
    setStatus('logs', { ok: false, at: Date.now(), error: String(err.message || err) });
  } finally {
    scanning = false;
  }
}

async function startProxy() {
  if (proxy) await proxy.stop();
  proxy = null;
  proxyState = { running: false, port: config.proxy.port, error: null, inFlight: 0 };
  if (!config.proxy.enabled) return scheduleSnapshot();
  proxy = createProxy({
    getConfig,
    onUsage: (ev) => {
      if (ev.client === 'claude-code' && config.sources.claudeCode.enabled) return;
      if (ev.client === 'codex' && config.sources.codex.enabled) return;
      record(ev);
    },
    onActivity: ({ inFlight, provider }) => {
      proxyState.inFlight = inFlight;
      if (island && !island.isDestroyed()) island.webContents.send('inflight', { inFlight, provider });
    },
  });
  try {
    proxyState.port = await proxy.start(config.proxy.port);
    proxyState.running = true;
  } catch (err) {
    proxyState.error = err.code === 'EADDRINUSE' ? `Port ${config.proxy.port} déjà utilisé` : String(err.message);
    proxy = null;
  }
  scheduleSnapshot();
}

function registerShortcut() {
  globalShortcut.unregisterAll();
  if (!config.shortcut) return;
  try {
    globalShortcut.register(config.shortcut, () => {
      if (!island) return;
      if (!island.isVisible()) island.showInactive();
      island.webContents.send('toggle-expand');
    });
  } catch {
    // Raccourci invalide : ignoré, l'utilisateur peut le corriger dans les réglages.
  }
}

function applyConfig(next) {
  const prev = config;
  userConfig = next;
  config = resolveConfig(userConfig);
  pricing = createPricing(config.pricing);
  writeJson(files.config, userConfig);
  if (prev.proxy.port !== config.proxy.port || prev.proxy.enabled !== config.proxy.enabled) startProxy();
  if (prev.shortcut !== config.shortcut) registerShortcut();
  if (JSON.stringify(prev.display) !== JSON.stringify(config.display)) placeIsland();
  if (JSON.stringify(prev.sources) !== JSON.stringify(config.sources)) adminSources.forEach((s) => s.start());
  if (process.platform === 'win32' || process.platform === 'darwin') {
    app.setLoginItemSettings({ openAtLogin: !!config.startWithWindows });
  }
  pushSnapshot();
}

async function exportCsv() {
  const { canceled, filePath } = await dialog.showSaveDialog({
    title: 'Exporter la consommation',
    defaultPath: path.join(app.getPath('documents'), `token-island-${new Date().toISOString().slice(0, 10)}.csv`),
    filters: [{ name: 'CSV', extensions: ['csv'] }],
  });
  if (canceled || !filePath) return null;
  fs.writeFileSync(filePath, '﻿' + store.toCsv(pricing));
  return filePath;
}

function buildTray() {
  const bmp = trayBitmap(32);
  const image = nativeImage.createFromBitmap(bmp.buffer, { width: bmp.width, height: bmp.height }).resize({ width: 16, height: 16 });
  tray = new Tray(image);
  const menu = () =>
    Menu.buildFromTemplate([
      {
        label: island && island.isVisible() ? "Masquer l'encoche" : "Afficher l'encoche",
        click: () => {
          if (!island) return;
          island.isVisible() ? island.hide() : island.showInactive();
        },
      },
      { label: 'Déplier / replier', accelerator: config.shortcut, click: () => island?.webContents.send('toggle-expand') },
      { type: 'separator' },
      { label: 'Réglages…', click: openSettings },
      { label: 'Exporter en CSV…', click: exportCsv },
      { label: 'Ouvrir le dossier des données', click: () => shell.openPath(dataDir) },
      { type: 'separator' },
      { label: 'Quitter', role: 'quit' },
    ]);
  tray.on('click', () => tray.popUpContextMenu(menu()));
  tray.on('right-click', () => tray.popUpContextMenu(menu()));
  tray.setToolTip('Token Island');
}

// --- IPC ---
ipcMain.on('island:hover', (_e, inside) => {
  if (island && !island.isDestroyed()) island.setIgnoreMouseEvents(!inside, { forward: true });
});
ipcMain.on('island:open-settings', openSettings);
ipcMain.handle('island:snapshot', () => pushSnapshot());

ipcMain.handle('settings:get', () => ({
  config: userConfig,
  resolved: config,
  defaults: DEFAULT_CONFIG,
  secrets: { anthropicAdmin: !!secrets.anthropicAdmin, openaiAdmin: !!secrets.openaiAdmin },
  encryption: safeStorage.isEncryptionAvailable(),
  detected: {
    claudeCode: claudeRoots().map((p) => ({ path: p, exists: fs.existsSync(p) })),
    codex: codexRoots().map((p) => ({ path: p, exists: fs.existsSync(p) })),
  },
  proxy: proxyState,
  sources: sourceStatus,
  displays: screen.getAllDisplays().map((d, i) => ({ index: i + 1, label: `${d.size.width}×${d.size.height}` })),
  dataDir,
  eventCount: store.events.length,
}));
ipcMain.handle('settings:save', (_e, next) => {
  applyConfig(next);
  return true;
});
ipcMain.handle('settings:set-secret', (_e, name, value) => {
  if (!['anthropicAdmin', 'openaiAdmin'].includes(name)) throw new Error('Secret inconnu');
  setSecret(name, value);
  adminSources.forEach((s) => s.start());
  return true;
});
ipcMain.handle('settings:test-admin', async (_e, name) => {
  const key = getSecret(name);
  if (!key) return { ok: false, error: 'Aucune clé enregistrée' };
  try {
    const fetchUsage = name === 'anthropicAdmin' ? fetchAnthropicUsage : fetchOpenAIUsage;
    const events = await fetchUsage({ key });
    return { ok: true, count: events.length };
  } catch (err) {
    return { ok: false, error: String(err.message || err) };
  }
});
ipcMain.handle('settings:export-csv', exportCsv);
ipcMain.handle('settings:open-data', () => shell.openPath(dataDir));

// --- Démarrage ---
app.on('second-instance', openSettings);
app.on('window-all-closed', (e) => e.preventDefault());
app.on('will-quit', () => globalShortcut.unregisterAll());

app.whenReady().then(async () => {
  buildTray();
  createIsland();
  registerShortcut();
  screen.on('display-metrics-changed', placeIsland);
  screen.on('display-added', placeIsland);
  screen.on('display-removed', placeIsland);
  await startProxy();
  adminSources.forEach((s) => s.start());
  scanLogs();
  setInterval(scanLogs, LOG_SCAN_MS);
  // Rafraîchit les fenêtres glissantes (dernière heure, bloc de 5 h, changement de jour).
  setInterval(pushSnapshot, 30e3);
  if (!fs.existsSync(files.config)) {
    writeJson(files.config, {});
    openSettings();
  }
});
