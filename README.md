# LightTune : correcteur de hauteur vocal ultra‑léger

LightTune est une ré‑implémentation indépendante, en C++17 sans dépendance, des
comportements d'un correcteur vocal de type Auto‑Tune Pro : Retune Speed, Humanize,
Flex‑Tune, Natural Vibrato, Formant/Throat, Classic Mode, Low Latency, Create Vibrato,
gammes avec Remove/Bypass par note, notes cibles par MIDI. Il consomme **moins de 0,5 %
d'un cœur CPU** en stéréo.

> Le fonctionnement d'Auto‑Tune et la correspondance avec chaque réglage sont détaillés
> dans [docs/RESEARCH.md](docs/RESEARCH.md). Ce projet n'est pas affilié à Antares et ne
> contient aucun de leurs codes ou ressources.

## Formats

| Format | Statut | Hôtes |
|---|---|---|
| **CLAP** (`LightTune.clap`) | compilé et testé dans un hôte de test | Bitwig, Reaper, FL Studio, Studio One, … |
| **VST3 / AU / Standalone** (JUCE, avec interface graphique) | code fourni ; compilation par la CI GitHub ou en local avec `-DLIGHTTUNE_BUILD_JUCE=ON` | Ableton, Cubase, Logic (AU), FL, Reaper, … |
| **CLI** (`lighttune`) | compilé et testé | traitement de fichiers WAV |

## Mesures (tests objectifs, `tests/test_engine.cpp`)

| Test | Résultat |
|---|---|
| Hard tune (Retune 0) d'une voix à +40 cents avec ±30 cents de vibrato | erreur moyenne **0,7 cent**, pire cas 3,2 cents |
| Vibrato ±50 cents, Retune 0 / Retune 300 ms | résidu 2 cents / vibrato conservé à 46 cents |
| Glissando C4 → E4 en Do majeur | seules les notes de la gamme sortent |
| Bruit non voisé (consonnes, souffle) | ressort intact (rapport signal/erreur de **132 dB**) |
| Transposition +5 demi‑tons, Formant ON / OFF / Throat 140 % | enveloppe −2 % / +17 % / −19 % |
| Voix grave D2, soprano A5 | erreur 0,0 / 0,4 cent |
| CPU mono 44,1 kHz / stéréo 48 kHz, toutes options | **0,36 % / 0,46 %** d'un cœur |
| Latence : normale / Low Latency | 31,5 ms / 15,8 ms (Alto/Ténor) |
| Automation aléatoire, 22 à 192 kHz, signaux extrêmes | aucun NaN, sortie bornée |

## Compilation

```bash
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release              # CLI + CLAP + tests
cmake --build build -j
ctest --test-dir build --output-on-failure

# VST3 / AU / Standalone avec interface (télécharge JUCE 8) :
cmake -S . -B build -DLIGHTTUNE_BUILD_JUCE=ON && cmake --build build -j --config Release
```

Sous Linux, JUCE demande : `libasound2-dev libx11-dev libxrandr-dev libxinerama-dev
libxcursor-dev libfreetype-dev libfontconfig1-dev libgl1-mesa-dev`.

La CI (`.github/workflows/build.yml`) compile Windows, macOS (universel) et Linux, lance
les tests et publie les plugins comme artefacts.

> Licence : JUCE est sous AGPLv3 ou licence commerciale. Si vous distribuez le
> VST3/AU, respectez l'une des deux. Le cœur DSP et le plugin CLAP n'en dépendent pas.

## Utilisation en ligne de commande

```bash
lighttune voix.wav sortie.wav --key A --scale minor --retune 0           # effet « robot »
lighttune voix.wav sortie.wav --key D --scale major --retune 25 --humanize 40 --flex 30
lighttune voix.wav sortie.wav --transpose -3 --throat 115                 # plus grave, gorge plus longue
lighttune voix.wav sortie.wav --classic --retune 0                        # son « Classic »
lighttune voix.wav --detect-key                                           # trouve la tonalité
lighttune --help
```

## Architecture

```
core/include/lighttune/
  PitchDetector.h   autocorrélation incrémentale décimée + YIN + raffinement pleine résolution
  PitchCorrector.h  gamme, Retune, Humanize, Flex-Tune, Natural Vibrato, Create Vibrato, Classic
  Engine.h          PSOLA temps réel (marques alignées en phase, formants, throat), latence fixe
  Scales.h          gammes et état par note
  ParamDefs.h       table de paramètres partagée par tous les wrappers
plugins/clap/       plugin CLAP (sans interface : éditeur générique de l'hôte)
plugins/juce/       VST3 / AU / Standalone avec interface (graphe de hauteur, indicateur de correction)
cli/                outil WAV
tests/              tests objectifs du DSP + mini-hôte CLAP
```

Aucune allocation, aucun verrou et aucune FFT dans le fil audio.
