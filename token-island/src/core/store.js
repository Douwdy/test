'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { EventEmitter } = require('node:events');

// Journal des consommations : un évènement par requête (ou par jour et par modèle pour
// les API d'administration), en JSON Lines dans le dossier de données de l'app.
//
// Évènement :
//   { id, ts, provider, model, source, app?, billing, input, cacheRead, cacheWrite, output, reasoning }
// `id` est déterministe pour les sources relues (journaux, API admin) : relire deux fois le
// même fichier ne compte donc rien en double.

const FIELDS = ['input', 'cacheRead', 'cacheWrite', 'output', 'reasoning'];

class UsageStore extends EventEmitter {
  constructor(file, { retentionDays = 400 } = {}) {
    super();
    this.file = file;
    this.retentionMs = retentionDays * 86400e3;
    this.events = [];
    this.byId = new Map();
  }

  load() {
    let raw = '';
    try {
      raw = fs.readFileSync(this.file, 'utf8');
    } catch {
      return this;
    }
    const cutoff = Date.now() - this.retentionMs;
    let lines = 0;
    for (const line of raw.split('\n')) {
      if (!line.trim()) continue;
      lines++;
      try {
        const ev = JSON.parse(line);
        if (ev && ev.id && ev.ts >= cutoff) this.#put(ev);
      } catch {
        // Ligne tronquée (arrêt brutal pendant une écriture) : ignorée.
      }
    }
    // Réécrit le fichier s'il contient des doublons (mises à jour) ou des entrées expirées.
    if (lines > this.events.length * 1.2 + 100) this.compact();
    return this;
  }

  compact() {
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    const tmp = `${this.file}.tmp`;
    fs.writeFileSync(tmp, this.events.map((e) => JSON.stringify(e)).join('\n') + (this.events.length ? '\n' : ''));
    fs.renameSync(tmp, this.file);
  }

  #put(ev) {
    const idx = this.byId.get(ev.id);
    if (idx === undefined) {
      this.byId.set(ev.id, this.events.length);
      this.events.push(ev);
    } else {
      this.events[idx] = ev;
    }
  }

  static clean(ev) {
    const out = {
      id: String(ev.id),
      ts: Number(ev.ts) || Date.now(),
      provider: ev.provider || 'autre',
      model: ev.model || 'inconnu',
      source: ev.source || 'manuel',
      billing: ev.billing === 'subscription' ? 'subscription' : 'api',
    };
    if (ev.app) out.app = String(ev.app).slice(0, 80);
    for (const f of FIELDS) out[f] = Math.max(0, Math.round(Number(ev[f]) || 0));
    return out;
  }

  /** Ajoute un évènement ; renvoie false s'il était déjà connu. */
  add(ev, { silent = false } = {}) {
    if (this.byId.has(ev.id)) return false;
    const clean = UsageStore.clean(ev);
    this.#put(clean);
    this.#append(clean);
    if (!silent) this.emit('added', clean);
    return true;
  }

  /** Ajoute ou remplace (utilisé pour les agrégats quotidiens des API d'administration). */
  upsert(ev) {
    const clean = UsageStore.clean(ev);
    const prev = this.byId.has(clean.id) ? this.events[this.byId.get(clean.id)] : null;
    if (prev && FIELDS.every((f) => prev[f] === clean[f])) return false;
    this.#put(clean);
    this.#append(clean);
    this.emit('changed');
    return true;
  }

  #append(ev) {
    try {
      fs.mkdirSync(path.dirname(this.file), { recursive: true });
      fs.appendFileSync(this.file, JSON.stringify(ev) + '\n');
    } catch (err) {
      this.emit('error', err);
    }
  }

  since(ts) {
    return this.events.filter((e) => e.ts >= ts);
  }

  toCsv(pricing) {
    const head = ['date', 'fournisseur', 'modele', 'source', 'app', 'facturation', ...FIELDS, 'cout_usd'];
    const esc = (v) => (/[",;\n]/.test(String(v)) ? `"${String(v).replace(/"/g, '""')}"` : String(v));
    const rows = [...this.events]
      .sort((a, b) => a.ts - b.ts)
      .map((e) => {
        const c = pricing.cost(e.model, e);
        return [
          new Date(e.ts).toISOString(),
          e.provider,
          e.model,
          e.source,
          e.app || '',
          e.billing,
          ...FIELDS.map((f) => e[f]),
          c === null ? '' : c.toFixed(6),
        ]
          .map(esc)
          .join(',');
      });
    return [head.join(','), ...rows].join('\n') + '\n';
  }
}

module.exports = { UsageStore, FIELDS };
