# LightTune : document de passation

Ce document sert à reprendre le projet sur ton PC. Commence par lire « État actuel »,
puis « Démarrage rapide ».

---

## 1. État actuel

| Élément | État | Vérifié ? |
|---|---|---|
| Moteur DSP (`core/include/lighttune/`) | terminé | oui : 9 tests objectifs passent (`tests/test_engine.cpp`) |
| Plugin CLAP (`plugins/clap/`) | terminé, sans interface | oui : chargé et testé dans un mini‑hôte (`tests/test_clap_host.cpp`) |
| Outil CLI WAV (`cli/`) | terminé | oui, testé à la main |
| VST3 / AU / Standalone JUCE (`plugins/juce/`) | code écrit | **NON COMPILÉ**. La compilation de JUCE n'a pas pu être lancée dans l'environnement de développement. C'est **la première chose à faire** (voir §3) |
| CI GitHub (`.github/workflows/build.yml`) | écrite | résultat non vérifié (onglet *Actions* du dépôt `douwdy/test`) |
| Tests sur une vraie voix | **pas faits** | les tests ont été faits sur des voix synthétiques uniquement |

Dépôt : `douwdy/test`, branche `claude/autotune-pro-lightweight-qhe6nr`.

---

## 2. Démarrage rapide

### Prérequis

| OS | Outils |
|---|---|
| Windows | Visual Studio 2022 (charge de travail « Développement desktop en C++ »), CMake ≥ 3.22, Git |
| macOS | Xcode + outils en ligne de commande, CMake ≥ 3.22 (`brew install cmake`) |
| Linux | `sudo apt install build-essential cmake git libasound2-dev libx11-dev libxrandr-dev libxinerama-dev libxcursor-dev libxext-dev libfreetype-dev libfontconfig1-dev libgl1-mesa-dev libxcomposite-dev` |

Une connexion internet est nécessaire au premier `cmake` : il télécharge les en‑têtes
CLAP et, si on le demande, JUCE.

### Compiler et tester (CLI + CLAP + tests)

```bash
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release -j
ctest --test-dir build -C Release --output-on-failure
```

Résultat attendu : `ALL TESTS PASSED`. Le test d'hôte CLAP n'est compilé que sous
Linux et macOS.

### Compiler les plugins VST3 / AU / Standalone (JUCE)

```bash
cmake -S . -B build-juce -DCMAKE_BUILD_TYPE=Release -DLIGHTTUNE_BUILD_JUCE=ON
cmake --build build-juce --config Release -j
```

Les binaires se trouvent dans `build-juce/LightTunePlugin_artefacts/Release/`
(`VST3/`, `AU/`, `Standalone/`).

### Installer les plugins

| Format | Windows | macOS | Linux |
|---|---|---|---|
| VST3 | `C:\Program Files\Common Files\VST3\` | `~/Library/Audio/Plug-Ins/VST3/` | `~/.vst3/` |
| AU | — | `~/Library/Audio/Plug-Ins/Components/` | — |
| CLAP | `C:\Program Files\Common Files\CLAP\` | `~/Library/Audio/Plug-Ins/CLAP/` | `~/.clap/` |

Sur macOS, un plugin non signé peut être bloqué. Pour le débloquer :
`xattr -dr com.apple.quarantine <plugin>`.

---

## 3. Prochaines étapes, par priorité

1. **Faire compiler le wrapper JUCE.** Le code vise JUCE 8.0.9. S'il y a des erreurs,
   elles devraient être petites (API de `Font` / `FontOptions`, attachements
   d'interface). Fichiers concernés : `plugins/juce/PluginProcessor.cpp` et
   `PluginEditor.cpp`. Ensuite, ouvre le Standalone et vérifie que la courbe de
   hauteur bouge quand tu chantes.
2. **Tester sur de vraies voix** dans un DAW, en comparant avec Auto‑Tune si tu l'as.
   Points à écouter :
   * changements de note avec Retune à 0 (artefacts, clics) ;
   * voix soufflées et *fry* : régler `Tracking` ;
   * notes très graves (Input Type « Low Male ») ;
   * transpositions importantes (±7 demi‑tons et plus) : PSOLA dégrade un peu à fort
     décalage.
3. **Ajuster les dosages à l'oreille** (constantes listées au §5).
4. Fonctions manquantes, de la plus utile à la moins utile :
   * mode Graph (édition de la courbe de hauteur note par note) ;
   * préréglages ;
   * interface pour le plugin CLAP (via `clap-wrapper` ou JUCE 8, qui peut sortir du
     CLAP) ;
   * AAX pour Pro Tools (demande le SDK Avid et une signature PACE).

---

## 4. Architecture

```
core/include/lighttune/      ← tout le DSP, en-têtes seulement, C++17, sans dépendance
  PitchDetector.h   passe-bas + décimation (~11 kHz), fonction de différence mise à jour
                    à chaque échantillon (idée du brevet Auto-Tune), YIN, raffinement à pleine résolution
  PitchCorrector.h  choix de la note cible, Retune / Humanize / Flex-Tune / Natural Vibrato,
                    vibrato synthétique, mode Classic. Structure Params = tous les réglages
  Engine.h          moteur temps réel : marques de période, PSOLA, formants/throat, latence
  Scales.h          gammes (masques 12 bits), état par note (On / Off / Bypass)
  ParamDefs.h       table unique des 36 paramètres (ids, plages, défauts, textes)
                    → utilisée par CLAP et JUCE ; ajoute un paramètre ICI uniquement
plugins/clap/lighttune_clap.cpp   plugin CLAP (paramètres, MIDI, latence, état)
plugins/juce/                     VST3/AU/Standalone + interface graphique
cli/lighttune_cli.cpp, wav.h      outil WAV (+ --detect-key, --pitch-log)
tests/test_engine.cpp             tests objectifs (précision, transparence, formants, CPU, robustesse)
tests/test_clap_host.cpp          mini-hôte CLAP
docs/RESEARCH.md                  comment fonctionne Auto-Tune et la correspondance avec chaque réglage
```

### Flux du signal (`Engine::process`, échantillon par échantillon)

1. L'entrée est écrite dans un buffer circulaire. Le mono (moyenne des canaux) sert à
   l'analyse.
2. `PitchDetector::push`. Toutes les 2,9 ms environ, il produit une estimation, qui
   passe par `PitchCorrector::process` → décalage en demi‑tons. Ce décalage est stocké
   dans un historique horodaté.
3. `placeMarks` pose une marque d'analyse par période, alignée par intercorrélation.
4. `synthesise` : pour chaque marque de synthèse, il prend la marque d'analyse la plus
   proche et ajoute un grain Hann de 2 périodes par recouvrement‑addition (OLA).
   Espacement = période / rapport de hauteur ; lecture du grain × facteur de formant.
   En zone non voisée, un OLA à 50 % reconstruit le signal à l'identique.
5. La sortie est lue avec une latence fixe (`latencySamples()`), puis mélangée
   dry/wet.

### Règles à respecter

* **Pas d'allocation ni de verrou dans `process()`.** `prepare()` alloue : ne
  l'appelle jamais depuis le fil audio.
* Changer `inputType` ou `lowLatency` modifie la latence. `setParams()` renvoie alors
  `true` et le wrapper doit refaire un `prepare()` hors du fil audio (CLAP :
  `request_restart` ; JUCE : `AsyncUpdater`).
* Après toute modification DSP, relance `ctest`. Les seuils des tests sont les
  garde‑fous de qualité.

---

## 5. Constantes réglables à l'oreille

| Où | Constante | Valeur | Effet |
|---|---|---|---|
| `PitchDetector::setTracking` | `dipThreshold_`, `voicedThreshold_` | 0,10–0,25 / 0,20–0,50 | sensibilité de détection du voisement |
| `PitchDetector::prepare` | `hopD_` | 32 (≈ 2,9 ms) | fréquence d'analyse |
| `PitchCorrector::process` | passe‑bas du centre de vibrato | 2,5 Hz | séparation centre / vibrato (Natural Vibrato) |
| idem | Retune : `exp(-dt*3/retune)` | 95 % au temps Retune | « courbe » de la vitesse de correction |
| idem | Humanize : `0.12 s`, `0.3 s`, `0.4 s` | — | délai, rampe et ralentissement maximal sur les notes tenues |
| idem | Flex‑Tune : zone `0.5*(1-f)+0.03`, fondu `0.2` | — | largeur de la zone de correction |
| idem | Classic : pente `1.5 / retune` demi‑ton/s | — | caractère du mode Classic |
| `chooseTarget` | hystérésis `0.06` demi‑ton | — | stabilité de la note cible |
| `inputTypeRange` | plages en Hz par type de voix | — | plage détectée et latence |
| `Engine::prepare` | `lookahead_`, `unvHop_` | 1,5 × Pmax / 3 ms | compromis entre latence et qualité |

---

## 6. Mesures de référence (à retrouver après modification)

| Test | Valeur attendue |
|---|---|
| Hard tune, voix +40 cents ± vibrato 30 cents | erreur moyenne ≈ 0,7 cent |
| Vibrato 50 cents, Retune 0 / 300 ms | ≈ 2 / ≈ 46 cents |
| Bruit non voisé | ≈ 132 dB de rapport signal/erreur |
| +5 demi‑tons, Formant ON / OFF / Throat 140 | −2 % / +17 % / −19 % (centroïde) |
| CPU mono 44,1 kHz / stéréo 48 kHz | ≈ 0,36 % / 0,46 % d'un cœur |
| Latence Alto/Ténor normale / Low Latency | 31,5 ms / 15,8 ms |

---

## 7. Pièges connus

* **Licence JUCE** : AGPLv3 ou licence commerciale. Pour distribuer le VST3/AU en code
  fermé, il faut une licence JUCE. Le moteur et le CLAP n'en dépendent pas.
* **Nom et marque** : ne pas utiliser le nom « Auto‑Tune » ni le visuel d'Antares
  dans le produit.
* **Fort décalage** : au‑delà d'environ ±7 demi‑tons, PSOLA peut sonner « métallique ».
  C'est normal pour cette méthode. Une piste d'amélioration serait un vocodeur de phase
  en option, au prix d'un CPU plus élevé.
* La détection de tonalité (`--detect-key`) a besoin de plusieurs secondes de chant
  varié pour être fiable.
* Le mode Classic est une **approximation** : le comportement exact d'Auto‑Tune 5 n'est
  pas public.

---

## 8. Commandes utiles

```bash
# traiter un fichier et enregistrer la courbe de hauteur
./build/lighttune voix.wav out.wav --key A --scale minor --retune 0 --pitch-log pitch.csv
./build/lighttune voix.wav --detect-key
./build/lighttune --help

# relancer uniquement les tests DSP
./build/lighttune_tests
```
