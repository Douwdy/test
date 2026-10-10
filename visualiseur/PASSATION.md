# Passation : Visualiseur musical

Ce document s'adresse à la personne qui reprend le projet. Il dit ce qu'est le projet, comment il est
construit, comment le lancer, le tester et le livrer, ce qui a été vérifié, et ce qui reste fragile ou à
faire. Le mode d'emploi pour l'utilisateur final est dans `LISEZMOI.md`.

## 1. En bref

Le programme pour Windows prend un fichier audio de n'importe quel format lu par ffmpeg et produit une
vidéo MP4 « visualiseur ». Le fond est généré par un shader GLSL animé par la musique. Au premier plan,
on trouve la pochette qui bat sur les kicks, un spectre (barres, cercle ou onde), des particules, le
titre, l'artiste et l'album, et une barre de progression. La vidéo ne contient pas de paroles.

- Les tags (titre, artiste, album, année) et la pochette sont lus dans le fichier. À défaut, le titre
  vient du nom de fichier et la pochette de `cover.jpg` ou `folder.jpg` du dossier, ou bien elle est
  générée. Tout reste modifiable dans l'interface.
- Il y a une interface graphique (tkinter) et une ligne de commande.
- Le `.exe` est construit par GitHub Actions avec PyInstaller, et ffmpeg est fourni à côté.

**État :** le programme est fonctionnel, il passe sa CI Windows et a été testé sous Linux. Il n'a pas encore
été essayé par une personne sur un vrai PC Windows avec carte graphique : c'est la première chose à faire
(voir § 8).

## 2. Où est le code

| Élément | Emplacement |
| --- | --- |
| Dépôt | `Douwdy/test`, branche `ccr-67ecddbb-y4dl9c`, sans pull request ouverte |
| Code | `visualiseur/` |
| CI | `.github/workflows/visualiseur.yml` (à la racine du dépôt) |

Le dépôt contient aussi le site Rhynote (`public/`, `tools/`, `content/`…), **sans rapport** avec le
visualiseur. Le visualiseur a été rangé dans son propre dossier pour ne pas s'y mêler. Le déplacer dans
un dépôt dédié serait plus propre (voir § 9).

## 3. Les fichiers

| Fichier | Lignes | Rôle |
| --- | ---: | --- |
| `moteur.py` | ~890 | Le cœur : recherche de ffmpeg, métadonnées, décodage et analyse audio, couleurs, polices, rendu d'une image (`Rendu`), rendu parallèle et encodage (`generer`). |
| `shaders.py` | ~250 | Les 6 fonds GLSL (`SOURCES`), leurs noms affichés (`FONDS`), l'en-tête commun (bruit, fbm, palette, spectre) et `FondShader` (contexte moderngl hors écran, framebuffer, relecture RGB). |
| `visualiseur.py` | ~480 | Point d'entrée : interface tkinter (`lancer_interface`), ligne de commande (`ligne_de_commande`), `main()` avec `freeze_support()`. |
| `requirements.txt` | | numpy, pillow ≥ 10.1, moderngl. |
| `construire.bat` | | Construction locale de l'exe avec PyInstaller. Fichier en fins de ligne CRLF. |
| `LISEZMOI.md` | | Mode d'emploi pour l'utilisateur. |
| `.github/workflows/visualiseur.yml` | | CI : tests sous Windows, construction de l'exe, artefact téléchargeable. |

## 4. Comment ça marche

```
fichier audio ─ffprobe─▶ tags + durée          ─┐
              ─ffmpeg──▶ pochette (PNG)          ├─▶ Options (titre, pochette, couleurs, style, fond…)
              ─ffmpeg──▶ PCM mono 22 050 Hz ─numpy─▶ Analyse (par image vidéo)
                                                  │
         processus de rendu (spawn, cœurs − 1) ◀───┘   chacun construit son Rendu + son contexte OpenGL
           pour chaque image i :
             shader GLSL → fond RGB (relu du GPU)
             Pillow : particules, spectre + halo, ombre + pochette, texte, barre de progression
         ─▶ octets RGB24 ─tube stdin─▶ ffmpeg : libx264 yuv420p + piste audio d'origine en AAC 320k ─▶ .mp4
```

### Analyse audio (`moteur.analyser`)

Elle est calculée une fois pour toutes, avec une valeur par image de la vidéo (fps × durée) :

- **`bandes`** (n × 40 à 64) : FFT sur 2048 points (fenêtre de Hann) centrée sur l'image, regroupée en
  bandes logarithmiques de 35 Hz à 11 kHz, en dB. Chaque bande est normalisée par son 99ᵉ centile sur
  42 dB de dynamique, avec un plancher pour ne pas gonfler les bandes muettes. Un suiveur d'enveloppe
  (`_lisser`) monte vite et redescend lentement, comme un VU-mètre.
- **`basse`** : l'énergie sous 150 Hz, lissée.
- **`impulsion`** : les attaques. C'est le flux spectral positif sous 250 Hz, avec un seuil adaptatif
  (moyenne + 1,2 écart-type sur 0,5 s), des pics espacés d'au moins 120 ms et une décroissance
  exponentielle de 180 ms.
- **`pulse`** = 0,6 × impulsion + 0,5 × basse, borné. C'est le signal qui fait « battre » l'image :
  zoom de la pochette, luminosité, particules.
- **`energie`** : le volume global lissé. **`onde`** : 256 points de forme d'onde par image, pour le
  style « onde ».

Tout est normalisé entre 0 et 1 **par rapport au morceau lui-même**. Un morceau calme bouge donc autant
qu'un morceau fort : c'est voulu.

### Rendu d'une image (`moteur.Rendu.image`)

Chaque image est calculée **seule** à partir de son indice, sans état d'une image à l'autre. C'est ce
qui permet le rendu parallèle sans ordre imposé. Les particules ont donc des trajectoires analytiques
(angle, vitesse, durée de vie, phase tirés au sort avec une graine fixe), accélérées par l'intégrale du
`pulse` (`self.cumul`).

**Optimisations, mesurées :**
- Le halo lumineux est calculé à résolution réduite et seulement dans la zone du spectre (`self.zone`).
  Le flou et l'agrandissement plein écran coûtaient 70 % du temps.
- Les pochettes redimensionnées sont mises en cache par taille, et les fonds « pochette floutée » par
  niveau de luminosité.
- Les particules sont dessinées directement en pleine résolution : leur couleur est le fond lu sous le
  point, plus la lumière, ce qui imite un mélange additif sans calque alpha.

### Fonds shaders (`shaders.py`)

Chaque fond est une fonction GLSL `vec3 fond(vec2 p)`. `p` est centré, avec y vers le haut et une hauteur
d'écran de 1. Elle reçoit :

| Uniforme | Contenu |
| --- | --- |
| `uTime` | temps en secondes |
| `uPhase` | « temps musical » : intégrale de 0,35 + 0,9 × énergie + 0,8 × pulse, qui avance plus vite quand ça cogne |
| `uBass`, `uPulse`, `uBeat`, `uEnergy` | signaux de l'analyse |
| `uBands[16]` | spectre réduit à 16 bandes, lu en continu avec `bande(x)` |
| `uC1`, `uC2`, `uC3` | palette tirée de la pochette par `moteur.palette` |

L'en-tête commun ajoute ensuite une vignette, une compression douce des hautes lumières et un facteur
0,85, pour que le premier plan reste lisible.

**Astuce d'orientation :** `gl_FragCoord` est utilisé comme coordonnée image, y vers le bas. La relecture
du framebuffer donne alors les lignes dans l'ordre attendu par Pillow, sans retournement. `P()` refait un
repère y vers le haut pour écrire les shaders naturellement.

**Ajouter un fond :** une entrée dans `SOURCES`, une dans `FONDS`, et c'est tout. L'interface et la ligne
de commande les listent automatiquement.

## 5. Décisions et pièges

1. **OpenGL ne tourne jamais dans le processus de l'interface.** Créer un contexte GLX dans un fil
   secondaire du processus tkinter faisait planter Xlib (`BadWindow`). Sous Windows, WGL et les threads
   posent des problèmes du même genre. L'aperçu et la vérification d'OpenGL passent donc par
   `_dans_un_processus` (pool spawn d'un processus), et le rendu par le pool de rendu.
2. **OpenGL est vérifié avant de lancer le pool** (`verifier_opengl`). Une exception dans l'initialiseur
   d'un `multiprocessing.Pool` fait redémarrer les processus en boucle au lieu de remonter l'erreur.
3. **`multiprocessing.freeze_support()`** doit rester en tête de `main()`, sinon l'exe PyInstaller se
   relance indéfiniment sous Windows. Tout script qui importe le moteur et génère doit avoir la garde
   `if __name__ == "__main__":`.
4. **Aucune méthode de la classe tkinter ne doit s'appeler `_options`** : ce nom masquerait une méthode
   interne de tkinter et casserait les boîtes de dialogue. C'est arrivé, d'où `_lire_options`.
5. **Pillow ≤ 10.2** plante (« y1 must be greater than or equal to y0 ») sur un `rounded_rectangle` moins
   haut que 2 × rayon + 2. Tous les rectangles arrondis passent par `_rect_arrondi`, qui borne le rayon.
6. **`fwidth()` et les autres dérivées GLSL** doivent être appelées hors de toute branche (`if`) : leur
   résultat y est indéfini sur certains GPU. Voir le shader synthwave.
7. **Dimensions paires** : imposées dans `Options.__post_init__`, parce que yuv420p l'exige.
8. **Exe sans console** (`--windowed`) : `sys.stdout` vaut `None`. Toute écriture passe par `_afficher`
   ou un test `if sys.stdout`. ffmpeg est lancé avec `CREATE_NO_WINDOW` pour ne pas ouvrir de console.
9. **Recherche de ffmpeg** (`trouver_outil`), dans l'ordre : variable `FFMPEG_PATH` ou `FFPROBE_PATH`,
   puis à côté de l'exe ou du script, ou dans `ffmpeg\` ou `ffmpeg\bin\`, puis le PATH.
10. **Synchronisation** : l'image i correspond au temps (i + 0,5) / fps de l'audio. L'audio de la vidéo
    est le fichier d'origine, découpé avec le même `-ss` / `-t` que l'analyse, et `-shortest` aligne les
    durées.

## 6. Lancer, tester, livrer

### Développement

```bat
pip install -r requirements.txt
python visualiseur.py                      :: interface
python visualiseur.py morceau.mp3          :: interface avec ce fichier
python visualiseur.py morceau.mp3 -o v.mp4 --fond tunnel --style cercle --debut 30 --duree 10 --qualite rapide
python visualiseur.py --help
```

Il faut ffmpeg et ffprobe accessibles (voir § 5, point 9).

**Sous Linux sans écran**, il faut Xvfb pour OpenGL :
`xvfb-run -a python3 visualiseur.py …`. Le rendu est alors fait par llvmpipe, sur le processeur.

### Tests

Il n'y a **pas de tests unitaires**. Les vérifications faites pendant le développement :

- **Synchronisation** : sur un son synthétique avec un kick toutes les 0,5 s, on mesure la luminance
  moyenne de chaque image de la vidéo et on repère les pics. Ils doivent tomber toutes les 15 images à
  30 i/s, ce qui était le cas, à une image près avec les shaders. Commande ffmpeg utile :
  `ffmpeg -i v.mp4 -vf "signalstats,metadata=print:key=lavfi.signalstats.YAVG:file=y.txt" -f null -`
- **Cas limites de rendu** : silence, spectre aléatoire, pochette absente, titre très long (tronqué avec
  « … »), texte japonais, formats 640×360 à 3840×2160, carré et vertical, les 3 styles × 7 fonds.
- **Interface** : test automatisé sous Xvfb (chargement, aperçu, génération) en pilotant la fenêtre avec
  `after()`.
- **CI Windows** (`visualiseur.yml`) :
  - rendu de 2 s par le script, avec shader et en parallèle, puis avec le fond pochette ;
  - construction de l'exe ;
  - rendu de 2 s **par l'exe lui-même** avec le fond synthwave.

  Les machines GitHub n'ont pas de GPU : les tests utilisent le rendu OpenGL logiciel de Mesa
  (`pal1000/mesa-dist-win`). Ses DLL sont copiées à côté de `python.exe` et de l'exe de test, **pas**
  dans le paquet livré, sinon elles remplaceraient le pilote de la carte graphique de l'utilisateur.

### Livraison

- **CI :** chaque push qui touche `visualiseur/` ou le workflow lance la construction, qu'on peut aussi
  lancer avec « Run workflow ». L'artefact **Visualiseur-windows** (environ 160 Mo) contient
  `Visualiseur.exe`, `ffmpeg.exe`, `ffprobe.exe` et `LISEZMOI.md`. Il est conservé 90 jours.
- **ffmpeg :** il vient des builds « latest win64 gpl » de BtbN. C'est la licence **GPL** : pour une
  diffusion publique, il faut la mentionner et indiquer où trouver les sources de ffmpeg.
- **Construction locale :** `construire.bat`, qui demande Python 3.10 ou plus.

## 7. Performances

Les mesures ont été faites sur un conteneur Linux de 4 cœurs, sans GPU :

| Cas | Coût |
| --- | --- |
| Une image 1080p, fond pochette | ~65-70 ms |
| Une image 1080p, carré 1080 | ~32 ms |
| Une image 4K | ~330 ms |
| Un shader 960×540 rendu sur le processeur (llvmpipe) | 15 à 35 ms |
| 10 s de vidéo 1080p de bout en bout (3 processus) | ~22 s |

Sur un vrai GPU, le shader devrait être négligeable, mais ce n'est **pas mesuré**. Le goulot reste le
dessin Pillow sur le processeur, et la relecture GPU → mémoire d'environ 6 Mo par image en 1080p.

Avec 3 processus de rendu sur 4 cœurs, ça donne environ 2 minutes de calcul par minute de vidéo 1080p à
30 i/s.

## 8. Ce qui n'a pas été vérifié, limites connues

- **Aucun essai sur un vrai PC Windows avec carte graphique.** C'est la priorité. Il faut vérifier :
  - le lancement de l'exe (SmartScreen : « Informations complémentaires → Exécuter quand même », car
    l'exe n'est pas signé) ;
  - l'aperçu, et une génération complète de chaque fond ;
  - la vitesse sur GPU ;
  - le rendu sur des GPU Intel, AMD et NVIDIA (différences de précision possibles dans les shaders).
- **Texte japonais, chinois, coréen :** le programme cherche Yu Gothic, MS Gothic, Microsoft YaHei puis
  Malgun Gothic. Le choix n'a pas été vu sous Windows, car le conteneur de test n'avait pas ces polices.
- **Lisibilité :** sur certains fonds clairs (soleil du synthwave, nébuleuse lumineuse), le titre n'a pour
  contraste que son ombre portée.
- **Mémoire :** l'analyse (dont 256 points de forme d'onde par image) est copiée dans chaque processus de
  rendu. Pour 1 h à 60 i/s, cela fait environ 110 Mo par processus. Ce n'est pas un problème pour un
  morceau normal, mais à surveiller pour des mixes très longs.
- **Champ « Album » :** l'interface y ajoute « (année) ». Ce texte part aussi dans le tag `album` du MP4.
- **Exe :** en mode ligne de commande, il ne montre pas la progression, puisqu'il n'a pas de console.
- **Glisser-déposer :** il ne marche que sur l'icône de l'exe, pas dans la fenêtre (tkinter ne le gère
  pas sans module supplémentaire).
- **Taille :** l'exe fait environ 31 Mo et le paquet complet environ 160 Mo à cause de ffmpeg.

## 9. Pistes pour la suite

Elles sont classées de la plus utile à la moins urgente, selon mon estimation :

1. **Tester sur un vrai PC** (voir § 8) et ajuster les intensités des shaders sur de vraies musiques. Les
   réglages ont été faits sur des sons synthétiques et deux pochettes de test.
2. **Lisibilité du texte :** ajouter un léger voile sombre derrière le bloc de texte, ou un réglage de la
   luminosité du fond.
3. **Choisir le moment de l'aperçu** (curseur de temps), et proposer un aperçu vidéo de quelques secondes.
4. **Composer le premier plan sur le GPU** (pochette, barres, halo en shaders) : le rendu deviendrait
   largement plus rapide que le temps réel. C'est un chantier conséquent, qui touche tout `Rendu`.
5. **Encodage matériel** (`h264_nvenc`, `h264_qsv`, `h264_amf`) en option, avec repli sur libx264.
6. **Préréglages enregistrés**, comme le dernier style, fond ou format utilisés.
7. **Signer l'exe** pour éviter l'avertissement SmartScreen (certificat de signature de code payant).
8. **Sortir le visualiseur dans un dépôt dédié**, avec ses propres versions et releases GitHub au lieu
   d'artefacts qui expirent.
9. **Ajouter des tests automatisés** : analyse (pics attendus sur un signal synthétique), métadonnées
   (fichiers avec et sans tags), rendu (pas d'exception sur la matrice formats × styles × fonds).
