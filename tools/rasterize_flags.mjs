import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { Resvg } from "@resvg/resvg-js";
import sharp from "sharp";

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(__dirname, "..");
const SVG_DIR = join(ROOT, "src/main/resources/assets/arena_of_nations/overlay/flags");
const OUT_FIGHTER = join(ROOT, "src/main/resources/assets/arena_of_nations/textures/gui/flags");
const OUT_HD = join(ROOT, "src/main/resources/assets/arena_of_nations/textures/gui/flags_hd");
const CONTACT_FIGHTER = join(__dirname, "generated", "fighter_flags_contact_sheet.png");
const CONTACT_HD = join(__dirname, "generated", "base_flags_hd_contact_sheet.png");

const COUNTRY_ORDER = [
  "ru", "ua", "by", "kz", "lt", "pl", "il", "am", "uz", "tj",
  "ge", "kg", "tm", "md", "az", "lv", "al", "bg", "cn", "us",
];

const SETS = [
  { out: OUT_FIGHTER, contact: CONTACT_FIGHTER, w: 128, h: 80, label: "fighter" },
  { out: OUT_HD, contact: CONTACT_HD, w: 256, h: 160, label: "hd" },
];

async function rasterizeSet(set) {
  mkdirSync(set.out, { recursive: true });
  mkdirSync(dirname(set.contact), { recursive: true });
  const images = [];

  for (const id of COUNTRY_ORDER) {
    const svgPath = join(SVG_DIR, `${id}.svg`);
    if (!existsSync(svgPath)) {
      console.error(`Missing SVG: ${svgPath}`);
      process.exit(1);
    }
    const svg = readFileSync(svgPath, "utf8");
    const resvg = new Resvg(svg, {
      fitTo: { mode: "width", value: set.w },
      background: "transparent",
    });
    const rendered = resvg.render();
    const png = await sharp(rendered.asPng()).resize(set.w, set.h, { fit: "fill" }).png().toBuffer();
    writeFileSync(join(set.out, `${id}.png`), png);
    images.push({ id, buffer: png });
    console.log(`OK ${set.label} ${id}.png ${set.w}x${set.h}`);
  }

  const cols = 5;
  const pad = 8;
  const labelH = 16;
  const cellW = set.w + pad;
  const cellH = set.h + labelH + pad;
  const sheet = sharp({
    create: {
      width: cols * cellW + pad,
      height: Math.ceil(COUNTRY_ORDER.length / cols) * cellH + pad,
      channels: 4,
      background: { r: 32, g: 32, b: 32, alpha: 1 },
    },
  });

  const composites = [];
  for (let i = 0; i < COUNTRY_ORDER.length; i++) {
    const col = i % cols;
    const row = Math.floor(i / cols);
    composites.push({
      input: images[i].buffer,
      left: pad + col * cellW,
      top: pad + row * cellH,
    });
  }
  await sheet.composite(composites).png().toFile(set.contact);
  console.log(`Contact sheet (${set.label}) -> ${set.contact}`);
}

for (const set of SETS) {
  await rasterizeSet(set);
}
