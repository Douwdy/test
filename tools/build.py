#!/usr/bin/env python3
"""Génère les pages HTML du site dans public/ à partir d'un seul gabarit.

    python3 tools/build.py

Sources :
- les textes des pages d'accueil (FR et EN) sont dans le dictionnaire T ci-dessous ;
- la politique de confidentialité est dans content/confidentialite-fr.html et
  content/confidentialite-en.html, recopiée telle quelle (aucune retouche typographique).

Fichiers produits : public/index.html, public/en/index.html, public/confidentialite.html,
public/en/privacy.html, public/404.html, public/wave.svg. Ne pas les modifier à la main.
"""
import math
import pathlib
import random

REPO = pathlib.Path(__file__).resolve().parent.parent
PUBLIC = REPO / "public"
CONTENT = REPO / "content"

SITE = "https://rhynote.yeeterie.org"
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


def wave_rects(n, w, h, seed, gap=0.4, cls=""):
    """Barres d'onde sonore (hauteurs pseudo-aléatoires, déterministes)."""
    rnd = random.Random(seed)
    bw = w / n
    attr = f' class="{cls}"' if cls else ""
    out = []
    for i in range(n):
        env = 0.35 + 0.65 * abs(math.sin(i / n * math.pi * 2.3 + seed))
        v = max(0.12, min(1, env * (0.55 + 0.45 * rnd.random())))
        bh = round(v * h, 1)
        rw = round(bw * (1 - gap), 2)
        out.append(f'<rect{attr} x="{round(i * bw + bw * gap / 2, 2)}" y="{round((h - bh) / 2, 1)}" '
                   f'width="{rw}" height="{bh}" rx="{round(rw / 2, 2)}"/>')
    return "".join(out)


# Petite onde animée du téléphone d'illustration (inline pour pouvoir l'animer en CSS).
WAVE_HERO = (f'<svg class="wave wave-hero" viewBox="0 0 220 44" preserveAspectRatio="none" aria-hidden="true">'
             f'{wave_rects(44, 220, 44, 3, cls="b")}</svg>')
# Grande onde décorative : fichier séparé, utilisé comme masque CSS (.band-wave, image de partage).
WAVE_FILE = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1200 220" preserveAspectRatio="none">'
             f'{wave_rects(120, 1200, 220, 7)}</svg>\n')

ICONS = {
    "pen": '<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/>',
    "layers": '<path d="m12 2 10 5-10 5L2 7z"/><path d="m2 12 10 5 10-5"/><path d="m2 17 10 5 10-5"/>',
    "speech": '<path d="M21 15a2 2 0 0 1-2 2H8l-5 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>',
    "mic": '<rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 11a7 7 0 0 0 14 0"/><path d="M12 18v4"/>',
    "music": '<path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/>',
    "save": '<path d="M12 3v12m0 0-4-4m4 4 4-4"/><path d="M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2"/>',
}
PLAY_ICON = ('<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M5 3.2v17.6a.8.8 0 0 0 '
             '1.2.7l14.4-8.8a.8.8 0 0 0 0-1.4L6.2 2.5A.8.8 0 0 0 5 3.2z"/></svg>')


def icon(name):
    return f'<span class="icon"><svg viewBox="0 0 24 24" aria-hidden="true">{ICONS[name]}</svg></span>'


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
        title="Rhynote · Le carnet de textes hors-ligne pour rappeurs, chanteurs et auteurs",
        desc="Rhynote, l'appli Android pour écrire tes textes, enregistrer des mémos vocaux, importer tes instrus "
             "et monter des maquettes. 100 % hors-ligne, aucune donnée collectée.",
        skip="Aller au contenu",
        ids=("fonctions", "maquettes", "confidentialite"),
        nav=("Fonctions", "Maquettes", "Confidentialité", "FAQ"),
        eyebrow="Carnet de textes hors-ligne pour Android",
        h1="Écris. Enregistre. <em>Garde tout chez toi.</em>",
        lede="Rhynote, c'est le carnet des rappeurs, chanteurs et auteurs : textes, mémos vocaux, instrus et "
             "maquettes réunis dans une seule appli. Sans compte, sans pub, sans Internet.",
        cta="Télécharger sur Google Play", cta2="Voir les fonctions",
        chips=("100 % hors-ligne", "Aucune donnée collectée", "Sans pub", "Sans compte"),
        ph_title="Nuit blanche", ph_chip="Rap", ph_meta="Brouillon · version 3",
        lyrics=("J'écris à l'encre de la nuit,", "le refrain vient sans bruit,", "trois heures, le café refroidit,",
                "mais la rime, elle, jamais ne fuit."),
        lyrics_dim=("[Refrain]", "Encore une ligne avant l'aube…"),
        ph_label="Maquette", ph_time="01:24",
        f_kicker="Fonctions", f_title="Tout ce qu'il faut pour écrire.",
        f_sub="De la première ligne à la maquette, sans quitter ton téléphone.",
        feats=(
            ("pen", "Écris et classe",
             "Paroles, poèmes, couplets : note une idée en quelques secondes et retrouve-la grâce aux catégories."),
            ("layers", "Garde chaque version",
             "Réécris sans crainte : Rhynote conserve les versions de tes textes, l'ancienne n'est jamais perdue."),
            ("speech", "Tes prononciations",
             "Indique comment tu prononces les mots à ta façon : noms propres, argot, mots inventés."),
            ("mic", "Mémos vocaux",
             "Capte une mélodie ou un flow dès qu'il te vient. L'enregistrement reste sur ton téléphone."),
            ("music", "Tes instrus",
             "Importe tes instrus en fichier audio, ou rattache à un texte le lien d'une instru publiée sur YouTube."),
            ("save", "Sauvegarde",
             "Depuis l'écran « Sauvegarde », crée un fichier de sauvegarde et range-le à l'endroit de ton choix."),
        ),
        m_kicker="Maquettes", m_title="Du texte à la maquette, dans la même appli.",
        m_sub="Enregistre tes prises, monte ta maquette et exporte-la en fichier audio.",
        steps=(("Enregistre", "Fais tes prises avec le micro de ton téléphone."),
               ("Monte", "Assemble tes prises pour construire ta maquette."),
               ("Exporte", "Enregistre-la où tu veux ou partage-la avec l'appli de ton choix. C'est toi qui décides.")),
        p_kicker="Confidentialité", p_title="Tes textes restent à toi.",
        p_sub="Rhynote ne collecte, ne transmet et ne vend aucune donnée. L'appli ne demande même pas "
              "l'accès à Internet : elle n'envoie jamais rien d'elle-même.",
        zero="donnée collectée",
        checks=("Aucun accès à Internet demandé", "Aucun compte utilisateur", "Ni publicité, ni mesure d'audience",
                "Micro : seulement quand tu enregistres"),
        p_link="Lire la politique de confidentialité",
        s_kicker="Rhynote Studio", s_title="Un achat unique, pas d'abonnement.",
        s_text="Rhynote Studio se débloque une fois pour toutes, via Google Play. Le paiement passe entièrement "
               "par Google : Rhynote ne reçoit aucune donnée bancaire.",
        s_cta="Voir sur Google Play",
        faq_kicker="FAQ", faq_title="Questions fréquentes",
        faq=(
            ("Rhynote a-t-il besoin d'Internet ?",
             "Non. L'appli ne demande même pas l'accès à Internet : tout fonctionne hors-ligne."),
            ("Où sont stockés mes textes et mes enregistrements ?",
             "Uniquement dans le stockage privé de l'appli, sur ton téléphone. Ils sont supprimés si tu désinstalles "
             "Rhynote : fais une sauvegarde avant."),
            ("Comment sauvegarder mes textes ?",
             "Depuis l'écran « Sauvegarde », tu crées un fichier que tu enregistres toi-même, où tu veux. Si la "
             "sauvegarde Google de ton téléphone est activée, Android peut aussi copier les données de l'appli dans "
             "ton compte Google : c'est une fonction du système, que tu contrôles dans les réglages d'Android."),
            ("Puis-je utiliser une instru YouTube ?",
             "Oui : tu peux rattacher à un texte le lien d'une instru publiée sur YouTube. Rhynote enregistre "
             "seulement ce lien et l'ouvre dans l'appli YouTube ou ton navigateur quand tu le demandes ; il ne "
             "télécharge pas la vidéo. Tu peux aussi importer tes instrus en fichier audio."),
            ("Mes maquettes sont-elles envoyées quelque part ?",
             "Non. Une maquette exportée ne quitte ton téléphone que si tu choisis « Enregistrer sous… » ou "
             "« Partager ». Rhynote n'envoie rien de lui-même."),
            ("Sur quels appareils fonctionne Rhynote ?", "Sur les téléphones Android, via Google Play."),
            ("Comment vous contacter ?", f'Par e-mail : <a href="mailto:{MAIL}">{MAIL}</a>.'),
        ),
        final_title="Ton prochain couplet t'attend.",
        final_text="Télécharge Rhynote et garde tes textes au même endroit, sur ton téléphone.",
    ),
    "en": dict(
        title="Rhynote · The offline lyrics notebook for rappers, singers and writers",
        desc="Rhynote, the Android app to write your lyrics, record voice memos, import your beats and build demos. "
             "100% offline, no data collected.",
        skip="Skip to content",
        ids=("features", "demos", "privacy"),
        nav=("Features", "Demos", "Privacy", "FAQ"),
        eyebrow="Offline lyrics notebook for Android",
        h1="Write. Record. <em>Keep it all on your phone.</em>",
        lede="Rhynote is the notebook for rappers, singers and writers: lyrics, voice memos, beats and demos in a "
             "single app. No account, no ads, no Internet.",
        cta="Get it on Google Play", cta2="See features",
        chips=("100% offline", "No data collected", "No ads", "No account"),
        ph_title="Sleepless", ph_chip="Rap", ph_meta="Draft · version 3",
        lyrics=("I write in the ink of the night,", "the hook comes in without a sound,", "three a.m., the coffee's cold,",
                "but the rhyme is always around."),
        lyrics_dim=("[Chorus]", "One more line before dawn…"),
        ph_label="Demo", ph_time="01:24",
        f_kicker="Features", f_title="Everything you need to write.",
        f_sub="From the first line to the demo, without leaving your phone.",
        feats=(
            ("pen", "Write and sort",
             "Lyrics, poems, verses: jot down an idea in seconds and find it again with categories."),
            ("layers", "Keep every version",
             "Rewrite without worry: Rhynote keeps the versions of your texts, so the old one is never lost."),
            ("speech", "Your pronunciations",
             "Tell the app how you pronounce words your own way: names, slang, made-up words."),
            ("mic", "Voice memos",
             "Catch a melody or a flow the moment it comes. The recording stays on your phone."),
            ("music", "Your beats",
             "Import your beats as audio files, or attach the link of a beat published on YouTube to a text."),
            ("save", "Backup",
             "From the “Sauvegarde” (Backup) screen, create a backup file and store it wherever you choose."),
        ),
        m_kicker="Demos", m_title="From lyrics to demo, in the same app.",
        m_sub="Record your takes, edit your demo and export it as an audio file.",
        steps=(("Record", "Lay down your takes with your phone's microphone."),
               ("Edit", "Put your takes together to build your demo."),
               ("Export", "Save it wherever you want or share it with the app of your choice. You decide.")),
        p_kicker="Privacy", p_title="Your texts stay yours.",
        p_sub="Rhynote does not collect, transmit or sell any data. The app does not even request Internet "
              "access: it never sends anything by itself.",
        zero="data collected",
        checks=("No Internet access requested", "No user account", "No ads, no analytics",
                "Microphone: only when you record"),
        p_link="Read the privacy policy",
        s_kicker="Rhynote Studio", s_title="One purchase, no subscription.",
        s_text="Rhynote Studio is unlocked once and for all, through Google Play. Payment is handled entirely by "
               "Google: Rhynote receives no payment data.",
        s_cta="See on Google Play",
        faq_kicker="FAQ", faq_title="Frequently asked questions",
        faq=(
            ("Does Rhynote need Internet?",
             "No. The app does not even request Internet access: everything works offline."),
            ("Where are my texts and recordings stored?",
             "Only in the app's private storage, on your phone. They are deleted if you uninstall Rhynote, so make "
             "a backup first."),
            ("How do I back up my texts?",
             "From the “Sauvegarde” (Backup) screen, you create a file that you save yourself, wherever you want. "
             "If Google backup is enabled on your phone, Android may also copy the app's data to your Google "
             "account: this is a system feature that you control in Android settings."),
            ("Can I use a beat from YouTube?",
             "Yes: you can attach the link of a beat published on YouTube to a text. Rhynote only stores the link "
             "and opens it in the YouTube app or your browser when you ask; it does not download the video. You can "
             "also import your beats as audio files."),
            ("Are my demos sent anywhere?",
             "No. An exported demo only leaves your phone if you choose “Save as…” or “Share”. Rhynote never sends "
             "anything by itself."),
            ("Which devices does Rhynote run on?", "Android phones, through Google Play."),
            ("How can I contact you?", f'By e-mail: <a href="mailto:{MAIL}">{MAIL}</a>.'),
        ),
        final_title="Your next verse is waiting.",
        final_text="Get Rhynote and keep all your texts in one place, on your phone.",
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
    lyr = "".join(f"<span>{x}</span>" for x in t["lyrics"])
    lyr += "".join(f'<span class="dim{" gap" if i == 0 else ""}">{x}</span>' for i, x in enumerate(t["lyrics_dim"]))
    feats = "".join(f'<li class="card">{icon(i)}<h3>{h}</h3><p>{p}</p></li>' for i, h, p in t["feats"])
    steps = "".join(f"<li><h3>{h}</h3><p>{p}</p></li>" for h, p in t["steps"])
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

    <div class="phone-wrap" aria-hidden="true">
      <div class="phone">
        <div class="screen">
          <div class="ph-top"><span class="ph-title">{t['ph_title']}</span><span class="ph-chip">{t['ph_chip']}</span></div>
          <div class="ph-meta">{t['ph_meta']}</div>
          <p class="ph-lyrics">{lyr}</p>
          <div class="ph-bottom">
            <div class="ph-label"><span>{t['ph_label']}</span><span>{t['ph_time']}</span></div>
            {WAVE_HERO}
            <div class="ph-controls"><span class="rec-dot"></span><span class="ph-time">{t['ph_time']}</span><span class="ph-bar"></span></div>
          </div>
        </div>
      </div>
    </div>
  </div>
</header>

<main id="main">
  <section id="{ids[0]}">
    <div class="wrap">
      <div class="section-head">
        <p class="kicker">{t['f_kicker']}</p>
        <h2 class="title">{t['f_title']}</h2>
        <p class="sub">{t['f_sub']}</p>
      </div>
      <ul class="grid">{feats}</ul>
    </div>
  </section>

  <section id="{ids[1]}" class="band">
    <div class="band-wave" aria-hidden="true"></div>
    <div class="wrap">
      <div class="section-head">
        <p class="kicker">{t['m_kicker']}</p>
        <h2 class="title">{t['m_title']}</h2>
        <p class="sub">{t['m_sub']}</p>
      </div>
      <ol class="steps">{steps}</ol>
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
          <p class="kicker">{t['s_kicker']}</p>
          <h2 class="title">{t['s_title']}</h2>
          <p>{t['s_text']}</p>
        </div>
        <a class="btn btn-solid" href="{PLAY}" rel="noopener">{PLAY_ICON}{t['s_cta']}</a>
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
