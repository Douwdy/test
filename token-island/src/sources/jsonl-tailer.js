'use strict';

const fs = require('node:fs');
const path = require('node:path');

// Lecture incrémentale de journaux JSON Lines : chaque passage ne lit que les octets ajoutés
// depuis le précédent. La position et le contexte de chaque fichier (modèle en cours,
// compteurs cumulés…) sont conservés dans `state`, que l'appelant enregistre sur disque.

const CHUNK = 1024 * 1024;

async function listFiles(root, accept, depth = 8, out = []) {
  let entries;
  try {
    entries = await fs.promises.readdir(root, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const e of entries) {
    const full = path.join(root, e.name);
    if (e.isDirectory() && depth > 0) await listFiles(full, accept, depth - 1, out);
    else if (e.isFile() && accept(full)) out.push(full);
  }
  return out;
}

/**
 * @param {object} opts
 * @param {() => string[]} opts.roots      dossiers à parcourir
 * @param {(file: string) => boolean} opts.accept
 * @param {string} [opts.prefilter]        sous-chaîne qu'une ligne doit contenir pour être lue
 * @param {(obj, ctx, file) => object[]|void} opts.parseLine
 * @param {object} opts.state              objet persistant { files: { [path]: { offset, ctx } } }
 */
function createJsonlTailer({ roots, accept, prefilter, parseLine, state }) {
  state.files ||= {};

  async function readNew(file, onEvent) {
    let stat;
    try {
      stat = await fs.promises.stat(file);
    } catch {
      return;
    }
    let entry = state.files[file];
    // Fichier tronqué ou remplacé : on repart du début (les identifiants évitent les doublons).
    if (!entry || stat.size < entry.offset) entry = state.files[file] = { offset: 0, ctx: {} };
    if (stat.size === entry.offset) return;

    const fh = await fs.promises.open(file, 'r');
    try {
      let pos = entry.offset;
      let carry = Buffer.alloc(0);
      const buf = Buffer.alloc(CHUNK);
      while (pos < stat.size) {
        const { bytesRead } = await fh.read(buf, 0, Math.min(CHUNK, stat.size - pos), pos);
        if (!bytesRead) break;
        pos += bytesRead;
        // Découpe sur les octets (et non le texte) pour ne jamais couper un caractère UTF-8.
        const data = carry.length ? Buffer.concat([carry, buf.subarray(0, bytesRead)]) : buf.subarray(0, bytesRead);
        const lastNl = data.lastIndexOf(10);
        if (lastNl < 0) {
          carry = Buffer.from(data);
          continue;
        }
        carry = Buffer.from(data.subarray(lastNl + 1));
        for (const line of data.toString('utf8', 0, lastNl).split('\n')) {
          if (!line || (prefilter && !line.includes(prefilter))) continue;
          let obj;
          try {
            obj = JSON.parse(line);
          } catch {
            continue;
          }
          const events = parseLine(obj, entry.ctx, file);
          if (events) for (const ev of events) onEvent(ev);
        }
      }
      // Une ligne incomplète (en cours d'écriture) sera relue au prochain passage.
      entry.offset = pos - carry.length;
    } finally {
      await fh.close();
    }
  }

  return {
    async scan(onEvent) {
      for (const root of roots()) {
        const files = await listFiles(root, accept);
        for (const file of files) await readNew(file, onEvent);
      }
    },
  };
}

module.exports = { createJsonlTailer, listFiles };
