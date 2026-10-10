// Regenerates the Android launcher icons, splash icon, themed (monochrome)
// icon and notification icon from the 404GB mark (brand/mark.svg).
// Run with `npm run assets` and commit the output.

import { readFile, writeFile, mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import sharp from "sharp";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const res = path.join(root, "android", "app", "src", "main", "res");

const BG = "#060609";
const GRADIENT =
  '<linearGradient id="g" x1="0" y1="0" x2="1" y2="1">' +
  '<stop offset="0" stop-color="#a5b4fc"/><stop offset="1" stop-color="#6366f1"/></linearGradient>';
const TILES = [
  { x: 232, y: 232, s: 256, r: 64 },
  { x: 232, y: 536, s: 256, r: 64 },
  { x: 536, y: 536, s: 256, r: 64 },
];
const MISSING = { x: 546, y: 242, s: 236, r: 56, stroke: 20, dash: 40, gap: 28 };

// Round launcher masks and the Android 12+ splash only show a centred circle
// whose radius is 1/3 of the canvas. The unscaled tiles reach 369.5/1024 from
// the centre, past the adaptive-icon safe zone (33dp of 108dp = 312.9/1024),
// so the mask-facing artwork is shrunk to keep every corner visible.
const SAFE_SCALE = 0.82;

const DENSITIES = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };

const tilesMarkup = () =>
  TILES.map(
    (t) => `<rect x="${t.x}" y="${t.y}" width="${t.s}" height="${t.s}" rx="${t.r}" fill="url(#g)"/>`
  ).join("") +
  `<rect x="${MISSING.x}" y="${MISSING.y}" width="${MISSING.s}" height="${MISSING.s}" rx="${MISSING.r}" ` +
  `fill="none" stroke="#818cf8" stroke-width="${MISSING.stroke}" stroke-dasharray="${MISSING.dash} ${MISSING.gap}"/>`;

const svgDoc = (px, body) =>
  `<svg xmlns="http://www.w3.org/2000/svg" width="${px}" height="${px}" viewBox="0 0 1024 1024">` +
  `<defs>${GRADIENT}</defs>${body}</svg>`;

const roundIcon = (px) => svgDoc(px, `<circle cx="512" cy="512" r="512" fill="${BG}"/>${tilesMarkup()}`);
const safeTiles = (px) =>
  svgDoc(px, `<g transform="translate(512 512) scale(${SAFE_SCALE}) translate(-512 -512)">${tilesMarkup()}</g>`);

async function writePng(svg, rel) {
  const out = path.join(res, rel);
  await mkdir(path.dirname(out), { recursive: true });
  await sharp(Buffer.from(svg)).png({ compressionLevel: 9 }).toFile(out);
  console.log("wrote", path.relative(root, out));
}

const fmt = (n) => {
  const s = n.toFixed(2);
  return s.includes(".") ? s.replace(/\.?0+$/, "") : s;
};

function roundRectPath(x, y, w, h, r) {
  return (
    `M${fmt(x + r)},${fmt(y)}H${fmt(x + w - r)}A${fmt(r)},${fmt(r)} 0 0 1 ${fmt(x + w)},${fmt(y + r)}` +
    `V${fmt(y + h - r)}A${fmt(r)},${fmt(r)} 0 0 1 ${fmt(x + w - r)},${fmt(y + h)}` +
    `H${fmt(x + r)}A${fmt(r)},${fmt(r)} 0 0 1 ${fmt(x)},${fmt(y + h - r)}` +
    `V${fmt(y + r)}A${fmt(r)},${fmt(r)} 0 0 1 ${fmt(x + r)},${fmt(y)}Z`
  );
}

// VectorDrawable has no stroke-dasharray, so dashes are emitted as explicit
// sub-paths walked along the rounded-rect outline the same way SVG does
// (start at (x+r, y), clockwise).
function roundRectOutline(x, y, w, h, r) {
  const L = w - 2 * r;
  const A = (Math.PI / 2) * r;
  const segs = [
    { len: L, line: [x + r, y, x + w - r, y] },
    { len: A, arc: [x + w - r, y + r, -Math.PI / 2] },
    { len: L, line: [x + w, y + r, x + w, y + h - r] },
    { len: A, arc: [x + w - r, y + h - r, 0] },
    { len: L, line: [x + w - r, y + h, x + r, y + h] },
    { len: A, arc: [x + r, y + h - r, Math.PI / 2] },
    { len: L, line: [x, y + h - r, x, y + r] },
    { len: A, arc: [x + r, y + r, Math.PI] },
  ];
  const perimeter = segs.reduce((sum, g) => sum + g.len, 0);
  const at = (dist) => {
    let s = ((dist % perimeter) + perimeter) % perimeter;
    for (const g of segs) {
      if (s <= g.len) {
        if (g.line) {
          const [x0, y0, x1, y1] = g.line;
          const t = g.len === 0 ? 0 : s / g.len;
          return [x0 + (x1 - x0) * t, y0 + (y1 - y0) * t];
        }
        const [cx, cy, a0] = g.arc;
        const th = a0 + s / r;
        return [cx + r * Math.cos(th), cy + r * Math.sin(th)];
      }
      s -= g.len;
    }
    return [x + r, y];
  };
  const dashPath = (s0, s1) => {
    const pts = [];
    for (let s = s0; s < s1; s += 2) pts.push(at(s));
    pts.push(at(s1));
    return "M" + pts.map(([px, py]) => `${fmt(px)},${fmt(py)}`).join("L");
  };
  return { L, A, perimeter, dashPath };
}

function svgPatternDashes(x, y, size, r, dash, gap) {
  const o = roundRectOutline(x, y, size, size, r);
  const parts = [];
  for (let s0 = 0; s0 < o.perimeter; s0 += dash + gap) {
    parts.push(o.dashPath(s0, Math.min(s0 + dash, o.perimeter)));
  }
  return parts.join("");
}

// Eight bold dashes centred on each edge and corner, legible at 24dp.
function symmetricDashes(x, y, size, r, dash) {
  const o = roundRectOutline(x, y, size, size, r);
  const period = o.perimeter / 8;
  const first = o.L / 2 - dash / 2;
  const parts = [];
  for (let i = 0; i < 8; i++) {
    const s0 = first + i * period;
    parts.push(o.dashPath(s0, s0 + dash));
  }
  return parts.join("");
}

function vectorXml({ sizeDp, viewport, fills, strokePath, strokeWidth }) {
  const fillPaths = fills
    .map((d) => `    <path\n        android:fillColor="#FFFFFFFF"\n        android:pathData="${d}" />`)
    .join("\n");
  return `<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by scripts/generate-brand-assets.mjs from brand/mark.svg. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="${sizeDp}dp"
    android:height="${sizeDp}dp"
    android:viewportWidth="${viewport}"
    android:viewportHeight="${viewport}">
${fillPaths}
    <path
        android:fillColor="#00000000"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="${fmt(strokeWidth)}"
        android:pathData="${strokePath}" />
</vector>
`;
}

async function writeText(rel, text) {
  const out = path.join(res, rel);
  await mkdir(path.dirname(out), { recursive: true });
  await writeFile(out, text);
  console.log("wrote", path.relative(root, out));
}

async function main() {
  const mark = (await readFile(path.join(root, "brand", "mark.svg"), "utf8")).trim();
  const squareIcon = (px) => mark.replace("<svg ", `<svg width="${px}" height="${px}" `);

  for (const [dpi, k] of Object.entries(DENSITIES)) {
    await writePng(squareIcon(48 * k), `mipmap-${dpi}/ic_launcher.png`);
    await writePng(roundIcon(48 * k), `mipmap-${dpi}/ic_launcher_round.png`);
    await writePng(safeTiles(108 * k), `mipmap-${dpi}/ic_launcher_foreground.png`);
    // Splash icon canvas is 288dp with a 192dp visible circle: same 1/3
    // radius ratio as adaptive icons, so the same composition fits.
    await writePng(safeTiles(288 * k), `drawable-${dpi}/splash_icon.png`);
  }

  // Themed (monochrome) launcher icon: same layout as the foreground layer.
  const t = (v) => 512 + (v - 512) * SAFE_SCALE;
  const S = SAFE_SCALE;
  await writeText(
    "drawable/ic_launcher_monochrome.xml",
    vectorXml({
      sizeDp: 108,
      viewport: 1024,
      fills: TILES.map((tile) => roundRectPath(t(tile.x), t(tile.y), tile.s * S, tile.s * S, tile.r * S)),
      strokePath: svgPatternDashes(t(MISSING.x), t(MISSING.y), MISSING.s * S, MISSING.r * S, MISSING.dash * S, MISSING.gap * S),
      strokeWidth: MISSING.stroke * S,
    })
  );

  // Notification small icon: tiles cropped to a 600-unit viewport so they
  // fill the 24dp status-bar slot; the missing tile keeps the 256-unit cell
  // footprint with a heavier stroke.
  const n = (v) => v - 212;
  const boldStroke = 36;
  const boldSize = 256 - boldStroke;
  const boldR = 64 - boldStroke / 2;
  await writeText(
    "drawable/ic_stat_404gb.xml",
    vectorXml({
      sizeDp: 24,
      viewport: 600,
      fills: TILES.map((tile) => roundRectPath(n(tile.x), n(tile.y), tile.s, tile.s, tile.r)),
      strokePath: symmetricDashes(n(536 + boldStroke / 2), n(232 + boldStroke / 2), boldSize, boldR, 56),
      strokeWidth: boldStroke,
    })
  );
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
