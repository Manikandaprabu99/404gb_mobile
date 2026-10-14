// Regenerates the Android launcher, splash, themed and notification icons from brand/mark.svg.
// Run with `npm run assets` and commit the output.

import { readFile, writeFile, mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import sharp from "sharp";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const res = path.join(root, "android", "app", "src", "main", "res");

// "Lumen" mark geometry (keep in sync with brand/mark.svg): blue gradient + sheen, white glyph.
const GRADIENT = ["#1660EB", "#0A2878"];
const SHEEN = { cx: 300, cy: 140, r: 820, opacity: 0.22 };
const GLYPH = "#FFFFFF";
const PHOTO_INK = "#1A5BE0";
const BACK = { x: 348, y: 261, w: 432, h: 356, r: 76, rotate: 9, cx: 564, cy: 439, stroke: 40, opacity: 0.62 };
const FRONT = { x: 228, y: 397, w: 472, h: 392, r: 84 };
const KNOCKOUT = 28; // gap cut into the back frame around the front print
const MOUNTAIN = "M228 789V712L372 584Q392 566 412 584L532 692L580 650Q598 634 616 650L700 724V789Z";
const RIDGE_FOOT = 724; // lowest ridge point: the print's visible bottom in one colour
const SUN = { cx: 582, cy: 505, r: 38 };
const MARK_RADIUS = 232;

// Farthest artwork point from the centre, as a fraction of the canvas (safe circle radius is 1/3).
const FOREGROUND_REACH = 0.31;
const SPLASH_REACH = 0.32;
const ROUND_REACH = 0.33;

const DENSITIES = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };

const fmt = (n) => {
  const s = n.toFixed(2);
  return s.includes(".") ? s.replace(/\.?0+$/, "") : s;
};

// A rounded rect's four corner discs (optionally rotated); reach and bounds are exact over them.
function cornerDiscs({ x, y, w, h, r }, grow = 0, rotate) {
  const th = rotate ? (rotate.deg * Math.PI) / 180 : 0;
  return [
    [x + r, y + r],
    [x + w - r, y + r],
    [x + w - r, y + h - r],
    [x + r, y + h - r],
  ].map(([px, py]) => {
    if (!rotate) return { x: px, y: py, r: r + grow };
    const [dx, dy] = [px - rotate.cx, py - rotate.cy];
    return {
      x: rotate.cx + dx * Math.cos(th) - dy * Math.sin(th),
      y: rotate.cy + dx * Math.sin(th) + dy * Math.cos(th),
      r: r + grow,
    };
  });
}

const backDiscs = (stroke) => cornerDiscs(BACK, stroke / 2, { deg: BACK.rotate, cx: BACK.cx, cy: BACK.cy });
const reach = (discs) => Math.max(...discs.map((d) => Math.hypot(d.x - 512, d.y - 512) + d.r));
const GLYPH_REACH = reach([...cornerDiscs(FRONT), ...backDiscs(BACK.stroke)]);
const MARK_REACH = (512 - MARK_RADIUS) * Math.SQRT2 + MARK_RADIUS;

const FOREGROUND_SCALE = (FOREGROUND_REACH * 1024) / GLYPH_REACH;
const ROUND_SCALE = (ROUND_REACH * 1024) / GLYPH_REACH;
const SPLASH_SCALE = (SPLASH_REACH * 1024) / MARK_REACH;

const svgDoc = (px, body) =>
  `<svg xmlns="http://www.w3.org/2000/svg" width="${px}" height="${px}" viewBox="0 0 1024 1024">${body}</svg>`;

const scaled = (s, body) => `<g transform="translate(512 512) scale(${+s.toFixed(4)}) translate(-512 -512)">${body}</g>`;

const backgroundDefs =
  `<linearGradient id="bg" x1="0" y1="0" x2="1024" y2="1024" gradientUnits="userSpaceOnUse">` +
  `<stop offset="0" stop-color="${GRADIENT[0]}"/><stop offset="1" stop-color="${GRADIENT[1]}"/></linearGradient>` +
  `<radialGradient id="sheen" cx="${SHEEN.cx}" cy="${SHEEN.cy}" r="${SHEEN.r}" gradientUnits="userSpaceOnUse">` +
  `<stop offset="0" stop-color="#FFFFFF" stop-opacity="${SHEEN.opacity}"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></radialGradient>`;

const background = (shape) => `<defs>${backgroundDefs}</defs>${shape("url(#bg)")}${shape("url(#sheen)")}`;
const fullBleed = (fill) => `<rect width="1024" height="1024" fill="${fill}"/>`;
const fullCircle = (fill) => `<circle cx="512" cy="512" r="512" fill="${fill}"/>`;

// The glyph in mark coordinates; the knockout is a mask so it stays exact when scaled over the background.
const glyphMarkup = () => {
  const [k, f, b] = [KNOCKOUT, FRONT, BACK];
  return (
    `<defs><mask id="knockout" maskUnits="userSpaceOnUse" x="0" y="0" width="1024" height="1024">` +
    `<rect width="1024" height="1024" fill="#FFFFFF"/>` +
    `<rect x="${f.x - k}" y="${f.y - k}" width="${f.w + 2 * k}" height="${f.h + 2 * k}" rx="${f.r + k}" fill="#000000"/></mask>` +
    `<clipPath id="front"><rect x="${f.x}" y="${f.y}" width="${f.w}" height="${f.h}" rx="${f.r}"/></clipPath></defs>` +
    `<g mask="url(#knockout)"><rect x="${b.x}" y="${b.y}" width="${b.w}" height="${b.h}" rx="${b.r}" ` +
    `transform="rotate(${b.rotate} ${b.cx} ${b.cy})" fill="none" stroke="${GLYPH}" stroke-opacity="${b.opacity}" stroke-width="${b.stroke}"/></g>` +
    `<g clip-path="url(#front)"><rect x="${f.x}" y="${f.y}" width="${f.w}" height="${f.h}" fill="${GLYPH}"/>` +
    `<path d="${MOUNTAIN}" fill="${PHOTO_INK}"/><circle cx="${SUN.cx}" cy="${SUN.cy}" r="${SUN.r}" fill="${PHOTO_INK}"/></g>`
  );
};

const roundIcon = (px) => svgDoc(px, background(fullCircle) + scaled(ROUND_SCALE, glyphMarkup()));
const foregroundIcon = (px) => svgDoc(px, background(fullBleed) + scaled(FOREGROUND_SCALE, glyphMarkup()));

async function writePng(svg, rel) {
  const out = path.join(res, rel);
  await mkdir(path.dirname(out), { recursive: true });
  await sharp(Buffer.from(svg)).png({ compressionLevel: 9 }).toFile(out);
  console.log("wrote", path.relative(root, out));
}

// Clockwise rounded rect; ccw winds it the other way so it cuts a nonzero-rule hole.
function roundRectPath(x, y, w, h, r, ccw = false) {
  const a = `A${fmt(r)},${fmt(r)} 0 0 ${ccw ? 0 : 1} `;
  if (ccw) {
    return (
      `M${fmt(x + r)},${fmt(y)}${a}${fmt(x)},${fmt(y + r)}V${fmt(y + h - r)}${a}${fmt(x + r)},${fmt(y + h)}` +
      `H${fmt(x + w - r)}${a}${fmt(x + w)},${fmt(y + h - r)}V${fmt(y + r)}${a}${fmt(x + w - r)},${fmt(y)}Z`
    );
  }
  return (
    `M${fmt(x + r)},${fmt(y)}H${fmt(x + w - r)}${a}${fmt(x + w)},${fmt(y + r)}V${fmt(y + h - r)}${a}${fmt(x + w - r)},${fmt(y + h)}` +
    `H${fmt(x + r)}${a}${fmt(x)},${fmt(y + h - r)}V${fmt(y + r)}${a}${fmt(x + r)},${fmt(y)}Z`
  );
}

const circlePath = ({ cx, cy, r }) =>
  `M${fmt(cx - r)},${fmt(cy)}A${fmt(r)},${fmt(r)} 0 1 0 ${fmt(cx + r)},${fmt(cy)}` +
  `A${fmt(r)},${fmt(r)} 0 1 0 ${fmt(cx - r)},${fmt(cy)}Z`;

// One-colour front print: the rounded rect above the mountain ridge, with the sun as an even-odd hole.
function frontSilhouette() {
  const { x, y, w, h, r } = FRONT;
  // Where the right slope (616,650)->(700,724) leaves through the bottom-right corner arc.
  const [p0, p1, c] = [[616, 650], [700, RIDGE_FOOT], [x + w - r, y + h - r]];
  const [dx, dy, fx, fy] = [p1[0] - p0[0], p1[1] - p0[1], p0[0] - c[0], p0[1] - c[1]];
  const qa = dx * dx + dy * dy;
  const qb = 2 * (fx * dx + fy * dy);
  const t = (-qb + Math.sqrt(qb * qb - 4 * qa * (fx * fx + fy * fy - r * r))) / (2 * qa);
  const [ex, ey] = [p0[0] + dx * t, p0[1] + dy * t];
  return (
    `M${x},712V${y + r}A${r},${r} 0 0 1 ${x + r},${y}H${x + w - r}A${r},${r} 0 0 1 ${x + w},${y + r}` +
    `V${y + h - r}A${r},${r} 0 0 1 ${fmt(ex)},${fmt(ey)}` +
    `L616,650Q598,634 580,650L532,692L412,584Q392,566 372,584Z` +
    circlePath(SUN)
  );
}

// Minimal VectorDrawable writer: each node renders itself at an indent.
const vdAttrs = (o, pad) =>
  Object.entries(o)
    .map(([k, v]) => `\n${pad}    android:${k}="${typeof v === "number" ? fmt(v) : v}"`)
    .join("");
const vdPath = (o) => (pad) => `${pad}<path${vdAttrs(o, pad)} />`;
const vdClip = (d) => (pad) => `${pad}<clip-path${vdAttrs({ pathData: d }, pad)} />`;
const vdGroup = (o, children) => (pad) =>
  `${pad}<group${vdAttrs(o, pad)}>\n${children.map((c) => c(pad + "    ")).join("\n")}\n${pad}</group>`;

// White glyph: stroked back frame (rotated, clipped clear of the grown print) plus the print silhouette.
function glyphVector({ backStroke, knockout }) {
  const [f, b, k] = [FRONT, BACK, knockout];
  const clip = "M-1024,-1024H2048V2048H-1024Z" + roundRectPath(f.x - k, f.y - k, f.w + 2 * k, f.h + 2 * k, f.r + k, true);
  return [
    vdGroup({ name: "back" }, [
      vdClip(clip),
      vdGroup({ rotation: b.rotate, pivotX: b.cx, pivotY: b.cy }, [
        vdPath({
          fillColor: "#00000000",
          strokeColor: "#FFFFFFFF",
          strokeWidth: backStroke,
          pathData: roundRectPath(b.x, b.y, b.w, b.h, b.r),
        }),
      ]),
    ]),
    vdPath({ fillColor: "#FFFFFFFF", fillType: "evenOdd", pathData: frontSilhouette() }),
  ];
}

function vectorXml({ sizeDp, viewport, body }) {
  return `<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by scripts/generate-brand-assets.mjs from brand/mark.svg. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="${sizeDp}dp"
    android:height="${sizeDp}dp"
    android:viewportWidth="${fmt(viewport)}"
    android:viewportHeight="${fmt(viewport)}">
${body("    ")}
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
  const markBody = mark.replace(/^<svg[^>]*>/, "").replace(/<\/svg>$/, "");
  // Splash: 288dp canvas, 192dp visible circle over the dark app background.
  const splashIcon = (px) => svgDoc(px, scaled(SPLASH_SCALE, markBody));

  for (const [dpi, k] of Object.entries(DENSITIES)) {
    await writePng(squareIcon(48 * k), `mipmap-${dpi}/ic_launcher.png`);
    await writePng(roundIcon(48 * k), `mipmap-${dpi}/ic_launcher_round.png`);
    await writePng(foregroundIcon(108 * k), `mipmap-${dpi}/ic_launcher_foreground.png`);
    await writePng(splashIcon(288 * k), `drawable-${dpi}/splash_icon.png`);
  }

  // Themed (monochrome) launcher icon: same layout as the foreground layer.
  await writeText(
    "drawable/ic_launcher_monochrome.xml",
    vectorXml({
      sizeDp: 108,
      viewport: 1024,
      body: vdGroup(
        { pivotX: 512, pivotY: 512, scaleX: FOREGROUND_SCALE, scaleY: FOREGROUND_SCALE },
        glyphVector({ backStroke: BACK.stroke, knockout: KNOCKOUT })
      ),
    })
  );

  // Notification icon: bolder glyph centred in a tight viewport with the 1dp-of-24 padding.
  const boldStroke = 60;
  const discs = backDiscs(boldStroke);
  const minX = Math.min(FRONT.x, ...discs.map((d) => d.x - d.r));
  const maxX = Math.max(FRONT.x + FRONT.w, ...discs.map((d) => d.x + d.r));
  const minY = Math.min(FRONT.y, ...discs.map((d) => d.y - d.r));
  const maxY = Math.max(RIDGE_FOOT, ...discs.map((d) => d.y + d.r));
  const side = Math.ceil((Math.max(maxX - minX, maxY - minY) * 24) / 22);
  await writeText(
    "drawable/ic_stat_404gb.xml",
    vectorXml({
      sizeDp: 24,
      viewport: side,
      body: vdGroup(
        { translateX: side / 2 - (minX + maxX) / 2, translateY: side / 2 - (minY + maxY) / 2 },
        glyphVector({ backStroke: boldStroke, knockout: 36 })
      ),
    })
  );
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
