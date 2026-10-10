"""Fonds générés par des shaders GLSL (OpenGL 3.3, via moderngl), animés par l'analyse audio.

Chaque fond est un fragment shader plein écran. Il reçoit à chaque image :
  uTime    temps en secondes
  uPhase   « temps musical » : avance plus vite quand la musique est forte (intégrale de l'énergie)
  uBass, uPulse, uBeat, uEnergy   énergie des basses, battement, attaque (kick), volume, entre 0 et 1
  uBands[16]   spectre réduit à 16 bandes, des graves aux aigus
  uC1, uC2, uC3   palette tirée de la pochette
Le rendu se fait dans un framebuffer hors écran, relu en RGB pour Pillow.
"""

FONDS = {
    "nebuleuse": "Nébuleuse",
    "plasma": "Plasma",
    "tunnel": "Tunnel",
    "aurore": "Aurore boréale",
    "kaleidoscope": "Kaléidoscope",
    "synthwave": "Synthwave",
    "pochette": "Pochette floutée (sans shader)",
}

VERT = """
#version 330
in vec2 in_pos;
void main() { gl_Position = vec4(in_pos, 0.0, 1.0); }
"""

ENTETE = """
#version 330
uniform vec2 uRes;
uniform float uTime, uPhase, uBass, uPulse, uBeat, uEnergy;
uniform float uBands[16];
uniform vec3 uC1, uC2, uC3;
out vec4 fragColor;

// gl_FragCoord sert de coordonnées image (y vers le bas) : la relecture du framebuffer donne
// alors directement les lignes dans l'ordre attendu par Pillow, sans retournement.
vec2 FC() { return gl_FragCoord.xy; }
// coordonnées centrées, y vers le haut, hauteur de l'écran = 1
vec2 P() { vec2 f = FC(); return vec2(f.x - 0.5 * uRes.x, 0.5 * uRes.y - f.y) / uRes.y; }

float hash(vec2 p) { p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1, 0)), u.x), mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), u.x), u.y);
}
float fbm(vec2 p) {
    float v = 0.0, a = 0.5;
    mat2 r = mat2(0.8, 0.6, -0.6, 0.8);
    for (int i = 0; i < 5; i++) { v += a * noise(p); p = r * p * 2.02 + 17.0; a *= 0.5; }
    return v;
}
// palette cyclique lissée entre les trois couleurs de la pochette
vec3 pal(float t) {
    t = fract(t) * 3.0;
    vec3 a = t < 1.0 ? uC1 : (t < 2.0 ? uC2 : uC3);
    vec3 b = t < 1.0 ? uC2 : (t < 2.0 ? uC3 : uC1);
    return mix(a, b, smoothstep(0.0, 1.0, fract(t)));
}
float bande(float x) {  // spectre lu en continu, x de 0 (graves) à 1 (aigus)
    float i = clamp(x, 0.0, 1.0) * 15.0;
    int a = int(floor(i));
    int b = min(a + 1, 15);
    return mix(uBands[a], uBands[b], fract(i));
}
vec3 fond(vec2 p);

void main() {
    vec2 p = P();
    vec3 col = fond(p);
    // vignette et plafond de luminosité : le premier plan (pochette, texte, spectre) doit rester lisible
    vec2 v = FC() / uRes - 0.5;
    col *= clamp(1.15 - 1.1 * dot(v, v) * 2.0, 0.3, 1.0);
    col = 1.0 - exp(-col * 1.3);   // compression douce des hautes lumières
    fragColor = vec4(col * 0.85, 1.0);
}
"""

SOURCES = {
    # volutes de fumée colorée (fbm à domaine déformé), qui se tordent davantage sur les basses
    "nebuleuse": """
vec3 fond(vec2 p) {
    p *= 1.1;
    float t = uPhase * 0.06;
    vec2 q = vec2(fbm(p + vec2(0.0, 0.0) + t), fbm(p + vec2(5.2, 1.3) - t));
    vec2 r = vec2(fbm(p + 4.0 * q + vec2(1.7, 9.2) + 0.5 * t), fbm(p + 4.0 * q + vec2(8.3, 2.8) - 0.3 * t));
    float f = fbm(p + (3.5 + 1.5 * uBass) * r);
    vec3 col = pal(f * 1.3 + length(q) * 0.5 + t * 0.15);
    col *= f * f * 1.8 + 0.08;
    col *= 0.7 + 0.7 * uPulse;
    col += uC2 * pow(clamp(length(r) - 0.5, 0.0, 1.0), 2.0) * 0.6 * uBeat;
    return col;
}
""",
    # plasma classique (sommes de sinus) avec des ondes circulaires lancées par les basses
    "plasma": """
vec3 fond(vec2 p) {
    p *= 3.0;
    float t = uPhase * 0.25;
    float v = sin(p.x * 1.3 + t) + sin(p.y * 1.7 - t * 1.2) + sin((p.x + p.y) * 1.1 + t * 0.7)
            + sin(length(p * 1.5 + vec2(sin(t * 0.5), cos(t * 0.4)) * 2.0) * 2.0 - t * 1.5);
    v += uBass * 1.2 * sin(length(p) * 3.0 - uTime * 4.0) * exp(-length(p) * 0.25);
    vec3 col = pal(v * 0.12 + t * 0.03);
    float lignes = 0.5 + 0.5 * sin(v * 3.14159);
    return col * (0.18 + 0.5 * lignes * lignes) * (0.75 + 0.6 * uPulse);
}
""",
    # tunnel infini : on avance au rythme de la musique, les anneaux s'allument sur les kicks,
    # et le spectre est réparti tout autour de la paroi
    "tunnel": """
vec3 fond(vec2 p) {
    float r = length(p) + 1e-3;
    float a = atan(p.y, p.x);
    float z = 0.3 / r + uPhase * 0.5;
    float ang = abs(a / 3.14159265);          // 0 en haut... symétrique gauche/droite
    float anneaux = pow(abs(sin(z * 3.14159265)), 24.0);
    float rayures = 0.5 + 0.5 * sin(a * 10.0 + z * 1.5 + uTime * 0.2);
    float b = bande(ang);
    vec3 col = pal(z * 0.06 + ang * 0.3);
    col *= 0.12 + 0.35 * rayures * (0.4 + b) + anneaux * (0.5 + 1.8 * uBeat);
    col *= smoothstep(0.0, 0.35, r);          // le fond du tunnel se perd dans le noir
    return col * (0.8 + 0.4 * uPulse);
}
""",
    # rideaux de lumière ondulants dans un ciel étoilé ; leur éclat suit le spectre
    "aurore": """
vec3 fond(vec2 p) {
    vec2 uv = FC() / uRes;
    float x = p.x;
    float t = uPhase * 0.08;
    vec3 col = mix(uC3 * 0.06, vec3(0.005, 0.01, 0.03), uv.y);
    // étoiles qui scintillent
    vec2 g = floor(FC() / 3.0);
    float e = step(0.997, hash(g)) * (0.5 + 0.5 * sin(uTime * 3.0 + hash(g + 7.0) * 30.0));
    col += vec3(e) * (1.0 - uv.y) * 0.8;
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float h = 0.25 + 0.12 * fi + 0.25 * (fbm(vec2(x * 0.9 + fi * 3.1 + t, t * 0.6 + fi)) - 0.5);
        float y = 1.0 - uv.y;                  // hauteur depuis le bas
        float d = y - h;
        float rideau = smoothstep(-0.02, 0.0, d) * exp(-max(d, 0.0) * (5.0 - 2.0 * uBass));
        float rayons = 0.55 + 0.45 * noise(vec2(x * 22.0 + fi * 10.0, t * 3.0));
        float b = bande(0.5 + 0.5 * sin(x * 1.7 + fi * 2.1));  // continu : pas de cassure
        col += pal(fi * 0.33 + x * 0.15 + t * 0.1) * rideau * rayons * (0.25 + 0.9 * b) * (0.6 + 0.6 * uPulse);
    }
    return col;
}
""",
    # kaléidoscope à 6 branches de bruit fractal, qui tourne avec la musique et respire sur les basses
    "kaleidoscope": """
vec3 fond(vec2 p) {
    float r = length(p);
    float a = atan(p.y, p.x) + uPhase * 0.04;
    float n = 6.0, s = 6.2831853 / n;
    a = mod(a, s);
    a = abs(a - s * 0.5);
    p = r * vec2(cos(a), sin(a));
    p *= 2.6 - 0.5 * uBass;
    float f = fbm(p + vec2(uPhase * 0.12, 0.0));
    float g = fbm(p * 2.0 - uPhase * 0.08 + f * 2.0);
    vec3 col = pal(f + g * 0.6 + r * 0.4 - uPhase * 0.02);
    col *= smoothstep(0.35, 0.85, g) * 1.4 + 0.06;
    col *= 0.7 + 1.2 * uPulse * exp(-r * 1.5);
    return col;
}
""",
    # années 80 : soleil rayé, ciel étoilé et grille en perspective qui défile au tempo
    "synthwave": """
vec3 fond(vec2 p) {
    float hz = -0.2;
    // sol : grille en perspective (calculée hors de toute condition, fwidth l'exige)
    float d = max(hz - p.y, 1e-3);
    float z = 0.35 / d;
    vec2 g2 = vec2(p.x * z, z + uPhase * 0.9) * 0.5;
    vec2 gr = abs(fract(g2) - 0.5) / (fwidth(g2) * 1.5 + 1e-4);
    float ligne = 1.0 - clamp(min(gr.x, gr.y), 0.0, 1.0);
    vec3 sol = uC3 * 0.04 + pal(0.35 + p.x * z * 0.01) * ligne * exp(-z * 0.08) * (0.6 + 1.4 * uBeat);
    sol += uC2 * exp(-d * 18.0) * (0.4 + 0.6 * uPulse);   // lueur de l'horizon

    // ciel : dégradé, étoiles, soleil rayé qui pulse
    float y = max(p.y - hz, 0.0);
    vec3 ciel = mix(uC1 * 0.35, uC3 * 0.05, smoothstep(0.0, 0.6, y));
    ciel += vec3(step(0.998, hash(floor(FC() / 3.0)))) * smoothstep(0.1, 0.5, y) * 0.8;
    vec2 c = vec2(0.0, hz + 0.2);
    float rs = 0.22 * (1.0 + 0.04 * uPulse);
    float ds = length(p - c);
    float rayures = step(0.0, sin((p.y - c.y) * 70.0 - uTime * 2.0) + (p.y - c.y) * 18.0 + 0.6);
    vec3 soleil = mix(uC2, vec3(1.0, 0.95, 0.7), smoothstep(c.y - rs, c.y + rs, p.y));
    ciel = mix(ciel, soleil * (0.45 + 0.3 * uPulse), smoothstep(rs, rs - 0.004, ds) * rayures);
    ciel += uC2 * exp(-max(ds - rs, 0.0) * 9.0) * (0.25 + 0.35 * uBass);

    return mix(sol, ciel, smoothstep(-0.002, 0.002, p.y - hz));
}
""",
}


def _contexte():
    import moderngl
    erreurs = []
    for options in ({}, {"backend": "egl"}):
        try:
            return moderngl.create_standalone_context(require=330, **options)
        except Exception as e:  # noqa: BLE001 - on essaie le mode suivant
            erreurs.append(str(e))
    raise RuntimeError(
        "OpenGL 3.3 est indisponible sur cet ordinateur (pilote graphique absent ou trop ancien, "
        "bureau à distance, machine virtuelle…). Choisissez le fond « Pochette floutée », ou mettez à jour "
        "le pilote de la carte graphique.\n\nDétail : " + " / ".join(erreurs))


class FondShader:
    def __init__(self, nom, largeur, hauteur, couleurs):
        try:
            import moderngl  # noqa: F401
        except ImportError:
            raise RuntimeError("Le module moderngl manque : pip install moderngl") from None
        self.taille = (largeur, hauteur)
        self.ctx = _contexte()
        self.prog = self.ctx.program(vertex_shader=VERT, fragment_shader=ENTETE + SOURCES[nom])
        import struct
        vbo = self.ctx.buffer(struct.pack("6f", -1, -1, 3, -1, -1, 3))  # un triangle couvre tout l'écran
        self.vao = self.ctx.vertex_array(self.prog, [(vbo, "2f", "in_pos")])
        self.fbo = self.ctx.simple_framebuffer(self.taille, components=4)
        self._u("uRes", self.taille)
        for nom_u, c in zip(("uC1", "uC2", "uC3"), couleurs):
            self._u(nom_u, tuple(v / 255 for v in c))

    def _u(self, nom, valeur):
        if nom in self.prog:  # le compilateur supprime les uniformes inutilisés
            self.prog[nom].value = valeur

    def rendre(self, temps, phase, basse, pulse, impulsion, energie, bandes16):
        self._u("uTime", temps)
        self._u("uPhase", phase)
        self._u("uBass", basse)
        self._u("uPulse", pulse)
        self._u("uBeat", impulsion)
        self._u("uEnergy", energie)
        self._u("uBands", tuple(float(b) for b in bandes16))
        self.fbo.use()
        self.vao.render()
        return self.fbo.read(components=3, alignment=1)

    def fermer(self):
        self.ctx.release()
