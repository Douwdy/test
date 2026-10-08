# Lecteur — lecteur de musique Android

Application Android (Kotlin, Jetpack Compose) qui lit la musique du téléphone,
avec les décodeurs FFmpeg intégrés pour les formats que le téléphone ne sait pas lire tout seul.

## Interface

Design maison, sans Material : uniquement Compose Foundation, avec des composants et un thème
écrits pour l'app (`ui/theme`, `ui/components`).

- Icônes **Font Awesome Free** (style solid), y compris celle de l'app : la note orange sur fond noir.

- Fond noir chaud, texte crème, un seul accent orange pour ce qui joue.
- Titres en **Instrument Serif**, texte en **Space Grotesk**, chiffres et étiquettes en **JetBrains Mono**
  (polices libres OFL).
- Licences des polices (SIL OFL 1.1) et de Font Awesome (icônes CC BY 4.0) dans `licences/`.
- Le lecteur plein écran prend la couleur dominante de la pochette ; un égaliseur animé signale le morceau en cours.
- Les morceaux sans pochette reçoivent un dégradé propre à chacun.

## Fonctionnalités

- **Bibliothèque** : onglets Titres, Albums, Artistes, Dossiers, avec pochettes.
- **Recherche** instantanée, insensible à la casse et aux accents.
- **Lecture** : tout lire, aléatoire, répétition (tout / un titre), file d'attente, barre de progression.
- **Arrière-plan** : notification de lecture, écran de verrouillage, boutons du casque et de la montre,
  pause automatique quand on débranche le casque, gestion des appels et des autres apps audio.
- **Ouvrir des fichiers** hors bibliothèque (icône dossier en haut),
  ou depuis une autre app via « Ouvrir avec… → Lecteur ».
- Un fichier illisible est **sauté** automatiquement, avec un message qui dit lequel.

## Formats

La lecture passe par Media3/ExoPlayer : les décodeurs du téléphone sont utilisés en priorité (économes
en batterie) et le décodeur **FFmpeg** prend le relais pour le reste
([`org.jellyfin.media3:media3-ffmpeg-decoder`](https://github.com/jellyfin/jellyfin-androidx-media),
binaires précompilés publiés sur Maven Central).

| Fichier | Codecs |
| --- | --- |
| `.mp3` | MP3 |
| `.m4a`, `.mp4`, `.aac` | AAC, HE-AAC, **ALAC** |
| `.flac` | FLAC (y compris 24 bits / haute résolution) |
| `.ogg`, `.oga`, `.opus` | Vorbis, Opus, FLAC |
| `.wav` | PCM, A-law, µ-law |
| `.mka`, `.mkv`, `.webm` | tous les codecs ci-dessus, AC-3, E-AC-3, DTS, TrueHD |
| `.ac3`, `.ec3`, `.amr`, `.3gp`, `.ts` | AC-3, E-AC-3, AMR-NB/WB, AAC |

**Non pris en charge** : `.wma` (Windows Media), `.ape` (Monkey's Audio), `.wv` (WavPack), `.dsf`/`.dff` (DSD),
`.aiff`. Ce ne sont pas les codecs qui manquent mais les conteneurs, qu'ExoPlayer ne sait pas ouvrir.
Ces fichiers apparaissent dans la bibliothèque et sont sautés avec un message. Pour les lire,
il faut les convertir une fois en FLAC (sans perte), par exemple :

```sh
ffmpeg -i morceau.ape morceau.flac
```

## Compiler

Prérequis : JDK 17 ou plus récent, Android SDK (plateforme 36).

```sh
./gradlew assembleRelease
```

L'APK est dans `app/build/outputs/apk/release/app-release.apk`, prêt à installer
(`adb install`, ou copie sur le téléphone en autorisant les sources inconnues).
Il est signé avec la clé de debug : avant une publication sur le Play Store,
remplacer `signingConfig` dans `app/build.gradle.kts` par une vraie clé.

Android 8.0 (API 26) minimum.

Captures d'écran des écrans principaux, rendues sur la JVM sans téléphone (Robolectric + Roborazzi) :

```sh
./gradlew testDebugUnitTest   # → app/build/screenshots/*.png
```

## Organisation du code

```
app/src/main/java/fr/douwdy/lecteur/
├── MainActivity.kt            permission, navigation, fichiers ouverts depuis d'autres apps
├── data/
│   ├── Models.kt              Track, Album, Artist, Folder, Library
│   ├── MusicRepository.kt     lecture de MediaStore et regroupements
│   └── ArtworkLoader.kt       pochettes intégrées aux fichiers, avec cache
├── playback/
│   ├── PlaybackService.kt     ExoPlayer + FFmpeg dans un MediaSessionService
│   ├── PlayerConnection.kt    MediaController exposé à l'interface en StateFlow
│   └── MediaItems.kt          conversion Track → MediaItem
└── ui/
    ├── MusicViewModel.kt      état de la bibliothèque, recherche, commandes
    ├── theme/                 couleurs, typographies, icônes Font Awesome
    ├── components/            texte, boutons, barre de progression, lignes, mini-lecteur, messages
    └── screens/               bibliothèque, album/artiste/dossier, lecteur plein écran
```

Les icônes de `ui/theme/Icons.kt` sont générées depuis les SVG du paquet npm
`@fortawesome/fontawesome-free` (tracés recopiés tels quels, chaque glyphe centré dans un carré de 512).

## Versions

Media3 est figé en **1.9.0** : c'est la version contre laquelle le décodeur FFmpeg précompilé
est construit. Le monter impose d'attendre la version correspondante de
`media3-ffmpeg-decoder` (le numéro suit celui de Media3, par exemple `1.9.0+1`).
