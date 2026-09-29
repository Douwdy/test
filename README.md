# rhynote-site

Site public de Rhynote, servi sur **https://rhynote.yeeterie.org/**.

| URL | Contenu |
| --- | --- |
| `/` | Accueil en français |
| `/en` | Accueil en anglais |
| `/confidentialite` | Politique de confidentialité (français puis anglais) : **c'est l'URL à déclarer dans la Play Console** |
| `/privacy`, `/politique-de-confidentialite` | Redirigent vers `/confidentialite` (`public/_redirects`) |

Site 100 % statique, sans JavaScript, sans police externe ni traceur : tout est dans `public/`.
La CSP (`public/_headers`) n'autorise que des ressources du site lui-même (`style-src 'self'`, `img-src 'self'`),
donc pas de style en ligne (`style="…"`), pas de script, pas d'image `data:`.
Il est hébergé comme Worker d'assets Cloudflare (compte de niivo.fr), avec le domaine personnalisé
`rhynote.yeeterie.org` déclaré dans `wrangler.jsonc` (Cloudflare gère le DNS et le certificat).

## Fichiers

- `public/index.html`, `public/en/index.html` : pages d'accueil. Elles ont la même structure : toute modification
  se fait dans les deux langues.
- `public/confidentialite.html` : politique de confidentialité. Après toute modification, changer la date
  « Dernière mise à jour / Last updated » et reporter le texte dans `docs/politique-de-confidentialite.md`
  du projet Rhynote.
- `public/style.css` : feuille de style commune (thème clair/sombre automatique).
- `public/og.png` (aperçu de partage 1200×630), `public/apple-touch-icon.png`, `public/icon.svg` : images.
- `public/404.html`, `public/sitemap.xml`, `public/robots.txt`.

## À renseigner

- **Lien Google Play** : les boutons de téléchargement pointent pour l'instant vers une recherche Play Store
  (`https://play.google.com/store/search?q=Rhynote&c=apps`). Le remplacer par l'URL exacte de la fiche
  (`https://play.google.com/store/apps/details?id=…`) dans `public/index.html` et `public/en/index.html`.
- **Captures d'écran** : le téléphone du bandeau d'accueil est une illustration en HTML/CSS (bloc `.phone`).
  Pour afficher de vraies captures, remplacer son contenu par une `<img src="/img/…">` (fichiers dans `public/img/`).

## Modifier et redéployer

```bash
npm install        # la première fois
npx wrangler deploy
```

Wrangler doit être connecté au compte Cloudflare (`npx wrangler whoami`, sinon `npx wrangler login`).

Aperçu local : `npx wrangler dev`, puis http://localhost:8787.
