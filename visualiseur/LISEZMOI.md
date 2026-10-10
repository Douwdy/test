# Visualiseur musical

Transforme un morceau (MP3, FLAC, WAV, OGG, Opus, M4A, WMA… tout ce que lit ffmpeg) en vidéo MP4
animée au rythme de la musique, sans paroles : la pochette bat sur les kicks, le spectre suit les
fréquences, des particules accélèrent sur les temps forts.

Titre, artiste, album et pochette sont lus dans le fichier. S'il n'y a pas de tag, le titre vient du nom
du fichier (`Artiste - Titre.mp3` est découpé). S'il n'y a pas de pochette intégrée, une image
`cover.jpg`, `folder.jpg` ou `front.jpg` du même dossier est utilisée. Tout reste modifiable à la main.
Sans aucune pochette, une image est générée avec l'initiale du titre.

## Utilisation

1. Lancer `Visualiseur.exe`. On peut aussi glisser un fichier audio sur l'icône du programme.
2. Choisir le fichier audio, puis vérifier ou compléter le titre, l'artiste, l'album et la pochette.
3. Choisir le format, le style, la couleur (par défaut, elle est tirée de la pochette) et la qualité.
4. Cliquer sur **Aperçu** pour voir une image, puis sur **Générer la vidéo**.

| Réglage | Choix |
| --- | --- |
| Format | 1080p, 720p, 1440p, 4K (16:9), carré 1080×1080, vertical 1080×1920 (Shorts, TikTok) |
| Style | **Barres** : spectre symétrique en bas · **Cercle** : spectre en couronne autour de la pochette ronde · **Onde** : oscilloscope |
| Images/s | 24, 30 ou 60 |
| Extrait | début et durée en secondes, pour une vidéo courte ou un test rapide (vide = morceau entier) |

Le rendu utilise tous les cœurs du processeur. Avec un processeur de 4 cœurs, 1 minute de vidéo 1080p à
30 i/s prend environ 2 minutes. En 4K, comptez 4 à 5 fois plus.

## Installation

### Avec l'exécutable

Visualiseur.exe a besoin de `ffmpeg.exe` et `ffprobe.exe` **dans le même dossier** que lui (ou dans un
sous-dossier `ffmpeg\`, ou dans le PATH). On les trouve dans l'archive « release essentials » de
https://www.gyan.dev/ffmpeg/builds/, dossier `bin\`.

L'exécutable est construit automatiquement par GitHub Actions à chaque modification du dossier
`visualiseur/` : onglet **Actions** → **Visualiseur (Windows)** → dernière exécution → artefact
**Visualiseur-windows**. L'archive contient déjà ffmpeg et ffprobe. On peut aussi relancer la construction
à la main avec **Run workflow**.

Pour le construire soi-même : installer Python 3.10 ou plus récent (cocher « Add to PATH »), puis
double-cliquer sur `construire.bat`. L'exe sort dans `dist\`.

### Depuis le code source

```bat
pip install -r requirements.txt
python visualiseur.py
```

## Ligne de commande

```bat
python visualiseur.py morceau.flac -o video.mp4 --style cercle --format 1080x1920
python visualiseur.py morceau.mp3 -o test.mp4 --debut 60 --duree 15 --qualite rapide
python visualiseur.py morceau.wav -o video.mp4 --titre "Mon titre" --artiste "Moi" --pochette image.jpg --couleur "#ff3366"
```

`python visualiseur.py --help` liste toutes les options. L'exe fonctionne de la même façon, mais sans
afficher la progression : il n'a pas de console.

## Fonctionnement

- `moteur.py` fait tout le travail. ffprobe lit les tags et ffmpeg extrait la pochette. ffmpeg décode
  l'audio en mono 22 kHz pour l'analyse, que fait numpy : spectre en bandes logarithmiques (35 Hz à
  11 kHz), énergie des basses, et détection des attaques (flux spectral des graves avec un seuil
  adaptatif). Pillow dessine les images, plusieurs processus en parallèle. Elles passent par un tube vers
  ffmpeg, qui les encode en H.264 avec la piste audio d'origine en AAC 320 kb/s.
- `visualiseur.py` contient l'interface (tkinter) et la ligne de commande.
