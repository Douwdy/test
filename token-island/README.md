# Token Island

Une encoche façon *Dynamic Island* en haut de l'écran Windows, qui compte les tokens consommés chez
Claude, GPT, Gemini, Grok, et tout fournisseur compatible avec l'API OpenAI (OpenRouter, Mistral, DeepSeek, Groq…).

- **Vue compacte** : tokens et coût du jour, débit en tokens/min, fine barre de budget. Le point lumineux pulse
  (aux couleurs du fournisseur) pendant qu'une requête passe par le proxy.
- **Activité en direct** : à chaque requête terminée, l'encoche s'élargit quelques secondes
  (« Claude · opus-4-8 · +12 k tok · 0,04 $ »).
- **Vue dépliée** (survol, clic pour la garder ouverte, ou `Ctrl+Alt+T`) : total du jour (entrée, sortie, cache),
  anneau du budget mensuel, histogramme des 24 dernières heures, répartition par fournisseur et par modèle,
  bloc de 5 h de Claude Code avec l'heure de remise à zéro, limites 5 h / semaine du forfait Codex, projection de fin de mois.
- **Alertes** Windows à 50, 80 et 100 % du budget (seuils réglables), budget en dollars et/ou en tokens par jour.
- **Export CSV**, démarrage avec Windows, choix de l'écran, euros avec ton propre taux de change.

## D'où viennent les chiffres

Aucun fournisseur ne donne un compteur commun : l'app combine quatre sources.

| Source | Ce qu'elle voit | À faire |
| --- | --- | --- |
| **Proxy local** (`127.0.0.1:4141`) | Toute requête API qui passe par lui, chez n'importe quel fournisseur, en temps réel | Changer l'adresse de base de l'API dans tes outils (voir plus bas) |
| **Journaux de Claude Code** | Toutes tes sessions Claude Code, abonnement Pro/Max compris | Rien : `%USERPROFILE%\.claude\projects` est lu automatiquement |
| **Journaux de Codex CLI** | Tes sessions Codex et l'état des limites de ton forfait ChatGPT | Rien : `%USERPROFILE%\.codex\sessions` est lu automatiquement |
| **API d'administration** OpenAI / Anthropic | Toute la consommation de l'organisation, quel que soit l'outil (Cursor, serveurs…), avec quelques minutes de retard | Coller une clé *admin* dans les réglages |

Ce qui **ne peut pas** être compté : les discussions sur les sites et apps grand public (claude.ai, chatgpt.com,
gemini.google.com, grok.com), qui ne passent pas par une API et n'exposent aucun nombre de tokens.

### Brancher un outil sur le proxy

Le proxy transmet chaque requête telle quelle à l'API officielle et lit le champ `usage` de la réponse
(JSON ou flux SSE). Tes clés restent dans tes outils : l'app ne les lit ni ne les enregistre.

| Fournisseur | Adresse de base à utiliser |
| --- | --- |
| OpenAI | `http://127.0.0.1:4141/openai/v1` |
| Anthropic | `http://127.0.0.1:4141/anthropic` |
| Gemini | `http://127.0.0.1:4141/gemini` (API native) ou `…/gemini/v1beta/openai` (compatible OpenAI) |
| xAI (Grok) | `http://127.0.0.1:4141/xai/v1` |
| OpenRouter, Mistral, DeepSeek, Groq | `http://127.0.0.1:4141/<nom>/v1` |

Pour la plupart des SDK, une variable d'environnement suffit (PowerShell) :

```powershell
[Environment]::SetEnvironmentVariable('OPENAI_BASE_URL', 'http://127.0.0.1:4141/openai/v1', 'User')
[Environment]::SetEnvironmentVariable('ANTHROPIC_BASE_URL', 'http://127.0.0.1:4141/anthropic', 'User')
```

```python
import os
from openai import OpenAI
grok = OpenAI(base_url="http://127.0.0.1:4141/xai/v1", api_key=os.environ["XAI_API_KEY"])
```

Ajoute l'en-tête `X-Token-Island-App: NomDuScript` pour voir le nom de l'outil dans l'encoche.
Claude Code et Codex sont reconnus à leur User-Agent : s'ils passent par le proxy alors que leurs journaux sont
déjà lus, leurs requêtes ne sont pas comptées une seconde fois. De même, quand une API d'administration est active,
le trafic du même fournisseur vu par le proxy est exclu des totaux.

Pour les flux OpenAI, Grok et DeepSeek, le proxy ajoute `stream_options.include_usage` (sans quoi l'API ne renvoie
pas le nombre de tokens). Désactivable dans les réglages.

### Ajouter des données à la main ou depuis un script

```powershell
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:4141/ingest -ContentType 'application/json' `
  -Body '{"provider":"gemini","model":"gemini-2.5-pro","input":1200,"output":300,"app":"Mon script"}'
```

## Coûts

Le coût est calculé avec une table de tarifs (dollars par million de tokens) dans `src/core/pricing.js`.
Les tarifs Claude ont été relevés en octobre 2026 ; ceux des autres fournisseurs sont plus anciens et **indicatifs**.
Un modèle sans tarif connu est compté en tokens mais pas en dollars. Corrige ou complète les tarifs dans les réglages,
section « Tarifs personnalisés » :

```json
[{ "match": "gpt-6", "input": 2, "output": 12, "cacheRead": 0.2 }]
```

Pour Claude Code et Codex utilisés avec un abonnement, le coût affiché n'est qu'un équivalent API et n'entre pas
dans le budget (case à cocher dans les réglages).

## Installer et lancer

Il faut [Node.js](https://nodejs.org) 20 ou plus.

```powershell
cd token-island
npm install
npm start          # lance l'app
npm test           # tests (Node seul, sans Electron)
npm run dist       # construit l'installateur et la version portable dans dist/
```

Au premier lancement, la fenêtre de réglages s'ouvre. Ensuite, l'app vit dans la zone de notification
(clic sur l'icône : afficher/masquer l'encoche, réglages, export CSV, quitter).

Les données sont dans `%APPDATA%\Token Island\` : `usage.jsonl` (une ligne par requête, conservée 400 jours),
`config.json`, et `secrets.json` (clés d'administration chiffrées avec ton compte Windows via DPAPI).

## Organisation du code

```
src/
  core/      lecture des usages, tarifs, journal, agrégats, proxy (Node pur, testé)
  sources/   journaux Claude Code et Codex, API d'administration
  main/      processus Electron : fenêtres, icône, raccourci, alertes
  renderer/  l'encoche et la fenêtre de réglages (HTML/CSS/JS sans framework)
test/        tests node:test, dont le proxy de bout en bout avec un faux fournisseur
```

## Idées pour la suite

Classées de la plus utile à la plus gadget, selon moi :

1. **Extension de navigateur** pour claude.ai, ChatGPT, Gemini et Grok : estimer les tokens à partir du texte
   envoyé et reçu (≈ 4 caractères par token) et les envoyer à `/ingest`. Seul moyen de couvrir les abonnements web.
2. **Gemini CLI** : lire ses journaux locaux, comme pour Claude Code et Codex.
3. **Coût par projet** : regrouper par dossier de travail (déjà connu pour Claude Code et Codex) ou par en-tête
   `X-Token-Island-App`, avec un budget par projet.
4. **Plafond dur** : le proxy refuse les requêtes (HTTP 429) une fois le budget du jour dépassé, avec un bouton
   « débloquer pour 1 h » dans l'encoche.
5. **Taux de cache** : part des tokens d'entrée servis depuis le cache, et économie réalisée. Utile pour repérer
   un prompt qui casse le cache.
6. **Masquage automatique** quand une application est en plein écran (jeu, vidéo, présentation).
7. **Historique** : graphique sur 30 jours et comparaison avec le mois précédent dans la fenêtre de réglages.
8. **Tarifs à jour** : récupérer automatiquement la liste des prix (par exemple celle d'OpenRouter) au lieu de la
   table intégrée.
9. **Widget de latence** : temps jusqu'au premier token et débit de sortie par modèle, mesurés par le proxy.
10. **Synchronisation entre plusieurs PC** via un dossier partagé (OneDrive) pour le fichier `usage.jsonl`.
