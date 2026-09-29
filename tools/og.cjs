// Génère les images de partage public/og.png (FR) et public/og-en.png (EN), 1200×630,
// ainsi que public/apple-touch-icon.png (180×180), avec Chromium via Playwright.
//
//   npx -y -p playwright@1 node tools/og.cjs   (ou : node tools/og.cjs si playwright est déjà installé)
//
// À relancer seulement si le titre, l'icône ou l'onde (public/wave.svg, produite par tools/build.py) changent.
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const PUBLIC = path.join(__dirname, '..', 'public');
const icon = 'data:image/svg+xml;base64,' + fs.readFileSync(path.join(PUBLIC, 'icon.svg')).toString('base64');
const wave = fs.readFileSync(path.join(PUBLIC, 'wave.svg'), 'utf8').replace('<svg ', '<svg class="wave" ');

const VARIANTS = [
  { file: 'og.png', lang: 'fr', h1: 'Écris. Enregistre.<br><em>Garde tout chez toi.</em>', sub: 'Le carnet de textes hors-ligne · Android' },
  { file: 'og-en.png', lang: 'en', h1: 'Write. Record.<br><em>Keep it all on your phone.</em>', sub: 'The offline lyrics notebook · Android' },
];

const page = (v) => `<!doctype html><html lang="${v.lang}"><head><meta charset="utf-8"><style>
*{box-sizing:border-box;margin:0}
body{width:1200px;height:630px;overflow:hidden;position:relative;color:#fff;
  font-family:system-ui,-apple-system,"Segoe UI",Roboto,"Helvetica Neue",sans-serif;
  background:radial-gradient(60% 90% at 90% 0%,rgba(120,117,230,.6) 0%,transparent 62%),
             radial-gradient(50% 70% at 0% 100%,rgba(183,181,255,.2) 0%,transparent 60%),#3D3B8E}
.wave{position:absolute;left:0;right:0;bottom:0;width:100%;height:150px;fill:rgba(183,181,255,.18)}
.wrap{position:absolute;left:84px;right:84px;top:0;bottom:120px;display:flex;flex-direction:column;justify-content:center}
.brand{display:flex;align-items:center;gap:22px;margin-bottom:34px}
.brand img{width:96px;height:96px;border-radius:26px;box-shadow:0 16px 40px rgba(8,7,30,.4)}
.brand span{font-size:64px;font-weight:850;letter-spacing:-.03em}
h1{font-size:80px;line-height:1.02;font-weight:850;letter-spacing:-.04em}
h1 em{font-style:normal;color:#B7B5FF}
p{margin-top:26px;font-size:32px;color:rgba(255,255,255,.82)}
</style></head><body>${wave}
<div class="wrap"><div class="brand"><img src="${icon}" alt=""><span>Rhynote</span></div>
<h1>${v.h1}</h1><p>${v.sub}</p></div></body></html>`;

(async () => {
  const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
  const p = await browser.newPage({ viewport: { width: 1200, height: 630 } });
  for (const v of VARIANTS) {
    await p.setContent(page(v), { waitUntil: 'load' });
    await p.screenshot({ path: path.join(PUBLIC, v.file) });
    console.log('écrit public/' + v.file);
  }
  await p.setViewportSize({ width: 180, height: 180 });
  await p.setContent(`<style>body{margin:0}img{display:block;width:180px;height:180px}</style><img src="${icon}">`, { waitUntil: 'load' });
  await p.screenshot({ path: path.join(PUBLIC, 'apple-touch-icon.png') });
  console.log('écrit public/apple-touch-icon.png');
  await browser.close();
})();
