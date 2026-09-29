#!/usr/bin/env python3
"""Génère les pages HTML du site dans public/ à partir d'un seul gabarit.

    python3 tools/build.py

Sources :
- les textes des pages d'accueil (FR et EN) sont dans le dictionnaire T ci-dessous ;
- la politique de confidentialité est dans content/confidentialite-fr.html et
  content/confidentialite-en.html, recopiée telle quelle (aucune retouche typographique) ;
- les captures d'écran sont dans content/captures/ ; tools/images.py en tire les WebP de public/img/.

Fichiers produits : public/index.html, public/en/index.html, public/confidentialite.html,
public/en/privacy.html, public/404.html, public/wave.svg. Ne pas les modifier à la main.
"""
import math
import pathlib
import random

REPO = pathlib.Path(__file__).resolve().parent.parent
PUBLIC = REPO / "public"
CONTENT = REPO / "content"

SITE = "https://rhynote.app"
# À remplacer par l'URL exacte de la fiche : https://play.google.com/store/apps/details?id=…
PLAY = "https://play.google.com/store/search?q=Rhynote&c=apps"
MAIL = "Douwdy@protonmail.com"
NNBSP = " "  # espace fine insécable, avant : ; ! ? et à l'intérieur des guillemets français


def fr_typo(html):
    """Espaces insécables de la typographie française (pages d'accueil FR uniquement)."""
    for a, b in ((" :", NNBSP + ":"), (" ;", NNBSP + ";"), (" ?", NNBSP + "?"), (" !", NNBSP + "!"),
                 ("« ", "«" + NNBSP), (" »", NNBSP + "»"), ("100 %", "100" + NNBSP + "%")):
        html = html.replace(a, b)
    return html


def wave_rects(n, w, h, seed, gap=0.4):
    """Barres d'onde sonore (hauteurs pseudo-aléatoires, déterministes)."""
    rnd = random.Random(seed)
    bw = w / n
    out = []
    for i in range(n):
        env = 0.35 + 0.65 * abs(math.sin(i / n * math.pi * 2.3 + seed))
        v = max(0.12, min(1, env * (0.55 + 0.45 * rnd.random())))
        bh = round(v * h, 1)
        rw = round(bw * (1 - gap), 2)
        out.append(f'<rect x="{round(i * bw + bw * gap / 2, 2)}" y="{round((h - bh) / 2, 1)}" '
                   f'width="{rw}" height="{bh}" rx="{round(rw / 2, 2)}"/>')
    return "".join(out)


# Onde décorative : fichier séparé, utilisé comme masque CSS (.band-wave) et dans l'image de partage.
WAVE_FILE = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1200 220" preserveAspectRatio="none">'
             f'{wave_rects(120, 1200, 220, 7)}</svg>\n')

PLAY_ICON = ('<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M5 3.2v17.6a.8.8 0 0 0 '
             '1.2.7l14.4-8.8a.8.8 0 0 0 0-1.4L6.2 2.5A.8.8 0 0 0 5 3.2z"/></svg>')


def shot(name, alt, sizes="(max-width: 640px) 78vw, 300px", eager=False, cls="device"):
    """Capture d'écran dans un cadre de téléphone (images produites par tools/images.py)."""
    landscape = name == "paysage"
    small, big = (800, 1600) if landscape else (360, 720)
    w, h = (1600, 720) if landscape else (720, 1600)
    load = 'fetchpriority="high"' if eager else 'loading="lazy"'
    return (f'<figure class="{cls}{" landscape" if landscape else ""}"><img src="/img/{name}-{big}.webp" '
            f'srcset="/img/{name}-{small}.webp {small}w, /img/{name}-{big}.webp {big}w" sizes="{sizes}" '
            f'width="{w}" height="{h}" alt="{alt}" {load} decoding="async"></figure>')


# Chrome commun aux deux langues : accueil, politique, libellés de navigation.
L = {
    "fr": dict(home="/", privacy="/confidentialite", other="en", other_label="English",
               other_title="Read this page in English", nav_label="Navigation principale",
               foot_label="Pied de page", home_label="Rhynote, accueil", home_word="Accueil",
               privacy_word="Politique de confidentialité", contact="Contact",
               tagline="Rhynote · Fait pour celles et ceux qui écrivent.", og="/og.png", og_locale="fr_FR"),
    "en": dict(home="/en", privacy="/en/privacy", other="fr", other_label="Français",
               other_title="Lire cette page en français", nav_label="Main navigation",
               foot_label="Footer", home_label="Rhynote, home", home_word="Home",
               privacy_word="Privacy policy", contact="Contact",
               tagline="Rhynote · Made for people who write.", og="/og-en.png", og_locale="en_US"),
}

T = {
    "fr": dict(
        title="Rhynote · Écris tes textes en rythme et enregistre tes maquettes",
        desc="Rhynote, l'appli Android pour écrire tes textes : syllabes comptées, schéma de rimes, dictionnaire de "
             "rimes, conseils de rythme et un studio multipiste pour tes maquettes. 100 % hors-ligne, aucune donnée "
             "collectée.",
        skip="Aller au contenu",
        ids=("ecrire", "studio", "confidentialite"),
        nav=("Écrire", "Studio", "Confidentialité", "FAQ"),
        eyebrow="Pour rappeurs, chanteurs et auteurs · Android",
        h1="Écris en rythme. <em>Enregistre ta maquette.</em>",
        lede="Rhynote compte tes syllabes, colore tes rimes, t'aide à caler ton flow sur le tempo et transforme ton "
             "texte en maquette, piste par piste. Tout se passe sur ton téléphone : sans compte, sans pub, sans "
             "Internet.",
        cta="Télécharger sur Google Play", cta2="Découvrir l'appli",
        chips=("100 % hors-ligne", "Aucune donnée collectée", "Sans pub", "Sans compte"),
        hero_shots=(("editeur", "L'éditeur de Rhynote : chaque vers avec son nombre de syllabes et la lettre de sa rime"),
                    ("dictionnaire", "Le dictionnaire de rimes de Rhynote, pour le mot « minuit »")),
        w_kicker="Écrire", w_title="Un carnet qui écoute ton rythme.",
        w_sub="Rhynote n'est pas un simple bloc-notes : il compte, il rime et il te dit quand un vers déborde.",
        shows=(
            ("editeur", "L'éditeur : vers numérotés par syllabes, rimes surlignées en couleur, alerte sur une ligne trop longue",
             "Éditeur", "Chaque syllabe compte.",
             "Rhynote compte les syllabes de chaque vers pendant que tu écris. Fixe une cible par ligne et un tempo : "
             "les vers trop longs ou trop courts ressortent tout de suite.",
             ("Syllabes comptées vers par vers", "Cible par ligne et tempo en BPM",
              "Schéma de rimes en couleurs : A, B, C…", "Sections [Couplet], [Refrain]")),
            ("dictionnaire", "Le dictionnaire de rimes : rimes riches, suffisantes et pauvres pour le mot « minuit »",
             "Dictionnaire de rimes", "Trouve la rime juste.",
             "Tape un mot : Rhynote trouve ses rimes et les classe en riches, suffisantes et pauvres, avec le nombre "
             "de syllabes de chaque mot. Filtre par longueur, touche un mot pour le copier.",
             ("Rimes classées par qualité", "Filtre par nombre de syllabes", "Inclus dans l'appli, marche sans réseau")),
            ("conseils", "L'analyse du rythme : débit par vers, syllabes par mot et élision proposée pour raccourcir une ligne",
             "Rythme", "Cale ton flow sur le tempo.",
             "Pour chaque vers, Rhynote affiche le débit en syllabes par seconde et le compte mot par mot. Une ligne "
             "déborde ? Il propose des façons de gagner des syllabes, comme l'élision « je suis » → « j'suis », à "
             "appliquer d'un geste.",
             ("Débit en syllabes par seconde", "Propositions pour raccourcir un vers",
              "Un mot mal compté ? Touche-le pour corriger sa prononciation")),
            ("notes", "La liste des notes, rangées par catégories : Album, Freestyles, Refrains, Feats",
             "Notes", "Tous tes textes, bien rangés.",
             "Classe tes notes par catégories, épingle celles en cours et retrouve n'importe quel texte avec la "
             "recherche. Rhynote garde aussi les versions de tes textes.",
             ("Catégories en couleurs", "Notes épinglées et recherche", "Versions conservées")),
            ("scene", "Le mode scène : le texte en grand sur fond sombre, le vers en cours mis en avant",
             "Mode scène", "Sur scène, garde le fil.",
             "Le mode scène affiche ton texte en grand, sur fond sombre, et met en avant le vers en cours.",
             ("Texte en grand, lisible de loin", "Vers en cours mis en avant")),
        ),
        s_kicker="Studio", s_title="Un studio de poche pour tes maquettes.",
        s_sub="Pose ta voix sur ton instru, piste par piste, avec tes paroles sous les yeux pendant que tu enregistres.",
        s_wide_alt="Le studio en mode paysage : l'instru et les pistes Couplet, Refrain et Ad-libs sur la ligne de temps",
        s_cards=(
            ("maquette", "Le studio en mode portrait : paroles en haut, pistes instru, voix, piano et batterie en dessous",
             "Multipiste", "Instru, couplet, refrain, ad-libs, piano, batterie : chaque partie a sa piste, avec muet, "
                           "solo et enregistrement. Coupe tes prises, duplique-les et règle leur volume."),
            ("effets", "La chaîne d'effets d'une piste voix : AutoPitch, égaliseur, compresseur, de-esser, delay",
             "Effets voix", "AutoPitch pour corriger la justesse, du naturel à l'effet robot. Égaliseur, compresseur, "
                            "de-esser, delay calé sur le tempo, et des presets prêts à l'emploi."),
            ("pianoroll", "Le piano roll : des accords écrits note par note, avec la vélocité en bas",
             "Piano roll", "Écris des accords et des mélodies note par note, avec la grille de ton choix, la "
                           "quantification et la vélocité."),
            ("console", "La console de mixage : tirettes, panoramiques et vumètres de chaque piste et du maître",
             "Console", "Volume, panoramique et vumètre pour chaque piste, et une sortie maître pour équilibrer "
                        "ton mix."),
        ),
        s_export="Ta maquette est prête ? Exporte-la en fichier audio, puis enregistre-la où tu veux ou partage-la "
                 "avec l'appli de ton choix.",
        p_kicker="Confidentialité", p_title="Tes textes restent à toi.",
        p_sub="Rhynote ne collecte, ne transmet et ne vend aucune donnée. L'appli ne demande même pas "
              "l'accès à Internet : elle n'envoie jamais rien d'elle-même.",
        zero="donnée collectée",
        checks=("Aucun accès à Internet demandé", "Aucun compte utilisateur", "Ni publicité, ni mesure d'audience",
                "Micro : seulement quand tu enregistres"),
        p_link="Lire la politique de confidentialité",
        b_kicker="Rhynote Studio", b_title="Un achat unique, pas d'abonnement.",
        b_text="Rhynote Studio se débloque une fois pour toutes, via Google Play. Le paiement passe entièrement "
               "par Google : Rhynote ne reçoit aucune donnée bancaire.",
        b_cta="Voir sur Google Play",
        faq_kicker="FAQ", faq_title="Questions fréquentes",
        faq=(
            ("Rhynote a-t-il besoin d'Internet ?",
             "Non. L'appli ne demande même pas l'accès à Internet : l'écriture, le dictionnaire de rimes et le studio "
             "fonctionnent hors-ligne."),
            ("Comment Rhynote compte-t-il les syllabes ?",
             "Il découpe chaque vers mot par mot et affiche le compte de chacun. Si un mot est mal compté, parce que tu "
             "le prononces à ta façon, touche-le pour corriger sa prononciation : Rhynote s'en souviendra."),
            ("Où sont stockés mes textes et mes enregistrements ?",
             "Uniquement dans le stockage privé de l'appli, sur ton téléphone. Ils sont supprimés si tu désinstalles "
             "Rhynote : fais une sauvegarde avant."),
            ("Comment sauvegarder mes textes ?",
             "Depuis l'écran « Sauvegarde », tu crées un fichier que tu enregistres toi-même, où tu veux. Si la "
             "sauvegarde Google de ton téléphone est activée, Android peut aussi copier les données de l'appli dans "
             "ton compte Google : c'est une fonction du système, que tu contrôles dans les réglages d'Android."),
            ("Puis-je utiliser une instru YouTube ?",
             "Tu peux rattacher à un texte le lien d'une instru publiée sur YouTube : Rhynote enregistre seulement ce "
             "lien et l'ouvre dans l'appli YouTube ou ton navigateur quand tu le demandes. Il ne télécharge pas la "
             "vidéo. Pour enregistrer une maquette, importe ton instru en fichier audio."),
            ("Mes maquettes sont-elles envoyées quelque part ?",
             "Non. Une maquette exportée ne quitte ton téléphone que si tu choisis « Enregistrer sous… » ou "
             "« Partager ». Rhynote n'envoie rien de lui-même."),
            ("Sur quels appareils fonctionne Rhynote ?", "Sur les téléphones Android, via Google Play."),
            ("Comment vous contacter ?", f'Par e-mail : <a href="mailto:{MAIL}">{MAIL}</a>.'),
        ),
        final_title="Ton prochain couplet t'attend.",
        final_text="Télécharge Rhynote : écris, rime, cale ton flow et enregistre, au même endroit.",
    ),
    "en": dict(
        title="Rhynote · Write lyrics in rhythm and record your demos",
        desc="Rhynote, the Android app for writing lyrics: syllable counting, rhyme scheme, rhyming dictionary, rhythm "
             "tips and a multitrack studio for your demos. 100% offline, no data collected.",
        skip="Skip to content",
        ids=("write", "studio", "privacy"),
        nav=("Write", "Studio", "Privacy", "FAQ"),
        eyebrow="For rappers, singers and writers · Android",
        h1="Write in rhythm. <em>Record your demo.</em>",
        lede="Rhynote counts your syllables, colors your rhymes, helps you fit your flow to the tempo and turns your "
             "lyrics into a demo, track by track. Everything happens on your phone: no account, no ads, no Internet.",
        cta="Get it on Google Play", cta2="Explore the app",
        chips=("100% offline", "No data collected", "No ads", "No account", "App in French"),
        hero_shots=(("editeur", "Rhynote's editor: each line with its syllable count and rhyme letter (app in French)"),
                    ("dictionnaire", "Rhynote's rhyming dictionary for the French word “minuit” (midnight)")),
        w_kicker="Write", w_title="A notebook that listens to your rhythm.",
        w_sub="Rhynote is more than a notepad: it counts, it rhymes, and it tells you when a line runs long. "
              "The app, its rhyming dictionary and its syllable counting are made for lyrics in French.",
        shows=(
            ("editeur", "The editor: syllable count per line, rhymes highlighted in color, a warning on a line that is too long",
             "Editor", "Every syllable counts.",
             "Rhynote counts the syllables of every line as you write. Set a target per line and a tempo: lines that "
             "are too long or too short stand out right away.",
             ("Syllables counted line by line", "Target per line and tempo in BPM",
              "Color-coded rhyme scheme: A, B, C…", "Sections like [Verse], [Chorus]")),
            ("dictionnaire", "The rhyming dictionary: rich, sufficient and weak rhymes for the word “minuit”",
             "Rhyming dictionary", "Find the right rhyme.",
             "Type a word: Rhynote finds its rhymes and sorts them into rich, sufficient and weak rhymes, with the "
             "syllable count of each word. Filter by length, tap a word to copy it.",
             ("Rhymes sorted by quality", "Filter by syllable count", "Built into the app, works offline")),
            ("conseils", "Rhythm analysis: delivery rate per line, syllables per word and a suggested elision to shorten a line",
             "Rhythm", "Fit your flow to the tempo.",
             "For each line, Rhynote shows the delivery rate in syllables per second and the count word by word. A "
             "line runs long? It suggests ways to drop syllables, such as elisions, that you apply with one tap.",
             ("Syllables per second", "Suggestions to shorten a line",
              "Word miscounted? Tap it to fix its pronunciation")),
            ("notes", "The notes list, sorted into categories: Album, Freestyles, Refrains, Feats",
             "Notes", "All your lyrics, neatly sorted.",
             "Sort your notes into categories, pin the ones you're working on and find any text with search. "
             "Rhynote also keeps the versions of your texts.",
             ("Color-coded categories", "Pinned notes and search", "Versions kept")),
            ("scene", "Stage mode: large text on a dark background, the current line highlighted",
             "Stage mode", "On stage, never lose your place.",
             "Stage mode shows your lyrics in large type on a dark background and highlights the current line.",
             ("Large text, readable from afar", "Current line highlighted")),
        ),
        s_kicker="Studio", s_title="A pocket studio for your demos.",
        s_sub="Lay your voice over your beat, track by track, with your lyrics in front of you while you record.",
        s_wide_alt="The studio in landscape: the beat and the Verse, Chorus and Ad-libs tracks on the timeline",
        s_cards=(
            ("maquette", "The studio in portrait: lyrics on top, beat, vocal, piano and drum tracks below",
             "Multitrack", "Beat, verse, chorus, ad-libs, piano, drums: each part gets its own track, with mute, solo "
                           "and record. Cut your takes, duplicate them and set their volume."),
            ("effets", "A vocal track's effect chain: AutoPitch, equalizer, compressor, de-esser, delay",
             "Vocal effects", "AutoPitch to correct pitch, from natural to robotic. Equalizer, compressor, de-esser, "
                              "tempo-synced delay, and ready-made presets."),
            ("pianoroll", "The piano roll: chords written note by note, with velocity at the bottom",
             "Piano roll", "Write chords and melodies note by note, with the grid of your choice, quantization and "
                           "velocity."),
            ("console", "The mixing console: faders, pan and meters for each track and the master",
             "Mixer", "Volume, pan and meter for every track, plus a master output to balance your mix."),
        ),
        s_export="Demo ready? Export it as an audio file, then save it wherever you want or share it with the app of "
                 "your choice.",
        p_kicker="Privacy", p_title="Your texts stay yours.",
        p_sub="Rhynote does not collect, transmit or sell any data. The app does not even request Internet "
              "access: it never sends anything by itself.",
        zero="data collected",
        checks=("No Internet access requested", "No user account", "No ads, no analytics",
                "Microphone: only when you record"),
        p_link="Read the privacy policy",
        b_kicker="Rhynote Studio", b_title="One purchase, no subscription.",
        b_text="Rhynote Studio is unlocked once and for all, through Google Play. Payment is handled entirely by "
               "Google: Rhynote receives no payment data.",
        b_cta="See on Google Play",
        faq_kicker="FAQ", faq_title="Frequently asked questions",
        faq=(
            ("Is Rhynote available in English?",
             "Rhynote's interface is in French, and its rhyming dictionary and syllable counting are designed for "
             "lyrics written in French."),
            ("Does Rhynote need Internet?",
             "No. The app does not even request Internet access: writing, the rhyming dictionary and the studio all "
             "work offline."),
            ("How does Rhynote count syllables?",
             "It splits each line word by word and shows the count for each. If a word is miscounted because you say "
             "it your own way, tap it to fix its pronunciation: Rhynote will remember it."),
            ("Where are my texts and recordings stored?",
             "Only in the app's private storage, on your phone. They are deleted if you uninstall Rhynote, so make "
             "a backup first."),
            ("How do I back up my texts?",
             "From the “Sauvegarde” (Backup) screen, you create a file that you save yourself, wherever you want. "
             "If Google backup is enabled on your phone, Android may also copy the app's data to your Google "
             "account: this is a system feature that you control in Android settings."),
            ("Can I use a beat from YouTube?",
             "You can attach the link of a beat published on YouTube to a text: Rhynote only stores the link and "
             "opens it in the YouTube app or your browser when you ask. It does not download the video. To record a "
             "demo, import your beat as an audio file."),
            ("Are my demos sent anywhere?",
             "No. An exported demo only leaves your phone if you choose “Save as…” or “Share”. Rhynote never sends "
             "anything by itself."),
            ("Which devices does Rhynote run on?", "Android phones, through Google Play."),
            ("How can I contact you?", f'By e-mail: <a href="mailto:{MAIL}">{MAIL}</a>.'),
        ),
        final_title="Your next verse is waiting.",
        final_text="Get Rhynote: write, rhyme, fit your flow and record, all in one place.",
    ),
}


def head(lang, title, desc, path, alternates=None, og_title=None, og_desc=None, noindex=False):
    l = L[lang]
    alt = ""
    if alternates:
        alt = "".join(f'\n<link rel="alternate" hreflang="{h}" href="{SITE}{p}">' for h, p in alternates)
    meta_robots = '\n<meta name="robots" content="noindex">' if noindex else ""
    og = "" if noindex else f"""
<link rel="canonical" href="{SITE}{path}">{alt}
<meta property="og:type" content="website">
<meta property="og:site_name" content="Rhynote">
<meta property="og:title" content="{og_title or title}">
<meta property="og:description" content="{og_desc or desc}">
<meta property="og:url" content="{SITE}{path}">
<meta property="og:locale" content="{l['og_locale']}">
<meta property="og:image" content="{SITE}{l['og']}">
<meta property="og:image:width" content="1200">
<meta property="og:image:height" content="630">
<meta name="twitter:card" content="summary_large_image">"""
    return f"""<!doctype html>
<html lang="{lang}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<meta name="description" content="{desc}">{meta_robots}
<meta name="color-scheme" content="light dark">
<meta name="theme-color" content="#3D3B8E">{og}
<link rel="icon" href="/icon.svg" type="image/svg+xml">
<link rel="apple-touch-icon" href="/apple-touch-icon.png">
<link rel="stylesheet" href="/style.css">
</head>
<body>"""


def site_header(lang, links, other_href):
    l = L[lang]
    nav = "".join(f'<a href="{h}">{t}</a>' for h, t in links)
    return f"""<div class="site-header">
    <div class="wrap">
      <a class="brand" href="{l['home']}" aria-label="{l['home_label']}"><img src="/icon.svg" alt="" width="40" height="40">Rhynote</a>
      <nav class="site-nav" aria-label="{l['nav_label']}">
        {nav}
        <a class="lang" href="{other_href}" hreflang="{l['other']}" lang="{l['other']}" title="{l['other_title']}">{l['other_label']}</a>
      </nav>
    </div>
  </div>"""


def site_footer(lang, links):
    l = L[lang]
    nav = "".join(f'<a href="{h}"' + (f' hreflang="{hl}" lang="{hl}"' if hl else "") + f">{t}</a>" for h, t, hl in links)
    return f"""<footer class="site-footer">
  <div class="wrap">
    <span>{l['tagline']}</span>
    <nav aria-label="{l['foot_label']}">{nav}</nav>
  </div>
</footer>
</body>
</html>
"""


def home(lang):
    t, l = T[lang], L[lang]
    o = L[l["other"]]
    ids = t["ids"]
    links = [(f"#{i}", n) for i, n in zip((*ids, "faq"), t["nav"])]
    chips = "".join(f"<li>{c}</li>" for c in t["chips"])
    (front, front_alt), (back, back_alt) = t["hero_shots"]
    hero_media = (shot(back, back_alt, sizes="260px", cls="device back") +
                  shot(front, front_alt, sizes="(max-width: 640px) 72vw, 300px", eager=True, cls="device front"))
    shows = ""
    for i, (name, alt, kicker, title, text, bullets) in enumerate(t["shows"]):
        items = "".join(f"<li>{b}</li>" for b in bullets)
        shows += f"""
      <article class="show{' rev' if i % 2 else ''}">
        <div class="show-media">{shot(name, alt)}</div>
        <div class="show-text">
          <p class="kicker">{kicker}</p>
          <h3>{title}</h3>
          <p>{text}</p>
          <ul class="ticks">{items}</ul>
        </div>
      </article>"""
    cards = "".join(
        f'<li class="s-card">{shot(n, a, sizes="(max-width: 640px) 70vw, (max-width: 960px) 40vw, 240px")}'
        f"<h3>{h}</h3><p>{p}</p></li>" for n, a, h, p in t["s_cards"])
    checks = "".join(f"<li>{c}</li>" for c in t["checks"])
    faq = "".join(f"<details><summary>{q}</summary><p>{a}</p></details>" for q, a in t["faq"])
    html = head(lang, t["title"], t["desc"], l["home"], alternates=[("fr", "/"), ("en", "/en"), ("x-default", "/")])
    html += f"""
<a class="skip" href="#main">{t['skip']}</a>

<header class="hero">
  {site_header(lang, links, o['home'])}

  <div class="wrap hero-grid">
    <div>
      <p class="eyebrow">{t['eyebrow']}</p>
      <h1>{t['h1']}</h1>
      <p class="lede">{t['lede']}</p>
      <div class="cta-row">
        <a class="btn btn-light" href="{PLAY}" rel="noopener">{PLAY_ICON}{t['cta']}</a>
        <a class="btn btn-ghost" href="#{ids[0]}">{t['cta2']}</a>
      </div>
      <ul class="chips">{chips}</ul>
    </div>
    <div class="hero-shots">{hero_media}</div>
  </div>
</header>

<main id="main">
  <section id="{ids[0]}">
    <div class="wrap">
      <div class="section-head">
        <p class="kicker">{t['w_kicker']}</p>
        <h2 class="title">{t['w_title']}</h2>
        <p class="sub">{t['w_sub']}</p>
      </div>{shows}
    </div>
  </section>

  <section id="{ids[1]}" class="band">
    <div class="band-wave" aria-hidden="true"></div>
    <div class="wrap">
      <div class="section-head">
        <p class="kicker">{t['s_kicker']}</p>
        <h2 class="title">{t['s_title']}</h2>
        <p class="sub">{t['s_sub']}</p>
      </div>
      {shot("paysage", t['s_wide_alt'], sizes="(max-width: 1120px) 92vw, 1000px")}
      <ul class="s-grid">{cards}</ul>
      <p class="s-export">{t['s_export']}</p>
    </div>
  </section>

  <section id="{ids[2]}">
    <div class="wrap privacy-grid">
      <div>
        <p class="kicker">{t['p_kicker']}</p>
        <h2 class="title">{t['p_title']}</h2>
        <p class="sub">{t['p_sub']}</p>
      </div>
      <div class="zero-card">
        <p class="zero"><strong>0</strong><span>{t['zero']}</span></p>
        <ul class="checks">{checks}</ul>
        <a class="link" href="{l['privacy']}">{t['p_link']} →</a>
      </div>
    </div>
  </section>

  <section class="tight">
    <div class="wrap">
      <div class="studio">
        <div>
          <p class="kicker">{t['b_kicker']}</p>
          <h2 class="title">{t['b_title']}</h2>
          <p>{t['b_text']}</p>
        </div>
        <a class="btn btn-solid" href="{PLAY}" rel="noopener">{PLAY_ICON}{t['b_cta']}</a>
      </div>
    </div>
  </section>

  <section id="faq">
    <div class="wrap faq">
      <div class="section-head">
        <p class="kicker">{t['faq_kicker']}</p>
        <h2 class="title">{t['faq_title']}</h2>
      </div>
      {faq}
    </div>
  </section>

  <section class="final">
    <div class="wrap">
      <img src="/icon.svg" alt="" width="84" height="84">
      <h2>{t['final_title']}</h2>
      <p>{t['final_text']}</p>
      <a class="btn btn-light" href="{PLAY}" rel="noopener">{PLAY_ICON}{t['cta']}</a>
    </div>
  </section>
</main>

"""
    html += site_footer(lang, [(l["privacy"], l["privacy_word"], None), (f"mailto:{MAIL}", l["contact"], None),
                               (o["home"], l["other_label"], l["other"])])
    return fr_typo(html) if lang == "fr" else html


PRIVACY_ALTERNATES = [("fr", "/confidentialite"), ("en", "/en/privacy"), ("x-default", "/confidentialite")]


def privacy_fr():
    """Page déclarée dans la Play Console : les deux versions de la politique, habillage en français."""
    fr = (CONTENT / "confidentialite-fr.html").read_text(encoding="utf-8").rstrip("\n")
    en = (CONTENT / "confidentialite-en.html").read_text(encoding="utf-8").rstrip("\n")
    l = L["fr"]
    html = head("fr", "Rhynote · Politique de confidentialité",
                "Politique de confidentialité de Rhynote, carnet de textes hors-ligne pour rappeurs, chanteurs et "
                "auteurs : aucune donnée collectée.", "/confidentialite", alternates=PRIVACY_ALTERNATES,
                og_desc="Rhynote ne collecte, ne transmet et ne vend aucune donnée. L'application n'a même pas "
                        "accès à Internet.")
    html += f"""
<header class="hero compact">
  {site_header("fr", [("/", l['home_word'])], "/en/privacy")}
  <div class="wrap page-title">
    <h1>Politique de confidentialité</h1>
    <p lang="en">Privacy policy</p>
  </div>
</header>

<main class="wrap narrow legal">
  <nav class="langnav" aria-label="Langue / Language">
    <a href="#francais" lang="fr">Français</a>
    <a href="#english" lang="en">English</a>
  </nav>

{fr}

{en}
</main>

"""
    html += site_footer("fr", [("/", l["home_word"], None), (f"mailto:{MAIL}", l["contact"], None),
                               ("/en/privacy", "English", "en")])
    return html


def privacy_en():
    en = (CONTENT / "confidentialite-en.html").read_text(encoding="utf-8").rstrip("\n")
    l = L["en"]
    html = head("en", "Rhynote · Privacy policy",
                "Privacy policy of Rhynote, the offline lyrics notebook for rappers, singers and writers: no data "
                "collected.", "/en/privacy", alternates=PRIVACY_ALTERNATES,
                og_desc="Rhynote does not collect, transmit or sell any data. The app does not even have Internet "
                        "access.")
    html += f"""
<header class="hero compact">
  {site_header("en", [("/en", l['home_word'])], "/confidentialite")}
  <div class="wrap page-title">
    <h1>Privacy policy</h1>
    <p>Rhynote</p>
  </div>
</header>

<main class="wrap narrow legal">
{en}
</main>

"""
    html += site_footer("en", [("/en", l["home_word"], None), (f"mailto:{MAIL}", l["contact"], None),
                               ("/confidentialite", "Français", "fr")])
    return html


def not_found():
    html = head("fr", "Rhynote · Page introuvable / Page not found", "Cette page n'existe pas.", "/404",
                noindex=True)
    html += """
<header class="hero compact">
  <div class="site-header">
    <div class="wrap">
      <a class="brand" href="/" aria-label="Rhynote, accueil"><img src="/icon.svg" alt="" width="40" height="40">Rhynote</a>
    </div>
  </div>
  <div class="wrap page-title">
    <h1>Page introuvable</h1>
    <p lang="en">Page not found</p>
  </div>
</header>

<main class="wrap notfound">
  <p class="big" aria-hidden="true">404</p>
  <p>Cette page n'existe pas, ou plus.<br><span lang="en">This page doesn't exist, or no longer does.</span></p>
  <div class="cta-row center">
    <a class="btn btn-solid" href="/">Accueil</a>
    <a class="btn btn-outline" href="/en" hreflang="en" lang="en">Home (English)</a>
  </div>
</main>
</body>
</html>
"""
    return fr_typo(html)


def main():
    (PUBLIC / "en").mkdir(exist_ok=True)
    out = {
        "index.html": home("fr"),
        "en/index.html": home("en"),
        "confidentialite.html": privacy_fr(),
        "en/privacy.html": privacy_en(),
        "404.html": not_found(),
        "wave.svg": WAVE_FILE,
    }
    for name, text in out.items():
        (PUBLIC / name).write_text(text, encoding="utf-8")
        print("écrit", "public/" + name)


if __name__ == "__main__":
    main()
