'use strict';

const os = require('node:os');
const path = require('node:path');
const fs = require('node:fs');
const { createJsonlTailer } = require('./jsonl-tailer');
const { normalizeUsage } = require('../core/usage');

// Claude Code écrit chaque session dans <config>/projects/<projet>/<session>.jsonl, avec
// l'objet `usage` de l'API pour chaque réponse. Une même réponse peut apparaître sur
// plusieurs lignes (une par bloc de contenu) : on la compte une fois, via message.id + requestId.

function defaultRoots(env = process.env, home = os.homedir()) {
  const roots = [];
  if (env.CLAUDE_CONFIG_DIR) {
    for (const dir of env.CLAUDE_CONFIG_DIR.split(/[,;]/)) if (dir.trim()) roots.push(path.join(dir.trim(), 'projects'));
  }
  roots.push(path.join(home, '.claude', 'projects'), path.join(home, '.config', 'claude', 'projects'));
  return [...new Set(roots)];
}

function parseClaudeCodeLine(obj, { subscription }) {
  if (obj.type !== 'assistant' || !obj.message?.usage) return null;
  const msg = obj.message;
  if (!msg.model || msg.model === '<synthetic>') return null;
  const usage = normalizeUsage(msg.usage, 'anthropic');
  if (!usage) return null;
  const ts = Date.parse(obj.timestamp) || Date.now();
  return {
    id: `cc:${msg.id || obj.uuid}:${obj.requestId || ''}`,
    ts,
    provider: 'anthropic',
    model: msg.model,
    source: 'claude-code',
    app: obj.cwd ? `Claude Code · ${path.basename(obj.cwd)}` : 'Claude Code',
    billing: subscription ? 'subscription' : 'api',
    ...usage,
  };
}

function createClaudeCodeSource({ getConfig, state }) {
  const tailer = createJsonlTailer({
    roots: () => {
      const extra = getConfig().sources.claudeCode.paths || [];
      return [...defaultRoots(), ...extra].filter((p) => fs.existsSync(p));
    },
    accept: (f) => f.endsWith('.jsonl'),
    prefilter: '"usage"',
    state,
    parseLine: (obj) => {
      const ev = parseClaudeCodeLine(obj, { subscription: getConfig().sources.claudeCode.subscription });
      return ev ? [ev] : null;
    },
  });
  return { name: 'claude-code', scan: (onEvent) => tailer.scan(onEvent) };
}

module.exports = { createClaudeCodeSource, parseClaudeCodeLine, defaultRoots };
