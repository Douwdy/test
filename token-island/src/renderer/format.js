// Mise en forme partagée par l'encoche et les réglages (et testée sous Node).
(function (root) {
  'use strict';

  const PROVIDERS = {
    anthropic: { name: 'Claude', color: '#d97757' },
    openai: { name: 'GPT', color: '#10a37f' },
    gemini: { name: 'Gemini', color: '#5b8def' },
    xai: { name: 'Grok', color: '#e4e4e7' },
    openrouter: { name: 'OpenRouter', color: '#8b5cf6' },
    mistral: { name: 'Mistral', color: '#fa520f' },
    deepseek: { name: 'DeepSeek', color: '#4d6bfe' },
    groq: { name: 'Groq', color: '#f55036' },
  };

  function provider(id) {
    return PROVIDERS[id] || { name: id ? id.charAt(0).toUpperCase() + id.slice(1) : 'Autre', color: '#a1a1aa' };
  }

  const compactFmt = new Intl.NumberFormat('fr-FR', { notation: 'compact', maximumFractionDigits: 1 });
  const intFmt = new Intl.NumberFormat('fr-FR');

  function tokens(n) {
    if (!n) return '0';
    return n < 10000 ? intFmt.format(Math.round(n)) : compactFmt.format(n).replace(/\s?k$/i, ' k');
  }

  /** Montant en dollars, converti en euros si l'utilisateur l'a demandé et a donné un taux. */
  function money(usd, display) {
    if (usd === null || usd === undefined || Number.isNaN(usd)) return '—';
    const eur = display && display.currency === 'EUR' && display.usdToEur > 0;
    const value = eur ? usd * display.usdToEur : usd;
    const digits = value !== 0 && Math.abs(value) < 0.1 ? 3 : 2;
    return new Intl.NumberFormat('fr-FR', {
      style: 'currency',
      currency: eur ? 'EUR' : 'USD',
      currencyDisplay: 'narrowSymbol',
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
    }).format(value);
  }

  function duration(ms) {
    if (ms <= 0) return 'maintenant';
    const min = Math.round(ms / 60e3);
    const h = Math.floor(min / 60);
    const m = min % 60;
    if (h >= 24) return `${Math.floor(h / 24)} j ${h % 24} h`;
    return h ? `${h} h ${String(m).padStart(2, '0')}` : `${m} min`;
  }

  /** Raccourcit les noms de modèles pour l'affichage (« claude-sonnet-4-6-20260101 » → « sonnet-4-6 »). */
  function model(name) {
    if (!name) return 'inconnu';
    return String(name)
      .replace(/^models\//, '')
      .replace(/^[a-z-]+\//, '')
      .replace(/^claude-/, '')
      .replace(/-\d{8}$/, '')
      .replace(/-latest$/, '');
  }

  const api = { PROVIDERS, provider, tokens, money, duration, model };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.TIFormat = api;
})(typeof self !== 'undefined' ? self : this);
