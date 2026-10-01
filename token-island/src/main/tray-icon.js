'use strict';

// Icône de la zone de notification dessinée en mémoire (une pilule noire avec un point
// lumineux), pour ne dépendre d'aucun fichier image.
function trayBitmap(size = 32, color = [52, 211, 153]) {
  const buf = Buffer.alloc(size * size * 4);
  const h = size * 0.56;
  const top = (size - h) / 2;
  const r = h / 2;
  const left = size * 0.06;
  const right = size - left;
  const dot = { x: right - r, y: size / 2, r: r * 0.55 };

  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const px = x + 0.5;
      const py = y + 0.5;
      // Distance signée à la pilule (rectangle à bouts ronds).
      const cx = Math.min(Math.max(px, left + r), right - r);
      const d = Math.hypot(px - cx, py - (top + r)) - r;
      const alpha = Math.max(0, Math.min(1, 0.5 - d));
      const dd = Math.hypot(px - dot.x, py - dot.y) - dot.r;
      const dotA = Math.max(0, Math.min(1, 0.5 - dd));
      const rgb = [
        Math.round(20 * (1 - dotA) + color[0] * dotA),
        Math.round(20 * (1 - dotA) + color[1] * dotA),
        Math.round(24 * (1 - dotA) + color[2] * dotA),
      ];
      const i = (y * size + x) * 4;
      // Format BGRA attendu par nativeImage.createFromBitmap.
      buf[i] = rgb[2];
      buf[i + 1] = rgb[1];
      buf[i + 2] = rgb[0];
      buf[i + 3] = Math.round(alpha * 255);
    }
  }
  return { buffer: buf, width: size, height: size };
}

module.exports = { trayBitmap };
