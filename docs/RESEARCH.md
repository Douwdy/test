# Comment fonctionne Auto‑Tune Pro, et comment LightTune le reproduit

Ce document résume ce que l'on sait **publiquement** d'Auto‑Tune (brevet, manuels,
tests de presse) et explique, contrôle par contrôle, comment LightTune reproduit chaque
comportement avec un coût CPU minimal.

> Auto‑Tune est un logiciel propriétaire : son code et ses algorithmes internes exacts
> ne sont pas publics. LightTune n'en contient aucun code. C'est une ré‑implémentation
> indépendante qui reprend des comportements documentés, à partir de méthodes de
> traitement du signal publiées. Le son ne peut donc pas être garanti « identique »
> à l'échantillon près. En revanche, les comportements et les réglages correspondent.

---

## 1. Le cœur historique : le brevet Hildebrand (US 5 973 252, 1998)

Andy Hildebrand (Antares, ex‑ingénieur en sismique) a breveté la méthode à l'origine
d'Auto‑Tune :

1. **Détection de la période par autocorrélation**. La période est le plus petit
   décalage `L` pour lequel le signal ressemble le plus à lui‑même. On compare
   l'autocorrélation à décalage 0 et à décalage `L`, ce qui revient à minimiser
   `E(0) − 2·H(L)`, c'est‑à‑dire la fonction de différence `Σ (x[n] − x[n−L])²`.
2. **L'astuce qui rendait cela faisable en temps réel en 1998** : au lieu de recalculer
   toute l'autocorrélation à chaque trame, on la **met à jour de façon incrémentale**.
   On ajoute le produit de l'échantillon entrant et on retire celui de l'échantillon qui
   sort de la fenêtre. Le brevet décrit aussi une **réduction de la fréquence
   d'échantillonnage** avant l'analyse.
3. **Correction** : la hauteur détectée est comparée à la note de la gamme la plus
   proche, puis le signal est ré‑échantillonné ou décalé, période par période, pour
   atteindre cette note à la vitesse « Retune Speed ».

**Dans LightTune** (`core/include/lighttune/PitchDetector.h`), on applique exactement ces
deux idées, plus deux raffinements modernes :

| Étape | LightTune | Coût |
|---|---|---|
| Passe‑bas Butterworth d'ordre 4 + décimation à environ 11 kHz | réduction de fréquence (comme le brevet) | 2 biquads/échantillon |
| Fonction de différence **mise à jour incrémentalement** | idée centrale du brevet | O(lag max) par échantillon décimé |
| Normalisation cumulative (YIN, de Cheveigné & Kawahara 2002) + premier creux sous un seuil | évite les erreurs d'octave | O(lag max) toutes les 2,9 ms |
| Interpolation parabolique + **raffinement à pleine résolution** autour de la période | précision < 0,05 cent | environ 30 opérations/échantillon |
| Garde‑fou anti‑saut d'octave | stabilité | négligeable |

Précision mesurée : 0,001 à 0,01 cent sur des sons stables de 110 à 1 000 Hz.

## 2. Le décalage de hauteur : PSOLA (grains synchrones de la période)

Auto‑Tune corrige la voix sans la « chipmunker » quand l'écart est faible. La méthode
classique la plus économe est la **TD‑PSOLA** (Time‑Domain Pitch‑Synchronous
Overlap‑Add, Moulines & Charpentier 1990) :

* on pose une **marque d'analyse** à chaque période du signal voisé ;
* chaque grain fait 2 périodes et passe par une fenêtre de Hann : il contient une
  impulsion glottique **avec son enveloppe spectrale, donc ses formants** ;
* on recolle ces grains à l'espacement de la **nouvelle** période.

La hauteur change, les formants restent en place. C'est la « correction de formants »
gratuite, sans FFT et sans cepstre. Coût : quelques multiplications‑additions par
échantillon.

Dans LightTune (`core/include/lighttune/Engine.h`) :

* les marques sont **alignées en phase** par une petite recherche d'intercorrélation
  (±P/8), en conservant la partie fractionnaire. Sans cela, on mesurait un biais de
  ±9 cents dans l'aigu ;
* la lecture se fait avec une **interpolation d'Hermite à 4 points**, avec précision
  sous‑échantillon ;
* les parties **non voisées** (s, ch, t, souffle) sont reconstruites par un OLA Hann à
  50 % de recouvrement, qui reconstruit parfaitement le signal. Elles ressortent
  intactes : mesure à 132 dB de rapport signal/erreur ;
* l'alignement temporel entre la courbe de correction et l'audio a été étalonné. Avec
  un Retune à 0 sur un vibrato de ±50 cents, le résidu est de 2 cents.

### Formant / Throat Length (« modélisation de gorge »)

Auto‑Tune Pro peut **déplacer les formants indépendamment de la hauteur** (bouton
*Throat Length* : une gorge plus longue donne des formants plus graves). En PSOLA, il
suffit de **ré‑échantillonner chaque grain**. On lit le grain un peu plus vite pour
monter les formants, un peu plus lentement pour les descendre. La période de sortie,
elle, ne change pas.

* Formant **ON** : facteur de formant = 100 / Throat (+ vibrato de formant).
* Formant **OFF** : facteur de formant = facteur de hauteur. Les formants suivent la
  hauteur, c'est le son « chipmunk » volontaire.

Mesures sur une transposition de +5 demi‑tons : centroïde spectral −2 % avec Formant ON,
+17 % avec Formant OFF, −19 % avec Throat 140 %.

## 3. Les réglages d'Auto‑Tune Pro et leur équivalent

| Contrôle Auto‑Tune Pro | Ce qu'il fait (documentation et tests publics) | Implémentation LightTune |
|---|---|---|
| **Key / Scale** | gamme cible | 16 gammes, 12 tonalités (`Scales.h`) |
| **Remove / Bypass** par note | retirer une note des cibles ou la laisser libre | paramètre par note : Scale / Remove / Bypass |
| **Target notes via MIDI** | les notes MIDI jouées deviennent les cibles | entrée MIDI (CLAP et JUCE), classes de hauteur tenues |
| **Retune Speed** (0–400 ms) | temps pour atteindre la note ; 0 = effet « robot » | lissage exponentiel de la *correction* (et non de la hauteur), environ 95 % atteint au temps indiqué. À vitesse lente, le vibrato naturel passe |
| **Humanize** | garde la vitesse Retune sur les notes courtes, la ralentit sur les notes tenues | durée de tenue → Retune effectif + jusqu'à 400 ms × Humanize |
| **Flex‑Tune** | corrige seulement quand le chanteur s'approche de la note, garde les inflexions | poids de correction en fonction de l'écart à la cible (zone pleine, puis fondu en *smoothstep*) |
| **Natural Vibrato** (±12 dB) | amplifie ou réduit le vibrato d'origine | séparation centre / vibrato par un passe‑bas d'ordre 2 à 2,5 Hz sur la courbe de hauteur, puis gain sur la composante vibrato |
| **Tracking** | sélectif ↔ tolérant (voix soufflées) | seuils YIN de détection de voisement |
| **Input Type** | Soprano, Alto/Ténor, Voix grave, Instrument, Basse | plage de recherche et latence |
| **Transpose / Detune** | décalage en demi‑tons / diapason de référence | ajouté au décalage / translation de la grille de notes |
| **Formant / Throat** | voir §2 | ré‑échantillonnage des grains |
| **Create Vibrato** (forme, vitesse, délai d'attaque, vitesse d'attaque, variation, quantités de hauteur, d'amplitude et de formant) | vibrato synthétique | LFO sinus / carré / dent de scie, enveloppe d'attaque, variation aléatoire lissée |
| **Classic Mode** | le « son Auto‑Tune 5 » ; désactive Formant, Throat, Transpose et Flex‑Tune | correction à pente constante (*slew*), formants suivant la hauteur, Humanize / Flex / Natural Vibrato / Transpose désactivés |
| **Low Latency** | latence réduite au prix d'un peu de qualité | grain pris dans la dernière période complète au lieu d'être centré : 15 à 21 ms au lieu de 31 à 50 ms |
| **Auto Key** (détection de tonalité) | trouve la tonalité | CLI : `--detect-key` (profils de Krumhansl‑Schmuckler) |

### Non reproduit (volontairement ou pas encore)

* **Graph Mode** : édition de la courbe de hauteur note par note, à la souris. C'est un
  éditeur graphique complet (piano roll, outils courbe et ligne, *time‑stretch*). Ce
  serait un projet en soi, et cela n'a rien à voir avec le coût DSP.
* **ARA 2**, **AAX** (Pro Tools : il faut le SDK Avid et une signature), harmonies et
  doublage.
* Les petites « recettes » sonores propriétaires d'Antares. Leurs courbes exactes ne
  sont pas publiques ; les nôtres sont des choix raisonnables et documentés dans le
  code.

## 4. Pourquoi c'est si léger

| Poste | Auto‑Tune Pro (estimation) | LightTune |
|---|---|---|
| Détection | analyse plus lourde, plusieurs modes | autocorrélation **incrémentale** à environ 11 kHz |
| Décalage | moteur propriétaire | PSOLA sans FFT |
| Formants | traitement dédié | intrinsèque à PSOLA (ré‑échantillonnage du grain) |
| Interface | interface vectorielle lourde, ARA | aucune (CLAP) ou interface JUCE simple, 30 images/s |
| Mémoire | — | environ 100 à 200 Ko par instance (44,1–48 kHz) |

Mesures sur un seul cœur (conteneur Linux, `-O2`) :

* mono 44,1 kHz : **0,36 % d'un cœur**, soit environ 280 fois le temps réel ;
* stéréo 48 kHz, toutes les fonctions actives : **0,46 % d'un cœur** ;
* traitement hors ligne d'un fichier : 250 fois le temps réel.

## Sources

* Brevet US 5 973 252, *Pitch detection and intonation correction apparatus and method*
  (H. A. Hildebrand) : https://patents.google.com/patent/US5973252
* Valhalla DSP, *Auto‑Tune, autocorrelation, and seismic analysis* : https://valhalladsp.com/?p=1559
* Page produit Antares Auto‑Tune Pro 11 : https://www.antarestech.com/products/auto-tune/pro
* Test MusicRadar d'Auto‑Tune Pro : https://www.musicradar.com/reviews/antares-auto-tune-pro
* Sweetwater, *Auto‑Tune Quickstart Guide* (Classic Mode) : https://www.sweetwater.com/sweetcare/articles/auto-tune-quickstart-guide/
* A. de Cheveigné & H. Kawahara, *YIN, a fundamental frequency estimator for speech and music*, JASA 2002.
* E. Moulines & F. Charpentier, *Pitch‑synchronous waveform processing techniques for text‑to‑speech synthesis using diphones*, Speech Communication 1990 (TD‑PSOLA).
* C. Krumhansl, *Cognitive Foundations of Musical Pitch*, 1990 (profils de tonalité).
