# rhynote-site

Site public de Rhynote, servi sur **https://rhynote.yeeterie.org/**.

| URL | Contenu |
| --- | --- |
| `/` | Accueil en français |
| `/en` | Accueil en anglais |
| `/confidentialite` | Politique de confidentialité, français puis anglais : **c'est l'URL à déclarer dans la Play Console** |
| `/en/privacy` | Politique de confidentialité, anglais seul (lien depuis l'accueil anglais) |
| `/privacy`, `/politique-de-confidentialite`, `/en/confidentialite` | Redirections (`public/_redirects`), avec ou sans `/` final |

Site 100 % statique, sans JavaScript, sans police externe ni traceur : tout est dans `public/`.
La CSP (`public/_headers`) n'autorise que des ressources du site lui-même (`style-src 'self'`, `img-src 'self'`),
donc pas de style en ligne (`style="…"`), pas de script, pas d'image `data:`.
Il est hébergé comme Worker d'assets Cloudflare (compte de niivo.fr), avec le domaine personnalisé
`rhynote.yeeterie.org` déclaré dans `wrangler.jsonc` (Cloudflare gère le DNS et le certificat).

## Modifier le site

Les pages HTML sont **générées** : ne pas modifier `public/*.html` à la main.

| Pour changer… | Modifier | Puis lancer |
| --- | --- | --- |
| Les textes des accueils FR/EN, le lien Google Play, l'e-mail | `tools/build.py` (dictionnaires `T` et `L`, constantes en tête) | `python3 tools/build.py` |
| La politique de confidentialité | `content/confidentialite-fr.html` et `content/confidentialite-en.html` | `python3 tools/build.py` |
| La mise en page | `public/style.css` (fichier source, non généré) | rien |
| Les images de partage (`og.png`, `og-en.png`) et `apple-touch-icon.png` | `tools/og.cjs` | `npx -y -p playwright@1 node tools/og.cjs` |

`tools/build.py` ne demande que Python 3, sans dépendance. Il produit `public/index.html`, `public/en/index.html`,
`public/confidentialite.html`, `public/en/privacy.html`, `public/404.html` et `public/wave.svg`.

Quand la politique change : mettre à jour la date « Dernière mise à jour / Last updated » dans les deux fichiers
de `content/`, et reporter le texte dans `docs/politique-de-confidentialite.md` du projet Rhynote.
Le texte de la politique est recopié tel quel (les espaces insécables ne sont ajoutées qu'aux pages d'accueil).

## À renseigner

- **Lien Google Play** : les boutons pointent pour l'instant vers une recherche Play Store. Remplacer la
  constante `PLAY` de `tools/build.py` par l'URL exacte de la fiche (`https://play.google.com/store/apps/details?id=…`).
- **Captures d'écran** : le téléphone du bandeau d'accueil est une illustration en HTML/CSS (bloc `.phone`).
  Pour afficher de vraies captures, remplacer son contenu dans `tools/build.py` par une `<img src="/img/…">`
  (fichiers dans `public/img/`).

## Déployer

```bash
npm install        # la première fois
npx wrangler deploy
```

Wrangler doit être connecté au compte Cloudflare (`npx wrangler whoami`, sinon `npx wrangler login`).

Aperçu local : `npx wrangler dev`, puis http://localhost:8787.
