'use strict';

// Tarifs en dollars par million de tokens. Les valeurs par défaut sont indicatives
// (relevées en octobre 2026 pour Claude, plus anciennes pour les autres) : vérifie-les et
// surcharge-les dans les réglages (« Tarifs personnalisés »). La première règle dont le motif
// correspond au nom du modèle l'emporte, donc les variantes précises passent avant les génériques.
//   cacheRead  absent → 10 % du prix d'entrée
//   cacheWrite absent → 125 % du prix d'entrée
const DEFAULT_PRICES = [
  // Anthropic
  { match: 'claude-(fable|mythos)-5', input: 10, output: 50, cacheRead: 0.25 },
  { match: 'claude-opus-5-5', input: 4, output: 20, cacheRead: 0.2 },
  { match: 'claude-opus-(5|4-[5-9])', input: 5, output: 25 },
  { match: 'claude-(3-)?opus', input: 15, output: 75 },
  { match: 'claude-sonnet-5', input: 2, output: 10, cacheRead: 0.2 },
  { match: 'claude-(3-[57]-)?sonnet', input: 3, output: 15 },
  { match: 'claude-haiku-4', input: 1, output: 5 },
  { match: 'claude-3-5-haiku', input: 0.8, output: 4 },
  { match: 'claude-3-haiku', input: 0.25, output: 1.25 },
  // OpenAI
  { match: 'gpt-5.*nano', input: 0.05, output: 0.4, cacheRead: 0.005 },
  { match: 'gpt-5.*mini', input: 0.25, output: 2, cacheRead: 0.025 },
  { match: 'gpt-5', input: 1.25, output: 10, cacheRead: 0.125 },
  { match: 'gpt-4\\.1-nano', input: 0.1, output: 0.4, cacheRead: 0.025 },
  { match: 'gpt-4\\.1-mini', input: 0.4, output: 1.6, cacheRead: 0.1 },
  { match: 'gpt-4\\.1', input: 2, output: 8, cacheRead: 0.5 },
  { match: 'gpt-4o-mini', input: 0.15, output: 0.6, cacheRead: 0.075 },
  { match: 'gpt-4o', input: 2.5, output: 10, cacheRead: 1.25 },
  { match: 'o4-mini', input: 1.1, output: 4.4, cacheRead: 0.275 },
  { match: '^o3', input: 2, output: 8, cacheRead: 0.5 },
  { match: 'text-embedding-3-small', input: 0.02, output: 0 },
  { match: 'text-embedding-3-large', input: 0.13, output: 0 },
  // Google
  { match: 'gemini-2\\.5-pro', input: 1.25, output: 10, cacheRead: 0.31 },
  { match: 'gemini-2\\.5-flash-lite', input: 0.1, output: 0.4, cacheRead: 0.025 },
  { match: 'gemini-2\\.5-flash', input: 0.3, output: 2.5, cacheRead: 0.075 },
  { match: 'gemini-2\\.0-flash', input: 0.1, output: 0.4, cacheRead: 0.025 },
  // xAI
  { match: 'grok-code-fast', input: 0.2, output: 1.5, cacheRead: 0.02 },
  { match: 'grok-4.*fast', input: 0.2, output: 0.5, cacheRead: 0.05 },
  { match: 'grok-3-mini', input: 0.3, output: 0.5, cacheRead: 0.075 },
  { match: 'grok-[34]', input: 3, output: 15, cacheRead: 0.75 },
];

function compile(rules) {
  const out = [];
  for (const r of rules || []) {
    if (!r || typeof r.match !== 'string') continue;
    try {
      out.push({ ...r, re: new RegExp(r.match, 'i') });
    } catch {
      // Motif invalide saisi dans les réglages : ignoré plutôt que de tout casser.
    }
  }
  return out;
}

function createPricing(customRules = []) {
  const rules = [...compile(customRules), ...compile(DEFAULT_PRICES)];
  const cache = new Map();

  function priceFor(model) {
    if (!model) return null;
    if (cache.has(model)) return cache.get(model);
    // Noms du type « models/gemini-2.5-pro » ou « anthropic/claude-sonnet-4.5 » (OpenRouter).
    const name = String(model).replace(/^models\//, '').replace(/\./g, (m, i, s) => (/claude/i.test(s) ? '-' : m));
    const rule = rules.find((r) => r.re.test(name)) || null;
    const price = rule && {
      input: rule.input,
      output: rule.output,
      cacheRead: rule.cacheRead ?? rule.input * 0.1,
      cacheWrite: rule.cacheWrite ?? rule.input * 1.25,
    };
    cache.set(model, price);
    return price;
  }

  /** Coût en dollars, ou null si le modèle n'a pas de tarif connu. */
  function cost(model, u) {
    const p = priceFor(model);
    if (!p) return null;
    return (
      (u.input * p.input + u.cacheRead * p.cacheRead + u.cacheWrite * p.cacheWrite + u.output * p.output) / 1e6
    );
  }

  return { priceFor, cost };
}

module.exports = { DEFAULT_PRICES, createPricing };
