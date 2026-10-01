'use strict';

// Lecture des compteurs de tokens renvoyés par les API (OpenAI, Anthropic, Gemini, xAI,
// et tout ce qui imite le format OpenAI : OpenRouter, Mistral, DeepSeek, Groq…).
//
// Tout est ramené à une forme unique, pensée pour le calcul du coût :
//   input      tokens d'entrée facturés plein tarif (hors cache)
//   cacheRead  tokens d'entrée lus depuis le cache
//   cacheWrite tokens d'entrée écrits dans le cache (Anthropic)
//   output     tokens de sortie, raisonnement compris
//   reasoning  part de `output` consacrée au raisonnement (information seulement)

const EMPTY = Object.freeze({ input: 0, cacheRead: 0, cacheWrite: 0, output: 0, reasoning: 0 });

const n = (v) => (Number.isFinite(v) && v > 0 ? v : 0);

function emptyUsage() {
  return { ...EMPTY };
}

function totalTokens(u) {
  return u.input + u.cacheRead + u.cacheWrite + u.output;
}

function isEmpty(u) {
  return !u || totalTokens(u) === 0;
}

function addUsage(a, b) {
  return {
    input: a.input + b.input,
    cacheRead: a.cacheRead + b.cacheRead,
    cacheWrite: a.cacheWrite + b.cacheWrite,
    output: a.output + b.output,
    reasoning: a.reasoning + b.reasoning,
  };
}

// Gemini : `usageMetadata`. promptTokenCount inclut le cache, candidatesTokenCount exclut
// les tokens de réflexion (facturés comme sortie).
function fromGemini(m) {
  const cached = n(m.cachedContentTokenCount);
  const thoughts = n(m.thoughtsTokenCount);
  return {
    input: Math.max(0, n(m.promptTokenCount) - cached) + n(m.toolUsePromptTokenCount),
    cacheRead: cached,
    cacheWrite: 0,
    output: n(m.candidatesTokenCount) + thoughts,
    reasoning: thoughts,
  };
}

// Anthropic : input_tokens exclut déjà les lectures et écritures de cache.
function fromAnthropic(u) {
  let cacheWrite = n(u.cache_creation_input_tokens);
  if (!cacheWrite && u.cache_creation) {
    cacheWrite = n(u.cache_creation.ephemeral_5m_input_tokens) + n(u.cache_creation.ephemeral_1h_input_tokens);
  }
  return {
    input: n(u.input_tokens),
    cacheRead: n(u.cache_read_input_tokens),
    cacheWrite,
    output: n(u.output_tokens),
    reasoning: 0,
  };
}

// OpenAI (Chat Completions et Responses) : le total d'entrée inclut les tokens en cache.
function fromOpenAI(u) {
  const promptTotal = u.prompt_tokens ?? u.input_tokens;
  const details = u.prompt_tokens_details || u.input_tokens_details || {};
  const outDetails = u.completion_tokens_details || u.output_tokens_details || {};
  const cached = n(details.cached_tokens);
  return {
    input: Math.max(0, n(promptTotal) - cached),
    cacheRead: cached,
    cacheWrite: 0,
    output: n(u.completion_tokens ?? u.output_tokens),
    reasoning: n(outDetails.reasoning_tokens),
  };
}

/**
 * Normalise un objet `usage` quel que soit son fournisseur.
 * `provider` lève l'ambiguïté entre Anthropic et l'API Responses d'OpenAI, qui
 * utilisent toutes deux `input_tokens` avec un sens différent.
 */
function normalizeUsage(u, provider) {
  if (!u || typeof u !== 'object') return null;
  if ('promptTokenCount' in u || 'candidatesTokenCount' in u) return fromGemini(u);
  if ('prompt_tokens' in u || 'completion_tokens' in u) return fromOpenAI(u);
  if ('input_tokens' in u || 'output_tokens' in u) {
    const looksAnthropic =
      provider === 'anthropic' ||
      'cache_read_input_tokens' in u ||
      'cache_creation_input_tokens' in u ||
      (provider !== 'openai' && !('input_tokens_details' in u) && !('total_tokens' in u));
    return looksAnthropic ? fromAnthropic(u) : fromOpenAI(u);
  }
  return null;
}

/**
 * Lit au fil de l'eau une réponse (JSON classique, flux SSE ou tableau JSON en flux
 * comme `streamGenerateContent` de Gemini) et en retient le modèle et l'usage final.
 * Ne garde en mémoire que le strict nécessaire pour les flux SSE.
 */
class UsageSniffer {
  constructor({ provider, contentType = '', maxBuffer = 16 * 1024 * 1024 } = {}) {
    this.provider = provider;
    this.sse = /event-stream/i.test(contentType);
    this.maxBuffer = maxBuffer;
    this.buffer = '';
    this.overflow = false;
    this.model = null;
    this.usage = null;
    this.anthropicStart = null;
    this.decoder = new TextDecoder();
  }

  feed(chunk) {
    const text = typeof chunk === 'string' ? chunk : this.decoder.decode(chunk, { stream: true });
    if (this.sse) {
      this.buffer += text;
      let idx;
      while ((idx = this.buffer.indexOf('\n')) >= 0) {
        const line = this.buffer.slice(0, idx).replace(/\r$/, '');
        this.buffer = this.buffer.slice(idx + 1);
        this.#sseLine(line);
      }
      if (this.buffer.length > this.maxBuffer) this.buffer = '';
      return;
    }
    if (this.overflow) return;
    this.buffer += text;
    if (this.buffer.length > this.maxBuffer) {
      this.overflow = true;
      this.buffer = '';
    }
  }

  end() {
    if (this.sse) {
      if (this.buffer) this.#sseLine(this.buffer);
    } else if (this.buffer) {
      try {
        const parsed = JSON.parse(this.buffer);
        for (const obj of Array.isArray(parsed) ? parsed : [parsed]) this.absorb(obj);
      } catch {
        // Réponse non JSON (erreur HTML, binaire…) : rien à compter.
      }
    }
    this.buffer = '';
    return { model: this.model, usage: this.usage && !isEmpty(this.usage) ? this.usage : null };
  }

  #sseLine(line) {
    if (!line.startsWith('data:')) return;
    const data = line.slice(5).trim();
    if (!data || data === '[DONE]') return;
    try {
      this.absorb(JSON.parse(data));
    } catch {
      // Ligne SSE non JSON : ignorée.
    }
  }

  absorb(obj) {
    if (!obj || typeof obj !== 'object') return;
    const model = obj.model || obj.modelVersion || obj.message?.model || obj.response?.model;
    if (model && typeof model === 'string') this.model = model;

    // Flux Anthropic : l'entrée arrive dans message_start, la sortie (cumulée) dans message_delta.
    if (obj.type === 'message_start' && obj.message?.usage) {
      this.anthropicStart = fromAnthropic(obj.message.usage);
      this.usage = { ...this.anthropicStart };
      return;
    }
    if (obj.type === 'message_delta' && obj.usage) {
      const delta = fromAnthropic(obj.usage);
      const base = this.usage || emptyUsage();
      this.usage = {
        input: delta.input || base.input,
        cacheRead: delta.cacheRead || base.cacheRead,
        cacheWrite: delta.cacheWrite || base.cacheWrite,
        output: delta.output || base.output,
        reasoning: 0,
      };
      return;
    }
    // API Responses d'OpenAI : l'usage est dans l'évènement final.
    if (obj.response && obj.response.usage && /^response\.(completed|done|incomplete)$/.test(obj.type || '')) {
      this.usage = normalizeUsage(obj.response.usage, 'openai');
      return;
    }
    if (obj.usageMetadata) {
      this.usage = fromGemini(obj.usageMetadata);
      return;
    }
    if (obj.usage) {
      const u = normalizeUsage(obj.usage, this.provider);
      if (u && !isEmpty(u)) this.usage = u;
    }
  }
}

module.exports = { emptyUsage, addUsage, totalTokens, isEmpty, normalizeUsage, UsageSniffer };
