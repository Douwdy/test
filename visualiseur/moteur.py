"""Moteur du visualiseur : métadonnées, analyse du rythme, rendu des images et encodage avec ffmpeg.

Le principe : ffmpeg décode l'audio (n'importe quel format qu'il sait lire) en échantillons bruts,
numpy en tire pour chaque image de la vidéo le spectre, l'énergie des basses et les attaques (kicks),
Pillow dessine les images, et ffmpeg les encode en MP4 avec la piste audio d'origine.
"""

import io
import json
import math
import multiprocessing
import os
import shutil
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont, ImageOps

SR = 22050  # fréquence d'échantillonnage utilisée pour l'analyse (pas pour la vidéo finale)
STYLES = ("barres", "cercle", "onde")
QUALITES = {"rapide": ("veryfast", 20), "normale": ("medium", 18), "haute": ("slow", 16)}
COULEUR_DEFAUT = (120, 170, 255)
NO_WINDOW = 0x08000000 if os.name == "nt" else 0  # CREATE_NO_WINDOW : pas de console ffmpeg sous Windows


class Annule(Exception):
    pass


# --------------------------------------------------------------------------- ffmpeg

def _dossiers_app():
    dossiers = []
    if getattr(sys, "frozen", False):
        dossiers.append(os.path.dirname(sys.executable))
        if hasattr(sys, "_MEIPASS"):
            dossiers.append(sys._MEIPASS)
    dossiers.append(os.path.dirname(os.path.abspath(__file__)))
    return dossiers


def trouver_outil(nom):
    """Cherche ffmpeg/ffprobe : variable FFMPEG_PATH/FFPROBE_PATH, à côté du programme, puis dans le PATH."""
    exe = nom + (".exe" if os.name == "nt" else "")
    candidats = []
    env = os.environ.get(nom.upper() + "_PATH")
    if env:
        candidats.append(env)
    for d in _dossiers_app():
        candidats += [os.path.join(d, exe), os.path.join(d, "ffmpeg", exe), os.path.join(d, "ffmpeg", "bin", exe)]
    for c in candidats:
        if os.path.isfile(c):
            return c
    return shutil.which(nom)


def _outil(nom):
    chemin = trouver_outil(nom)
    if not chemin:
        raise RuntimeError(
            f"{nom} introuvable. Placez {nom}.exe à côté du programme (ou dans un sous-dossier « ffmpeg »), "
            "ou ajoutez ffmpeg au PATH. Téléchargement : https://www.gyan.dev/ffmpeg/builds/"
        )
    return chemin


def _lancer(args):
    return subprocess.run(args, capture_output=True, creationflags=NO_WINDOW)


# --------------------------------------------------------------------------- métadonnées

@dataclass
class Infos:
    titre: str = ""
    artiste: str = ""
    album: str = ""
    annee: str = ""
    duree: float = 0.0
    pochette: Image.Image | None = None


def lire_infos(chemin):
    """Lit titre, artiste, album, année, durée et pochette intégrée (ou cover.jpg/folder.jpg du dossier)."""
    r = _lancer([_outil("ffprobe"), "-v", "error", "-of", "json", "-show_format", "-show_streams", chemin])
    if r.returncode:
        raise RuntimeError("Fichier illisible par ffprobe :\n" + r.stderr.decode("utf-8", "replace").strip())
    data = json.loads(r.stdout.decode("utf-8", "replace") or "{}")
    flux = data.get("streams", [])
    if not any(s.get("codec_type") == "audio" for s in flux):
        raise RuntimeError("Ce fichier ne contient pas de piste audio.")

    tags = {}
    for s in flux:  # Ogg/Opus rangent leurs tags sur le flux audio
        if s.get("codec_type") == "audio":
            for k, v in (s.get("tags") or {}).items():
                tags.setdefault(k.lower(), v)
    for k, v in (data.get("format", {}).get("tags") or {}).items():
        tags[k.lower()] = v

    infos = Infos(
        titre=tags.get("title", "").strip(),
        artiste=(tags.get("artist") or tags.get("album_artist") or "").strip(),
        album=tags.get("album", "").strip(),
        annee=(tags.get("date") or tags.get("year") or "").strip()[:4],
    )
    try:
        infos.duree = float(data.get("format", {}).get("duration", 0))
    except ValueError:
        pass

    if not infos.titre:  # pas de tag : on se rabat sur le nom du fichier, « Artiste - Titre » si possible
        nom = os.path.splitext(os.path.basename(chemin))[0]
        if " - " in nom and not infos.artiste:
            infos.artiste, infos.titre = (p.strip() for p in nom.split(" - ", 1))
        else:
            infos.titre = nom

    if any(s.get("codec_type") == "video" for s in flux):
        r = _lancer([_outil("ffmpeg"), "-v", "error", "-i", chemin, "-map", "0:v:0", "-frames:v", "1",
                     "-f", "image2pipe", "-c:v", "png", "-"])
        if r.returncode == 0 and r.stdout:
            try:
                infos.pochette = Image.open(io.BytesIO(r.stdout)).convert("RGB")
            except OSError:
                pass
    if infos.pochette is None:
        infos.pochette = _pochette_du_dossier(os.path.dirname(os.path.abspath(chemin)))
    return infos


def _pochette_du_dossier(dossier):
    try:
        fichiers = {f.lower(): f for f in os.listdir(dossier)}
    except OSError:
        return None
    for nom in ("cover", "folder", "front", "albumart", "album"):
        for ext in (".jpg", ".jpeg", ".png", ".webp"):
            if nom + ext in fichiers:
                try:
                    return Image.open(os.path.join(dossier, fichiers[nom + ext])).convert("RGB")
                except OSError:
                    pass
    return None


def ouvrir_image(chemin):
    return Image.open(chemin).convert("RGB")


# --------------------------------------------------------------------------- analyse audio

@dataclass
class Analyse:
    fps: int
    duree: float
    bandes: np.ndarray     # (n, nb) spectre lissé, 0..1
    basse: np.ndarray      # (n,) énergie des basses lissée, 0..1
    impulsion: np.ndarray  # (n,) enveloppe des attaques détectées (kick), 0..1
    energie: np.ndarray    # (n,) volume global lissé, 0..1
    pulse: np.ndarray      # (n,) mélange basse + impulsion : ce qui fait « battre » l'image
    onde: np.ndarray       # (n, 256) forme d'onde autour de chaque image, -1..1

    @property
    def n(self):
        return len(self.pulse)


def decoder_audio(chemin, debut=0.0, duree=None):
    args = [_outil("ffmpeg"), "-v", "error"]
    if debut:
        args += ["-ss", f"{debut:.3f}"]
    args += ["-i", chemin]
    if duree:
        args += ["-t", f"{duree:.3f}"]
    args += ["-vn", "-ac", "1", "-ar", str(SR), "-f", "f32le", "-"]
    r = _lancer(args)
    if r.returncode:
        raise RuntimeError("Décodage audio impossible :\n" + r.stderr.decode("utf-8", "replace").strip())
    echantillons = np.frombuffer(r.stdout, dtype=np.float32)
    if len(echantillons) < SR // 10:
        raise RuntimeError("Le fichier ne contient pas (assez) d'audio sur l'extrait demandé.")
    return echantillons


def _coef(fps, tau):
    """Coefficient de lissage par image pour une constante de temps tau (secondes)."""
    return 1.0 - math.exp(-1.0 / (fps * tau))


def _lisser(x, fps, montee, descente):
    """Suiveur d'enveloppe : monte vite, redescend doucement (comme les VU-mètres)."""
    km, kd = _coef(fps, montee), _coef(fps, descente)
    out = np.empty_like(x)
    cur = x[0].copy() if x.ndim > 1 else x[0]
    for i in range(len(x)):
        v = x[i]
        cur = cur + (v - cur) * np.where(v > cur, km, kd)
        out[i] = cur
    return out


def _normaliser_db(db, plage=42.0):
    haut = np.percentile(db, 99, axis=0)
    haut = np.maximum(haut, np.percentile(db, 99) - 18)  # une bande quasi muette ne doit pas être gonflée
    return np.clip((db - (haut - plage)) / plage, 0, 1)


def analyser(echantillons, fps, nb_bandes=64):
    n = max(1, math.ceil(len(echantillons) / SR * fps))
    fen = 2048
    pad = np.pad(echantillons, (fen, fen))
    hop = SR / fps
    centres = (np.arange(n) * hop + hop / 2).astype(np.int64) + fen
    hann = np.hanning(fen).astype(np.float32)
    freqs = np.fft.rfftfreq(fen, 1 / SR)

    # matrice qui regroupe les raies FFT en bandes logarithmiques (35 Hz → 11 kHz)
    bords = np.geomspace(35, 11000, nb_bandes + 1)
    M = np.zeros((len(freqs), nb_bandes), np.float32)
    for b in range(nb_bandes):
        sel = (freqs >= bords[b]) & (freqs < bords[b + 1])
        if sel.any():
            M[sel, b] = 1.0 / sel.sum()
        else:
            M[np.argmin(np.abs(freqs - math.sqrt(bords[b] * bords[b + 1]))), b] = 1.0
    centres_bandes = np.sqrt(bords[:-1] * bords[1:])

    spec = np.empty((n, nb_bandes), np.float32)
    decal = np.arange(-fen // 2, fen // 2)
    for a in range(0, n, 512):
        idx = centres[a:a + 512]
        seg = pad[idx[:, None] + decal] * hann
        spec[a:a + len(idx)] = np.abs(np.fft.rfft(seg, axis=1)) @ M

    db = 20 * np.log10(spec + 1e-7)
    brut = _normaliser_db(db)
    bandes = _lisser(brut ** 1.4, fps, 0.02, 0.16)

    graves = centres_bandes < 150
    basse_brute = _normaliser_db(20 * np.log10(spec[:, graves].mean(axis=1) + 1e-7)[:, None], 30)[:, 0]
    basse = _lisser(basse_brute ** 2, fps, 0.015, 0.12)

    # attaques : flux spectral positif dans le bas du spectre, seuil adaptatif, pics espacés d'au moins 120 ms
    bas = centres_bandes < 250
    flux = np.maximum(0, np.diff(brut[:, bas], axis=0, prepend=brut[:1, bas])).sum(axis=1)
    w = max(3, int(fps * 0.5))
    noyau = np.ones(w) / w
    moy = np.convolve(flux, noyau, mode="same")
    ecart = np.sqrt(np.maximum(0, np.convolve(flux ** 2, noyau, mode="same") - moy ** 2))
    seuil = moy + 1.2 * ecart + 1e-6
    ref = np.percentile(flux, 97) + 1e-6
    impulsion = np.zeros(n, np.float32)
    kd = math.exp(-1.0 / (fps * 0.18))
    cur, dernier, ecart_min = 0.0, -10 ** 9, max(1, int(fps * 0.12))
    for i in range(n):
        cur *= kd
        f = flux[i]
        if (f > seuil[i] and i - dernier >= ecart_min
                and f >= flux[max(0, i - 1)] and f >= flux[min(n - 1, i + 1)]):
            cur = max(cur, min(1.0, 0.35 + 0.65 * f / ref))
            dernier = i
        impulsion[i] = cur

    energie = _lisser(brut.mean(axis=1), fps, 0.05, 0.3)
    pulse = np.clip(0.6 * impulsion + 0.5 * basse, 0, 1).astype(np.float32)

    # forme d'onde : 2048 échantillons autour de chaque image, moyennés par 8 (ça lisse les aigus)
    crete = np.percentile(np.abs(echantillons), 99.5) + 1e-6
    onde = np.empty((n, 256), np.float16)
    decal = np.arange(-1024, 1024)
    for a in range(0, n, 512):
        idx = centres[a:a + 512]
        seg = pad[idx[:, None] + decal].reshape(len(idx), 256, 8).mean(axis=2)
        onde[a:a + len(idx)] = np.clip(seg / crete, -1, 1)

    return Analyse(fps=fps, duree=len(echantillons) / SR, bandes=bandes.astype(np.float32),
                   basse=basse.astype(np.float32), impulsion=impulsion, energie=energie.astype(np.float32),
                   pulse=pulse, onde=onde)


# --------------------------------------------------------------------------- couleurs et polices

def couleur_accent(img):
    """Couleur vive dominante de la pochette (pour les barres, les particules…)."""
    if img is None:
        return COULEUR_DEFAUT
    a = np.asarray(img.convert("RGB").resize((64, 64)), np.float32).reshape(-1, 3) / 255
    mx, mn = a.max(axis=1), a.min(axis=1)
    sat = (mx - mn) / (mx + 1e-6)
    score = sat ** 2 * mx * (mx > 0.2)
    if score.sum() < 2:  # pochette en noir et blanc
        return COULEUR_DEFAUT
    r, g, b = a[:, 0], a[:, 1], a[:, 2]
    teinte = (np.degrees(np.arctan2(math.sqrt(3) * (g - b), 2 * r - g - b)) % 360).astype(int) // 15
    poids = np.bincount(teinte, weights=score, minlength=24)
    sel = teinte == np.argmax(poids)
    c = (a[sel] * score[sel, None]).sum(axis=0) / score[sel].sum()
    # on la rend lumineuse et saturée pour qu'elle ressorte sur le fond sombre
    mx = c.max() + 1e-6
    c = c / mx
    c = 1 - (1 - c) * max(1.0, 0.75 / max(1e-6, 1 - c.min()))
    return tuple(int(v) for v in np.clip(c * 255, 0, 255))


def _melange(c1, c2, t):
    return tuple(int(a + (b - a) * t) for a, b in zip(c1, c2))


POLICES = {
    "gras": ["segoeuib.ttf", "arialbd.ttf", "Inter-Bold.otf", "DejaVuSans-Bold.ttf", "FreeSansBold.ttf"],
    "normal": ["segoeui.ttf", "arial.ttf", "Inter-Regular.otf", "DejaVuSans.ttf", "FreeSans.ttf"],
    "cjk": ["YuGothB.ttc", "msgothic.ttc", "msyhbd.ttc", "msyh.ttc", "malgunbd.ttf", "NotoSansCJK-Bold.ttc",
            "NotoSansCJK-Regular.ttc"],
}


def _police(role, taille, texte=""):
    noms = POLICES[role]
    if any(ord(c) >= 0x2E80 for c in texte):  # japonais, chinois, coréen
        noms = POLICES["cjk"] + noms
    for nom in noms:
        try:
            return ImageFont.truetype(nom, taille)
        except OSError:
            continue
    return ImageFont.load_default(taille)


def _police_ajustee(texte, role, taille, largeur_max):
    taille_min = max(10, int(taille * 0.6))
    while taille > taille_min:
        f = _police(role, taille, texte)
        if f.getlength(texte) <= largeur_max:
            return f, texte
        taille = int(taille * 0.93)
    f = _police(role, taille_min, texte)
    while texte and f.getlength(texte + "…") > largeur_max:
        texte = texte[:-1]
    return f, (texte.rstrip() + "…") if texte else ""


# --------------------------------------------------------------------------- rendu

@dataclass
class Options:
    largeur: int = 1920
    hauteur: int = 1080
    fps: int = 30
    style: str = "barres"
    titre: str = ""
    artiste: str = ""
    album: str = ""
    pochette: Image.Image | None = None
    couleur: tuple | None = None  # None : tirée de la pochette
    particules: bool = True
    progression: bool = True
    qualite: str = "normale"

    def __post_init__(self):
        self.largeur -= self.largeur % 2  # yuv420p exige des dimensions paires
        self.hauteur -= self.hauteur % 2
        if self.style not in STYLES:
            raise ValueError(f"Style inconnu : {self.style} (choix : {', '.join(STYLES)})")

    @property
    def nb_bandes(self):
        if self.style == "cercle":
            return 60
        return 64 if self.largeur >= self.hauteur * 1.3 else 40


def _masque(taille, rond, rayon=0.06):
    s = taille * 4
    m = Image.new("L", (s, s), 0)
    d = ImageDraw.Draw(m)
    if rond:
        d.ellipse((0, 0, s - 1, s - 1), fill=255)
    else:
        d.rounded_rectangle((0, 0, s - 1, s - 1), radius=int(s * rayon), fill=255)
    return m.resize((taille, taille), Image.LANCZOS)


def _rect_arrondi(d, boite, rayon, coul):
    # rayon borné : les Pillow anciens (≤ 10.2) plantent si le rectangle est moins haut que 2 × rayon + 2
    rayon = min(rayon, (boite[3] - boite[1] - 2) / 2, (boite[2] - boite[0] - 2) / 2)
    if rayon >= 1:
        d.rounded_rectangle(boite, radius=rayon, fill=coul)
    elif boite[3] - boite[1] >= 1:
        d.rectangle(boite, fill=coul)


def _pochette_par_defaut(taille, couleur, titre):
    """Pochette générée quand le fichier n'en a pas et que l'utilisateur n'en a pas choisi."""
    y = np.linspace(0, 1, taille)[:, None]
    x = np.linspace(0, 1, taille)[None, :]
    t = np.clip((x + y) / 2, 0, 1)[..., None]
    c1 = np.array(couleur, np.float32)
    c2 = c1 * 0.25
    img = Image.fromarray((c1 * (1 - t) + c2 * t).astype(np.uint8))
    lettre = (titre.strip()[:1] or "♪").upper()
    f = _police("gras", int(taille * 0.5), lettre)
    d = ImageDraw.Draw(img)
    d.text((taille / 2, taille / 2), lettre, font=f, anchor="mm", fill=(255, 255, 255))
    return img


class Rendu:
    def __init__(self, opts: Options, analyse: Analyse):
        self.o, self.a = opts, analyse
        W, H = opts.largeur, opts.hauteur
        self.W, self.H = W, H
        m = self.m = min(W, H)
        self.paysage = W >= H * 1.3
        self.accent = tuple(opts.couleur) if opts.couleur else couleur_accent(opts.pochette)
        self.clair = _melange(self.accent, (255, 255, 255), 0.55)
        self.g = max(1, round(m / 360))  # facteur de réduction du calque de halo
        self._cache_fond, self._cache_poch = {}, {}

        # --- mise en page
        st = opts.style
        if st == "cercle":
            self.S = int(m * 0.36)
            self.cx, self.cy = W // 2, int(H * (0.44 if self.paysage else 0.42))
            self.r0 = self.S / 2 + m * 0.02
            self.long_max = m * 0.14
            texte_y = self.cy + self.r0 + self.long_max + m * 0.05
            self.texte = ("centre", W // 2, texte_y, W * 0.86)
        elif self.paysage:
            self.S = int(H * 0.42)
            x0 = int(W * 0.1)
            self.cx, self.cy = x0 + self.S // 2, int(H * 0.38)
            tx = x0 + self.S + W * 0.035
            self.texte = ("gauche", tx, self.cy, W * 0.92 - tx)
        else:
            self.S = int(min(W * 0.62, H * 0.42))
            self.cx, self.cy = W // 2, int(H * (0.3 if H < W * 1.3 else 0.33))
            self.texte = ("centre", W // 2, self.cy + self.S / 2 + m * 0.06, W * 0.86)
        if st != "cercle":
            self.x0, self.x1 = W * (0.05 if self.paysage else 0.06), W * (0.95 if self.paysage else 0.94)
            self.base = H * (0.86 if self.paysage else 0.87)
            self.haut_max = H * (0.22 if self.paysage else 0.17)
            pad = m * 0.04
            zone = (self.x0 - pad, self.base - self.haut_max * 1.1 - pad, self.x1 + pad, self.base + pad)
        else:
            r = self.r0 * 1.08 + self.long_max + m * 0.04
            zone = (self.cx - r, self.cy - r, self.cx + r, self.cy + r)
        # le halo n'est calculé que dans cette zone (le flou plein écran coûte trop cher)
        g = self.g
        zx0, zy0 = max(0, int(zone[0]) // g * g), max(0, int(zone[1]) // g * g)
        zx1, zy1 = min(W, int(zone[2])) // g * g, min(H, int(zone[3])) // g * g
        self.zone = (zx0, zy0, zx1, zy1)

        # --- fond : pochette floutée et assombrie, ou dégradé
        if opts.pochette is not None:
            petit = ImageOps.fit(opts.pochette, (max(16, W // 8), max(16, H // 8)), Image.LANCZOS)
            fond = petit.filter(ImageFilter.GaussianBlur(max(2, m / 8 * 0.05))).resize((W, H), Image.BICUBIC)
            fond = np.asarray(fond, np.float32) * 0.34
        else:
            yy = np.linspace(0, 1, H)[:, None, None]
            fond = np.array(self.accent, np.float32) * (0.22 * (1 - yy)) + 8
            fond = np.broadcast_to(fond, (H, W, 3)).copy()
        yy, xx = np.ogrid[-1:1:H * 1j, -1:1:W * 1j]
        vignette = np.clip(1.15 - 0.45 * (xx ** 2 + yy ** 2), 0.35, 1)[..., None]
        self.fond = fond * vignette

        # --- pochette (carré arrondi ou disque) et son ombre
        rond = st == "cercle"
        grand = int(self.S * 1.12)
        src = opts.pochette or _pochette_par_defaut(grand, self.accent, opts.titre)
        poch = ImageOps.fit(src, (grand, grand), Image.LANCZOS).convert("RGBA")
        poch.putalpha(_masque(grand, rond))
        self.poch = poch
        marge = int(self.S * 0.12)
        ombre = Image.new("L", (self.S + 2 * marge, self.S + 2 * marge), 0)
        ombre.paste(_masque(self.S, rond), (marge, marge))
        ombre = ombre.filter(ImageFilter.GaussianBlur(marge / 2.5)).point(lambda v: int(v * 0.7))
        self.ombre = Image.merge("RGBA", (*[Image.new("L", ombre.size, 0)] * 3, ombre))

        self.bloc_texte = self._bloc_texte()

        # --- particules : trajectoires déterministes (chaque image se calcule seule, en parallèle)
        rng = np.random.default_rng(7)
        np_ = int(110 * (W * H) / (1920 * 1080) ** 1) if opts.particules else 0
        np_ = min(np_, 260)
        self.p_angle = rng.uniform(0, 2 * np.pi, np_)
        self.p_vit = rng.uniform(0.04, 0.16, np_) * m
        self.p_vie = rng.uniform(2.5, 6.0, np_)
        self.p_phase = rng.uniform(0, 6.0, np_)
        self.p_taille = rng.uniform(1.2, 3.6, np_) * m / 1080
        self.cumul = np.concatenate([[0], np.cumsum(analyse.pulse) / analyse.fps])

    # ---------------------------------------------------------------- éléments fixes

    def _bloc_texte(self):
        o, m = self.o, self.m
        align, _, _, lmax = self.texte
        lignes = [(o.titre, "gras", 0.062, (255, 255, 255)),
                  (o.artiste, "normal", 0.038, (225, 225, 225)),
                  (o.album, "normal", 0.028, (170, 170, 170))]
        rendu = []
        for texte, role, rel, coul in lignes:
            texte = (texte or "").strip()
            if texte:
                f, texte = _police_ajustee(texte, role, int(m * rel), lmax)
                rendu.append((texte, f, coul))
        if not rendu:
            return None
        esp = int(m * 0.012)
        hauteurs = [f.getbbox("Hgjpq")[3] - f.getbbox("H")[1] for _, f, _ in rendu]
        largeur = int(max(f.getlength(t) for t, f, _ in rendu)) + 4
        marge = int(m * 0.02)
        hauteur = sum(hauteurs) + esp * (len(rendu) - 1)
        img = Image.new("RGBA", (largeur + 2 * marge, hauteur + 2 * marge), (0, 0, 0, 0))
        ombre = Image.new("L", img.size, 0)
        dt, do = ImageDraw.Draw(img), ImageDraw.Draw(ombre)
        y = marge
        for (texte, f, coul), h in zip(rendu, hauteurs):
            x = marge if align == "gauche" else marge + largeur / 2
            anc = "la" if align == "gauche" else "ma"
            y_txt = y - f.getbbox("H")[1]
            do.text((x, y_txt + m * 0.003), texte, font=f, anchor=anc, fill=200)
            dt.text((x, y_txt), texte, font=f, anchor=anc, fill=coul + (255,))
            y += h + esp
        ombre = ombre.filter(ImageFilter.GaussianBlur(m * 0.006))
        fond = Image.merge("RGBA", (*[Image.new("L", img.size, 0)] * 3, ombre))
        return Image.alpha_composite(fond, img), marge

    def _fond(self, pulse):
        niveau = int(round(pulse * 12))
        img = self._cache_fond.get(niveau)
        if img is None:
            f = 0.85 + 0.35 * niveau / 12
            img = Image.fromarray(np.clip(self.fond * f, 0, 255).astype(np.uint8))
            self._cache_fond[niveau] = img
        return img.copy()

    def _pochette(self, pulse):
        taille = int(self.S * (1 + 0.07 * pulse)) // 2 * 2
        img = self._cache_poch.get(taille)
        if img is None:
            img = self._cache_poch[taille] = self.poch.resize((taille, taille), Image.BILINEAR)
        return img

    # ---------------------------------------------------------------- dessin d'une image

    def _h(self, x, y):
        """Coordonnées plein écran → calque de halo (réduit et décalé sur sa zone)."""
        return (x - self.zone[0]) / self.g, (y - self.zone[1]) / self.g

    def _barres(self, d_net, d_halo, i):
        v = self.a.bandes[i]
        vals = np.concatenate([v[::-1], v])  # graves au centre, symétrique
        k = len(vals)
        pas = (self.x1 - self.x0) / k
        larg = max(2.0, pas * 0.62)
        rayon = larg / 2
        coul_reflet = _melange((10, 10, 14), self.accent, 0.3)
        for j, val in enumerate(vals):
            x = self.x0 + (j + 0.5) * pas
            h = max(larg, float(val) * self.haut_max)
            coul = _melange(self.accent, (255, 255, 255), min(1, float(val) * 0.7))
            _rect_arrondi(d_net, (x - larg / 2, self.base - h, x + larg / 2, self.base), rayon, coul)
            _rect_arrondi(d_net, (x - larg / 2, self.base + larg, x + larg / 2, self.base + larg + h * 0.3),
                          rayon, coul_reflet)
            d_halo.rectangle((*self._h(x - larg / 2, self.base - h), *self._h(x + larg / 2, self.base)),
                             fill=self.accent)

    def _cercle(self, d_net, d_halo, i, pulse):
        v = self.a.bandes[i]
        vals = np.concatenate([v, v[::-1]])  # graves en haut, symétrique gauche/droite
        k = len(vals)
        r0 = self.r0 * (1 + 0.07 * pulse)
        larg = max(2, int(2 * np.pi * r0 / k * 0.55))
        for j, val in enumerate(vals):
            ang = -np.pi / 2 + (j + 0.5) / k * 2 * np.pi
            c, s = math.cos(ang), math.sin(ang)
            r1 = r0 + max(larg, float(val) * self.long_max)
            p0 = (self.cx + c * r0, self.cy + s * r0)
            p1 = (self.cx + c * r1, self.cy + s * r1)
            coul = _melange(self.accent, (255, 255, 255), min(1, float(val) * 0.7))
            d_net.line((p0, p1), fill=coul, width=larg)
            d_halo.line((self._h(*p0), self._h(*p1)), fill=self.accent, width=max(1, int(larg * 1.5 / self.g)))

    def _onde(self, d_net, d_halo, i):
        w = self.a.onde[i].astype(np.float32)
        y0 = self.base - self.haut_max * 0.45
        amp = self.haut_max * 0.55
        xs = np.linspace(self.x0, self.x1, len(w))
        # fondu aux extrémités pour que la ligne ne s'arrête pas net
        fondu = np.clip(np.minimum(np.arange(len(w)), np.arange(len(w))[::-1]) / 24, 0, 1)
        ys = y0 + w * amp * fondu
        pts = list(zip(xs.tolist(), ys.tolist()))
        ep = max(2, int(self.m * 0.004))
        d_halo.line([self._h(x, y) for x, y in pts], fill=self.accent, width=max(2, int(ep * 2 / self.g)),
                    joint="curve")
        d_net.line([(x, y + ep * 2.5) for x, y in pts], fill=_melange((10, 10, 14), self.accent, 0.35), width=ep,
                   joint="curve")
        d_net.line(pts, fill=self.clair, width=ep, joint="curve")

    def _particules(self, d_net, i, pulse):
        """Points lumineux qui s'échappent de la pochette et accélèrent sur les temps forts."""
        if not len(self.p_angle):
            return
        fps = self.a.fps
        t = i / fps
        age = (t + self.p_phase) % self.p_vie
        i0 = np.clip(np.round((t - age) * fps).astype(int), 0, self.a.n)
        boost = self.cumul[min(i, self.a.n)] - self.cumul[i0]
        dist = self.p_vit * (age + 2.5 * boost) + self.S * 0.25
        x = self.cx + np.cos(self.p_angle) * dist
        y = self.cy + np.sin(self.p_angle) * dist
        lum = np.sin(np.pi * age / self.p_vie) * (0.4 + 0.6 * pulse)
        r = self.p_taille * (1 + 0.6 * pulse)
        vis = (lum > 0.03) & (x > -10) & (x < self.W + 10) & (y > -10) & (y < self.H + 10)
        x, y, lum, r = x[vis], y[vis], lum[vis], r[vis]
        # mélange additif avec le fond, sans calque alpha : on lit la couleur du fond sous chaque point
        fond = self.fond[np.clip(y.astype(int), 0, self.H - 1), np.clip(x.astype(int), 0, self.W - 1)]
        fond = fond * (0.85 + 0.35 * pulse)
        clair = np.array(self.clair, np.float32)
        aura = np.clip(fond + clair * lum[:, None] * 0.3, 0, 255).astype(int)
        coeur = np.clip(fond + clair * lum[:, None], 0, 255).astype(int)
        for xi, yi, ri, ca, cc in zip(x.tolist(), y.tolist(), r.tolist(), aura.tolist(), coeur.tolist()):
            d_net.ellipse((xi - ri * 2.2, yi - ri * 2.2, xi + ri * 2.2, yi + ri * 2.2), fill=tuple(ca))
            d_net.ellipse((xi - ri, yi - ri, xi + ri, yi + ri), fill=tuple(cc))

    def image(self, i):
        o, a = self.o, self.a
        i = min(i, a.n - 1)
        pulse = float(a.pulse[i])
        img = self._fond(pulse)
        W, H, g = self.W, self.H, self.g
        zx0, zy0, zx1, zy1 = self.zone

        halo = Image.new("RGB", ((zx1 - zx0) // g, (zy1 - zy0) // g))
        d_halo = ImageDraw.Draw(halo)
        d_net = ImageDraw.Draw(img)
        self._particules(d_net, i, pulse)
        if o.style == "barres":
            self._barres(d_net, d_halo, i)
        elif o.style == "onde":
            self._onde(d_net, d_halo, i)

        # pochette (et son ombre) au centre de l'action
        poch = self._pochette(pulse)
        ombre = self.ombre
        img.paste(ombre, (self.cx - ombre.width // 2, self.cy - ombre.height // 2 + int(self.m * 0.01)), ombre)
        if o.style == "cercle":
            self._cercle(d_net, d_halo, i, pulse)
        img.paste(poch, (self.cx - poch.width // 2, self.cy - poch.height // 2), poch)

        # halo lumineux : calque réduit et flouté, ajouté en « écran » sur sa zone seulement
        flou = halo.filter(ImageFilter.GaussianBlur(max(1.5, self.m * 0.012 / g)))
        halo = ImageChops.add(flou, flou).resize((zx1 - zx0, zy1 - zy0), Image.BILINEAR)
        img.paste(ImageChops.screen(img.crop(self.zone), halo), (zx0, zy0))

        if self.bloc_texte:
            bloc, marge = self.bloc_texte
            align, tx, ty, _ = self.texte
            if align == "gauche":
                pos = (int(tx) - marge, int(ty - bloc.height / 2))
            else:
                pos = (int(tx - bloc.width / 2), int(ty) - marge)
            img.paste(bloc, pos, bloc)

        if o.progression:
            hp = max(3, int(self.m * 0.005))
            d = ImageDraw.Draw(img)
            d.rectangle((0, H - hp, W, H), fill=(25, 25, 30))
            d.rectangle((0, H - hp, int(W * (i + 1) / a.n), H), fill=self.accent)
        return img


# --------------------------------------------------------------------------- rendu parallèle

_rendu_proc = None


def _init_proc(opts, analyse):
    global _rendu_proc
    _rendu_proc = Rendu(opts, analyse)


def _image_proc(i):
    return _rendu_proc.image(i).tobytes()


def preparer(chemin, opts: Options, debut=0.0, duree=None):
    return analyser(decoder_audio(chemin, debut, duree), opts.fps, opts.nb_bandes)


def generer(chemin, sortie, opts: Options, debut=0.0, duree=None, progression=None, annule=None,
            processus=None, analyse=None):
    """Produit la vidéo. progression(fraction, message) est appelée régulièrement ; annule() → True pour arrêter."""
    def signaler(f, msg):
        if progression:
            progression(f, msg)

    def verifier():
        if annule and annule():
            raise Annule()

    ffmpeg = _outil("ffmpeg")
    if analyse is None:
        signaler(0, "Lecture de l'audio…")
        echantillons = decoder_audio(chemin, debut, duree)
        verifier()
        signaler(0, "Analyse du rythme…")
        analyse = analyser(echantillons, opts.fps, opts.nb_bandes)
        del echantillons
    verifier()

    preset, crf = QUALITES.get(opts.qualite, QUALITES["normale"])
    args = [ffmpeg, "-y", "-v", "error",
            "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{opts.largeur}x{opts.hauteur}", "-r", str(opts.fps),
            "-i", "-"]
    if debut:
        args += ["-ss", f"{debut:.3f}"]
    args += ["-i", chemin]
    if duree:
        args += ["-t", f"{duree:.3f}"]
    args += ["-map", "0:v:0", "-map", "1:a:0",
             "-c:v", "libx264", "-preset", preset, "-crf", str(crf), "-pix_fmt", "yuv420p",
             "-c:a", "aac", "-b:a", "320k", "-shortest", "-movflags", "+faststart"]
    for cle, val in (("title", opts.titre), ("artist", opts.artiste), ("album", opts.album)):
        if val:
            args += ["-metadata", f"{cle}={val}"]
    args.append(sortie)

    n = analyse.n
    if processus is None:
        processus = max(1, min(8, (os.cpu_count() or 2) - 1))
    journal = tempfile.TemporaryFile()
    proc = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=journal,
                            creationflags=NO_WINDOW)
    pool = None
    termine = False
    try:
        if processus > 1:
            pool = multiprocessing.get_context("spawn").Pool(processus, _init_proc, (opts, analyse))
            images = pool.imap(_image_proc, range(n), chunksize=4)
        else:
            rendu = Rendu(opts, analyse)
            images = (rendu.image(i).tobytes() for i in range(n))
        signaler(0, "Démarrage du rendu…")
        t0, dernier = None, 0.0
        for i, octets in enumerate(images):
            verifier()
            try:
                proc.stdin.write(octets)
            except (BrokenPipeError, OSError):
                break
            maintenant = time.monotonic()
            if t0 is None:  # la première image inclut le démarrage des processus : on ne la compte pas
                t0 = maintenant
            if maintenant - dernier > 0.25 or i == n - 1:
                dernier = maintenant
                fait = (i + 1) / n
                msg = f"Rendu : image {i + 1}/{n} — {fait:.0%}"
                if i >= 10:
                    reste = (maintenant - t0) / i * (n - 1 - i)
                    msg += f" — reste {_duree_lisible(reste)}"
                signaler(fait, msg)
        proc.stdin.close()
        proc.wait()
        if proc.returncode:
            journal.seek(0)
            raise RuntimeError("ffmpeg a échoué :\n" + journal.read().decode("utf-8", "replace").strip()[-2000:])
        termine = True
    finally:
        if pool is not None:
            pool.terminate()
            pool.join()
        if proc.poll() is None:
            proc.kill()
            proc.wait()
        journal.close()
        if not termine and os.path.exists(sortie):
            try:
                os.remove(sortie)
            except OSError:
                pass
    signaler(1, "Terminé")
    return sortie


def _duree_lisible(s):
    s = int(s)
    if s < 60:
        return f"{s} s"
    return f"{s // 60} min {s % 60:02d} s"
