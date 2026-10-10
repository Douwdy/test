"""Visualiseur musical : transforme un fichier audio en vidéo animée au rythme de la musique.

Sans argument : ouvre l'interface. Avec un fichier audio seul (ou glissé sur l'exe) : l'interface s'ouvre avec ce
fichier. Avec -o/--sortie : génère la vidéo en ligne de commande (voir --help).
"""

import argparse
import multiprocessing
import os
import queue
import subprocess
import sys
import threading

import moteur
from shaders import FONDS as NOMS_FONDS

FORMATS = {
    "1920×1080 — 16:9 (YouTube)": (1920, 1080),
    "1280×720 — 16:9 (plus rapide)": (1280, 720),
    "2560×1440 — 16:9 (1440p)": (2560, 1440),
    "3840×2160 — 16:9 (4K, lent)": (3840, 2160),
    "1080×1080 — carré (Instagram)": (1080, 1080),
    "1080×1920 — vertical (Shorts, TikTok)": (1080, 1920),
}
STYLES = {"Barres": "barres", "Cercle": "cercle", "Onde": "onde"}
FONDS = {libelle: cle for cle, libelle in NOMS_FONDS.items()}
QUALITES = {"Rapide": "rapide", "Normale": "normale", "Haute (plus lent)": "haute"}
TYPES_AUDIO = [("Fichiers audio", "*.mp3 *.flac *.wav *.ogg *.opus *.m4a *.aac *.wma *.aiff *.aif *.alac *.ape "
                                  "*.wv *.mka *.mp4 *.webm *.mkv"), ("Tous les fichiers", "*.*")]
TYPES_IMAGE = [("Images", "*.jpg *.jpeg *.png *.webp *.bmp *.gif"), ("Tous les fichiers", "*.*")]


def _afficher(*args):
    if sys.stdout:  # None dans l'exe sans console
        print(*args, flush=True)


def _ouvrir(chemin):
    if os.name == "nt":
        os.startfile(chemin)
    else:
        subprocess.Popen(["open" if sys.platform == "darwin" else "xdg-open", chemin])


def sortie_par_defaut(chemin):
    base = os.path.splitext(chemin)[0]
    sortie = base + ".mp4"
    if os.path.abspath(sortie) == os.path.abspath(chemin):
        sortie = base + " (visualiseur).mp4"
    return sortie


# --------------------------------------------------------------------------- interface

def lancer_interface(fichier=None):
    import tkinter as tk
    from tkinter import colorchooser, filedialog, messagebox, ttk

    from PIL import Image, ImageTk

    class App(tk.Tk):
        def __init__(self):
            super().__init__()
            self.title("Visualiseur musical")
            self.minsize(720, 560)
            self.file = queue.Queue()
            self.annulation = threading.Event()
            self.occupe = False
            self.pochette = None        # image PIL utilisée
            self.couleur = None         # None : automatique (tirée de la pochette)
            self.cache_analyse = {}     # pour l'aperçu
            self._vignette = None
            self._apercu_img = None

            self.v = {k: tk.StringVar() for k in ("fichier", "titre", "artiste", "album", "sortie", "debut", "duree")}
            self.v_format = tk.StringVar(value=next(iter(FORMATS)))
            self.v_style = tk.StringVar(value="Barres")
            self.v_fond = tk.StringVar(value=NOMS_FONDS["nebuleuse"])
            self.v_fps = tk.StringVar(value="30")
            self.v_qualite = tk.StringVar(value="Normale")
            self.v_particules = tk.BooleanVar(value=True)
            self.v_progression = tk.BooleanVar(value=True)
            self.v_statut = tk.StringVar(value="Choisissez un fichier audio pour commencer.")

            self._construire()
            self.protocol("WM_DELETE_WINDOW", self._fermer)
            self.after(100, self._pomper)
            if not moteur.trouver_outil("ffmpeg") or not moteur.trouver_outil("ffprobe"):
                self.after(300, lambda: messagebox.showwarning(
                    "ffmpeg introuvable",
                    "ffmpeg.exe et ffprobe.exe sont nécessaires.\n\nPlacez-les à côté de ce programme (ou dans un "
                    "sous-dossier « ffmpeg »), ou installez ffmpeg dans le PATH.\n\n"
                    "Téléchargement : https://www.gyan.dev/ffmpeg/builds/", parent=self))
            if fichier:
                self.after(200, lambda: self._charger(fichier))

        # ------------------------------------------------------------ construction

        def _construire(self):
            pad = dict(padx=8, pady=4)
            racine = ttk.Frame(self, padding=10)
            racine.pack(fill="both", expand=True)
            racine.columnconfigure(0, weight=1)

            f = ttk.LabelFrame(racine, text="Morceau", padding=8)
            f.grid(row=0, column=0, sticky="ew", **pad)
            f.columnconfigure(1, weight=1)
            ttk.Label(f, text="Fichier audio").grid(row=0, column=0, sticky="w")
            ttk.Entry(f, textvariable=self.v["fichier"]).grid(row=0, column=1, sticky="ew", padx=6)
            ttk.Button(f, text="Parcourir…", command=self._choisir_audio).grid(row=0, column=2)

            f = ttk.LabelFrame(racine, text="Informations affichées (lues dans le fichier, modifiables)", padding=8)
            f.grid(row=1, column=0, sticky="ew", **pad)
            f.columnconfigure(1, weight=1)
            for r, (cle, nom) in enumerate((("titre", "Titre"), ("artiste", "Artiste"), ("album", "Album"))):
                ttk.Label(f, text=nom).grid(row=r, column=0, sticky="w", pady=2)
                ttk.Entry(f, textvariable=self.v[cle]).grid(row=r, column=1, sticky="ew", padx=6, pady=2)
            cadre_p = ttk.Frame(f)
            cadre_p.grid(row=0, column=2, rowspan=4, padx=(10, 0), sticky="n")
            self.lbl_pochette = tk.Label(cadre_p, text="Aucune\npochette", width=18, height=8, relief="groove",
                                         bg="#202028", fg="#aaaaaa")
            self.lbl_pochette.pack()
            bts = ttk.Frame(cadre_p)
            bts.pack(pady=(4, 0))
            ttk.Button(bts, text="Choisir…", command=self._choisir_pochette).pack(side="left")
            ttk.Button(bts, text="Retirer", command=lambda: self._definir_pochette(None)).pack(side="left", padx=4)

            f = ttk.LabelFrame(racine, text="Vidéo", padding=8)
            f.grid(row=2, column=0, sticky="ew", **pad)
            for c in (1, 3):
                f.columnconfigure(c, weight=1)
            ttk.Label(f, text="Format").grid(row=0, column=0, sticky="w")
            ttk.Combobox(f, textvariable=self.v_format, values=list(FORMATS), state="readonly", width=34) \
                .grid(row=0, column=1, sticky="ew", padx=6, pady=2)
            ttk.Label(f, text="Style").grid(row=0, column=2, sticky="w")
            ttk.Combobox(f, textvariable=self.v_style, values=list(STYLES), state="readonly", width=12) \
                .grid(row=0, column=3, sticky="ew", padx=6, pady=2)
            ttk.Label(f, text="Images/s").grid(row=1, column=0, sticky="w")
            ttk.Combobox(f, textvariable=self.v_fps, values=["24", "30", "60"], state="readonly", width=6) \
                .grid(row=1, column=1, sticky="w", padx=6, pady=2)
            ttk.Label(f, text="Qualité").grid(row=1, column=2, sticky="w")
            ttk.Combobox(f, textvariable=self.v_qualite, values=list(QUALITES), state="readonly", width=16) \
                .grid(row=1, column=3, sticky="ew", padx=6, pady=2)

            ttk.Label(f, text="Fond (shader)").grid(row=2, column=0, sticky="w")
            ttk.Combobox(f, textvariable=self.v_fond, values=list(FONDS), state="readonly", width=30) \
                .grid(row=2, column=1, sticky="ew", padx=6, pady=2)

            ttk.Label(f, text="Couleur").grid(row=3, column=0, sticky="w")
            cc = ttk.Frame(f)
            cc.grid(row=3, column=1, sticky="w", padx=6, pady=2)
            self.lbl_couleur = tk.Label(cc, width=3, relief="groove")
            self.lbl_couleur.pack(side="left")
            self.lbl_couleur_txt = ttk.Label(cc, text="")
            self.lbl_couleur_txt.pack(side="left", padx=6)
            ttk.Button(cc, text="Choisir…", command=self._choisir_couleur).pack(side="left")
            ttk.Button(cc, text="Auto", command=lambda: self._definir_couleur(None)).pack(side="left", padx=4)
            oc = ttk.Frame(f)
            oc.grid(row=3, column=2, columnspan=2, sticky="w")
            ttk.Checkbutton(oc, text="Particules", variable=self.v_particules).pack(side="left")
            ttk.Checkbutton(oc, text="Barre de progression", variable=self.v_progression).pack(side="left", padx=8)

            ttk.Label(f, text="Extrait").grid(row=4, column=0, sticky="w")
            ex = ttk.Frame(f)
            ex.grid(row=4, column=1, columnspan=3, sticky="w", padx=6, pady=2)
            ttk.Label(ex, text="début (s)").pack(side="left")
            ttk.Entry(ex, textvariable=self.v["debut"], width=7).pack(side="left", padx=4)
            ttk.Label(ex, text="durée (s)").pack(side="left", padx=(8, 0))
            ttk.Entry(ex, textvariable=self.v["duree"], width=7).pack(side="left", padx=4)
            ttk.Label(ex, text="vide = morceau entier", foreground="#777777").pack(side="left", padx=8)

            f = ttk.LabelFrame(racine, text="Sortie", padding=8)
            f.grid(row=3, column=0, sticky="ew", **pad)
            f.columnconfigure(1, weight=1)
            ttk.Label(f, text="Vidéo MP4").grid(row=0, column=0, sticky="w")
            ttk.Entry(f, textvariable=self.v["sortie"]).grid(row=0, column=1, sticky="ew", padx=6)
            ttk.Button(f, text="Enregistrer sous…", command=self._choisir_sortie).grid(row=0, column=2)

            bas = ttk.Frame(racine)
            bas.grid(row=4, column=0, sticky="ew", **pad)
            bas.columnconfigure(0, weight=1)
            self.barre = ttk.Progressbar(bas, maximum=1000)
            self.barre.grid(row=0, column=0, columnspan=4, sticky="ew", pady=(0, 6))
            ttk.Label(bas, textvariable=self.v_statut).grid(row=1, column=0, sticky="w")
            self.bt_apercu = ttk.Button(bas, text="Aperçu", command=self._apercu)
            self.bt_apercu.grid(row=1, column=1, padx=4)
            self.bt_generer = ttk.Button(bas, text="Générer la vidéo", command=self._generer)
            self.bt_generer.grid(row=1, column=2, padx=4)
            self.bt_annuler = ttk.Button(bas, text="Annuler", command=self.annulation.set, state="disabled")
            self.bt_annuler.grid(row=1, column=3, padx=4)
            self._definir_couleur(None)

        # ------------------------------------------------------------ outils

        def _pomper(self):
            """Exécute dans le fil de l'interface ce que les tâches de fond y ont déposé."""
            try:
                while True:
                    self.file.get_nowait()()
            except queue.Empty:
                pass
            self.after(80, self._pomper)

        def _en_fond(self, travail, fini, erreur_titre="Erreur"):
            def fil():
                try:
                    res = travail()
                except moteur.Annule:
                    self.file.put(lambda: self._fin("Annulé."))
                except Exception as e:  # noqa: BLE001 - toute erreur est montrée à l'utilisateur
                    msg = str(e) or e.__class__.__name__
                    self.file.put(lambda: (self._fin("Erreur."), messagebox.showerror(erreur_titre, msg, parent=self)))
                else:
                    self.file.put(lambda: fini(res))
            threading.Thread(target=fil, daemon=True).start()

        def _occuper(self, oui, statut=None):
            self.occupe = oui
            etat = "disabled" if oui else "normal"
            self.bt_generer.configure(state=etat)
            self.bt_apercu.configure(state=etat)
            self.bt_annuler.configure(state="normal" if oui else "disabled")
            if statut:
                self.v_statut.set(statut)

        def _fin(self, statut):
            self._occuper(False, statut)
            self.barre.configure(value=0)

        def _definir_pochette(self, img):
            self.pochette = img
            if img is None:
                self._vignette = None
                self.lbl_pochette.configure(image="", text="Aucune\npochette\n(une sera générée)", width=18, height=8)
            else:
                v = img.copy()
                v.thumbnail((140, 140))
                self._vignette = ImageTk.PhotoImage(v)
                self.lbl_pochette.configure(image=self._vignette, text="", width=140, height=140)
            if self.couleur is None:
                self._definir_couleur(None)

        def _definir_couleur(self, c):
            self.couleur = c
            vraie = c or moteur.couleur_accent(self.pochette)
            self.lbl_couleur.configure(bg="#%02x%02x%02x" % tuple(vraie))
            self.lbl_couleur_txt.configure(text="automatique (pochette)" if c is None else "personnalisée")

        def _nombre(self, cle, nom):
            txt = self.v[cle].get().strip().replace(",", ".")
            if not txt:
                return None
            try:
                val = float(txt)
            except ValueError:
                raise ValueError(f"« {nom} » doit être un nombre de secondes.") from None
            if val < 0:
                raise ValueError(f"« {nom} » ne peut pas être négatif.")
            return val

        def _lire_options(self):
            w, h = FORMATS[self.v_format.get()]
            return moteur.Options(
                largeur=w, hauteur=h, fps=int(self.v_fps.get()), style=STYLES[self.v_style.get()],
                fond=FONDS[self.v_fond.get()],
                titre=self.v["titre"].get(), artiste=self.v["artiste"].get(), album=self.v["album"].get(),
                pochette=self.pochette, couleur=self.couleur, particules=self.v_particules.get(),
                progression=self.v_progression.get(), qualite=QUALITES[self.v_qualite.get()])

        def _entrees(self):
            chemin = self.v["fichier"].get().strip().strip('"')
            if not chemin or not os.path.isfile(chemin):
                raise ValueError("Choisissez d'abord un fichier audio existant.")
            debut = self._nombre("debut", "début") or 0.0
            duree = self._nombre("duree", "durée") or None
            return chemin, debut, duree

        # ------------------------------------------------------------ actions

        def _choisir_audio(self):
            chemin = filedialog.askopenfilename(title="Fichier audio", filetypes=TYPES_AUDIO, parent=self)
            if chemin:
                self._charger(chemin)

        def _charger(self, chemin):
            chemin = os.path.abspath(chemin)
            self.v["fichier"].set(chemin)
            self.v["sortie"].set(sortie_par_defaut(chemin))
            self.cache_analyse.clear()
            self._occuper(True, "Lecture des informations du fichier…")

            def fini(infos):
                self.v["titre"].set(infos.titre)
                self.v["artiste"].set(infos.artiste)
                self.v["album"].set(infos.album + (f" ({infos.annee})" if infos.album and infos.annee else ""))
                self._definir_pochette(infos.pochette)
                duree = f"{int(infos.duree // 60)}:{int(infos.duree % 60):02d}" if infos.duree else "?"
                manque = [n for n, v in (("titre", infos.titre), ("artiste", infos.artiste),
                                         ("pochette", infos.pochette)) if not v]
                note = f" — à compléter : {', '.join(manque)}" if manque else ""
                self._fin(f"Durée {duree}{note}")

            self._en_fond(lambda: moteur.lire_infos(chemin), fini, "Lecture impossible")

        def _choisir_pochette(self):
            chemin = filedialog.askopenfilename(title="Image de pochette", filetypes=TYPES_IMAGE, parent=self)
            if chemin:
                try:
                    self._definir_pochette(moteur.ouvrir_image(chemin))
                except OSError as e:
                    messagebox.showerror("Image illisible", str(e), parent=self)

        def _choisir_couleur(self):
            vraie = self.couleur or moteur.couleur_accent(self.pochette)
            rgb, _ = colorchooser.askcolor(color="#%02x%02x%02x" % tuple(vraie), title="Couleur", parent=self)
            if rgb:
                self._definir_couleur(tuple(int(x) for x in rgb))

        def _choisir_sortie(self):
            actuel = self.v["sortie"].get()
            chemin = filedialog.asksaveasfilename(
                title="Enregistrer la vidéo", defaultextension=".mp4", filetypes=[("Vidéo MP4", "*.mp4")],
                initialdir=os.path.dirname(actuel) or None, initialfile=os.path.basename(actuel) or None,
                parent=self)
            if chemin:
                self.v["sortie"].set(chemin)

        def _apercu(self):
            try:
                chemin, debut, duree = self._entrees()
                opts = self._lire_options()
            except ValueError as e:
                messagebox.showerror("Paramètres", str(e), parent=self)
                return
            cle = (chemin, debut, duree, opts.fps, opts.nb_bandes)
            self._occuper(True, "Préparation de l'aperçu (analyse du morceau)…")

            def travail():
                analyse = self.cache_analyse.get(cle)
                if analyse is None:
                    analyse = self.cache_analyse[cle] = moteur.preparer(chemin, opts, debut, duree)
                # un temps fort vers le premier tiers du morceau, pour voir l'effet
                n = analyse.n
                zone = analyse.pulse[n // 4: max(n // 4 + 1, n // 2)]
                i = n // 4 + int(zone.argmax())
                return moteur.apercu(opts, analyse, i), i / analyse.fps

            def fini(res):
                img, t = res
                self._fin("Aperçu prêt.")
                fen = tk.Toplevel(self)
                fen.title(f"Aperçu — {int(t // 60)}:{t % 60:04.1f}")
                img.thumbnail((min(1100, self.winfo_screenwidth() - 100), self.winfo_screenheight() - 160))
                self._apercu_img = ImageTk.PhotoImage(img)
                tk.Label(fen, image=self._apercu_img).pack()

            self._en_fond(travail, fini, "Aperçu impossible")

        def _generer(self):
            try:
                chemin, debut, duree = self._entrees()
                opts = self._lire_options()
            except ValueError as e:
                messagebox.showerror("Paramètres", str(e), parent=self)
                return
            sortie = self.v["sortie"].get().strip().strip('"')
            if not sortie:
                sortie = sortie_par_defaut(chemin)
                self.v["sortie"].set(sortie)
            if not sortie.lower().endswith(".mp4"):
                sortie += ".mp4"
                self.v["sortie"].set(sortie)
            if os.path.abspath(sortie) == os.path.abspath(chemin):
                messagebox.showerror("Sortie", "La vidéo écraserait le fichier audio : changez le nom.", parent=self)
                return
            if os.path.exists(sortie) and not messagebox.askyesno(
                    "Fichier existant", f"{os.path.basename(sortie)} existe déjà. Le remplacer ?", parent=self):
                return
            self.annulation.clear()
            self._occuper(True, "Préparation…")
            cle = (chemin, debut, duree, opts.fps, opts.nb_bandes)

            def progression(frac, msg):
                self.file.put(lambda: (self.barre.configure(value=frac * 1000), self.v_statut.set(msg)))

            def travail():
                return moteur.generer(chemin, sortie, opts, debut, duree, progression=progression,
                                      annule=self.annulation.is_set, analyse=self.cache_analyse.get(cle))

            def fini(res):
                self._fin(f"Vidéo créée : {os.path.basename(res)}")
                if messagebox.askyesno("Terminé", f"La vidéo est prête :\n{res}\n\nL'ouvrir maintenant ?",
                                       parent=self):
                    _ouvrir(res)

            self._en_fond(travail, fini, "Génération impossible")

        def _fermer(self):
            if self.occupe and not messagebox.askyesno("Quitter", "Un traitement est en cours. Quitter quand même ?",
                                                       parent=self):
                return
            self.annulation.set()
            self.destroy()

    App().mainloop()


# --------------------------------------------------------------------------- ligne de commande

def ligne_de_commande(argv):
    p = argparse.ArgumentParser(description="Génère une vidéo de visualisation rythmée à partir d'un fichier audio.")
    p.add_argument("audio", help="fichier audio (tout format lu par ffmpeg)")
    p.add_argument("-o", "--sortie", help="vidéo MP4 à créer (par défaut : à côté du fichier audio)")
    p.add_argument("--style", choices=moteur.STYLES, default="barres")
    p.add_argument("--fond", choices=list(NOMS_FONDS), default="nebuleuse",
                   help="fond généré par shader, ou « pochette » (pochette floutée, sans OpenGL)")
    p.add_argument("--format", default="1920x1080", help="largeur x hauteur, ex. 1920x1080, 1080x1920")
    p.add_argument("--fps", type=int, default=30)
    p.add_argument("--qualite", choices=list(moteur.QUALITES), default="normale")
    p.add_argument("--titre")
    p.add_argument("--artiste")
    p.add_argument("--album")
    p.add_argument("--pochette", help="image à utiliser à la place de la pochette du fichier")
    p.add_argument("--couleur", help="couleur des effets, ex. #ff3366 (par défaut : tirée de la pochette)")
    p.add_argument("--sans-particules", action="store_true")
    p.add_argument("--sans-progression", action="store_true")
    p.add_argument("--debut", type=float, default=0.0, help="début de l'extrait, en secondes")
    p.add_argument("--duree", type=float, help="durée de l'extrait, en secondes")
    p.add_argument("--processus", type=int, help="nombre de processus de rendu (par défaut : cœurs - 1)")
    a = p.parse_args(argv)

    infos = moteur.lire_infos(a.audio)
    try:
        w, h = (int(x) for x in a.format.lower().replace("×", "x").split("x"))
    except ValueError:
        p.error("--format attend largeur x hauteur, ex. 1920x1080")
    couleur = None
    if a.couleur:
        c = a.couleur.lstrip("#")
        if len(c) != 6:
            p.error("--couleur attend une couleur hexadécimale, ex. #ff3366")
        couleur = tuple(int(c[k:k + 2], 16) for k in (0, 2, 4))
    album = a.album if a.album is not None else infos.album
    opts = moteur.Options(
        largeur=w, hauteur=h, fps=a.fps, style=a.style, fond=a.fond,
        titre=a.titre if a.titre is not None else infos.titre,
        artiste=a.artiste if a.artiste is not None else infos.artiste,
        album=album, pochette=moteur.ouvrir_image(a.pochette) if a.pochette else infos.pochette,
        couleur=couleur, particules=not a.sans_particules, progression=not a.sans_progression, qualite=a.qualite)
    sortie = a.sortie or sortie_par_defaut(a.audio)
    _afficher(f"{opts.artiste} — {opts.titre}" if opts.artiste else opts.titre)
    dernier = [""]

    def progression(frac, msg):
        if msg != dernier[0]:
            dernier[0] = msg
            if sys.stdout:
                sys.stdout.write("\r" + msg.ljust(70))
                sys.stdout.flush()

    moteur.generer(a.audio, sortie, opts, a.debut, a.duree, progression=progression, processus=a.processus)
    _afficher(f"\nVidéo créée : {sortie}")


def main():
    multiprocessing.freeze_support()  # indispensable pour l'exe PyInstaller sous Windows
    args = sys.argv[1:]
    if not args or (len(args) == 1 and not args[0].startswith("-")):
        lancer_interface(args[0] if args else None)
    else:
        try:
            ligne_de_commande(args)
        except (RuntimeError, ValueError, OSError) as e:
            _afficher(f"Erreur : {e}")
            sys.exit(1)


if __name__ == "__main__":
    main()
