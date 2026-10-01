'use strict';

// Réglages par défaut. Le fichier config.json de l'utilisateur est fusionné par-dessus,
// clé par clé, ce qui permet d'ajouter des réglages dans une nouvelle version sans
// casser les anciens fichiers.
const DEFAULT_CONFIG = {
  proxy: {
    enabled: true,
    port: 4141,
    // Ajoute stream_options.include_usage aux requêtes OpenAI en flux, sans quoi
    // l'API ne renvoie pas le nombre de tokens.
    injectStreamUsage: true,
  },
  // Préfixe local → API réelle. http://127.0.0.1:4141/openai/v1/… → https://api.openai.com/v1/…
  upstreams: {
    openai: 'https://api.openai.com',
    anthropic: 'https://api.anthropic.com',
    gemini: 'https://generativelanguage.googleapis.com',
    xai: 'https://api.x.ai',
    openrouter: 'https://openrouter.ai/api',
    mistral: 'https://api.mistral.ai',
    deepseek: 'https://api.deepseek.com',
    groq: 'https://api.groq.com/openai',
  },
  sources: {
    // « subscription » : utilisé avec un abonnement (Pro/Max, ChatGPT Plus…). Les tokens sont
    // comptés, mais le coût affiché n'est qu'un équivalent API et n'entre pas dans le budget.
    claudeCode: { enabled: true, subscription: true, paths: [] },
    codex: { enabled: true, subscription: true, paths: [] },
    openaiAdmin: { enabled: false, intervalMin: 5 },
    anthropicAdmin: { enabled: false, intervalMin: 5 },
  },
  budget: {
    monthlyUsd: 0, // 0 = pas de budget
    dailyTokens: 0,
    alerts: [50, 80, 100],
    includeSubscription: false,
  },
  // Pour la jauge du bloc de 5 h de Claude Code (Anthropic ne publie pas la limite exacte).
  claudeBlockTokenLimit: 0,
  display: {
    currency: 'USD', // ou 'EUR'
    usdToEur: 0,
    displayIndex: 0, // 0 = écran principal
    offsetY: 6,
    showCost: true,
    liveActivity: true,
  },
  pricing: [], // règles { match, input, output, cacheRead?, cacheWrite? } prioritaires
  startWithWindows: false,
  shortcut: 'CommandOrControl+Alt+T',
};

const isPlainObject = (v) => v && typeof v === 'object' && !Array.isArray(v);

function mergeDeep(base, over) {
  if (!isPlainObject(over)) return structuredClone(base);
  const out = structuredClone(base);
  for (const [k, v] of Object.entries(over)) {
    out[k] = isPlainObject(v) && isPlainObject(base[k]) ? mergeDeep(base[k], v) : structuredClone(v);
  }
  return out;
}

function resolveConfig(userConfig) {
  return mergeDeep(DEFAULT_CONFIG, userConfig);
}

module.exports = { DEFAULT_CONFIG, resolveConfig, mergeDeep };
