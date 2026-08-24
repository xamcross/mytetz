/**
 * Builds `public/favicon.ico` and `public/apple-touch-icon.png` from `public/icon.svg`.
 *
 *   cd frontend && node tools/make-icons.mjs
 *
 * Run it after any change to `public/icon.svg`, then commit the two results.
 *
 * The renderer is the Chromium that Playwright already installs for the end-to-end suite, so
 * this repository needs no image library. ImageMagick and sharp are both absent on the machine
 * this was written on, and neither is worth a dependency for two files that change once a year.
 *
 * `icon.svg` stays the only artwork. The Apple icon needs a square with no rounded corner,
 * because iOS applies its own mask and would clip the tile twice. Rather than hold a second
 * drawing that can drift, this script injects the SVG into a page and overrides the tile with
 * CSS. `x`, `y`, `width`, `height` and `rx` are SVG geometry properties, so CSS can set them.
 */
import { chromium } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const PUBLIC_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', 'public');

/** The sizes Windows and the older browsers ask an `.ico` for. */
const ICO_SIZES = [16, 32, 48];

/** The size iOS asks for. Any other size makes a device resample the image. */
const APPLE_SIZE = 180;

/** Drops the tile's rounded corner, so the artwork fills the square. */
const FULL_BLEED = `
  svg .tile { rx: 0; }
`;

async function render(page, svg, size, extraCss) {
  await page.setContent(`<!doctype html>
    <meta charset="utf-8" />
    <style>
      html, body { margin: 0; padding: 0; }
      #box { width: ${size}px; height: ${size}px; }
      #box svg { display: block; width: 100%; height: 100%; }
      ${extraCss}
    </style>
    <div id="box">${svg}</div>`);
  return await page.locator('#box').screenshot({ type: 'png' });
}

/**
 * Packs the PNGs into one `.ico`.
 *
 * An `.ico` entry may hold a PNG rather than a BMP. Every browser in support reads it, and the
 * file stays about a tenth of the BMP size. The layout is a 6-byte header, then one 16-byte
 * directory entry per image, then the image data.
 */
function packIco(images) {
  const header = Buffer.alloc(6);
  header.writeUInt16LE(0, 0); // reserved
  header.writeUInt16LE(1, 2); // 1 is an icon; 2 is a cursor
  header.writeUInt16LE(images.length, 4);

  const directory = Buffer.alloc(16 * images.length);
  let offset = header.length + directory.length;
  images.forEach(({ size, data }, i) => {
    const at = i * 16;
    // A byte holds 0 to 255, so the format writes 256 as 0. No size here reaches it.
    directory.writeUInt8(size, at);
    directory.writeUInt8(size, at + 1);
    directory.writeUInt8(0, at + 2); // no colour palette
    directory.writeUInt8(0, at + 3); // reserved
    directory.writeUInt16LE(1, at + 4); // one colour plane
    directory.writeUInt16LE(32, at + 6); // 32 bits per pixel
    directory.writeUInt32LE(data.length, at + 8);
    directory.writeUInt32LE(offset, at + 12);
    offset += data.length;
  });

  return Buffer.concat([header, directory, ...images.map((image) => image.data)]);
}

const svg = readFileSync(join(PUBLIC_DIR, 'icon.svg'), 'utf8');
const browser = await chromium.launch();
try {
  const page = await browser.newPage({ deviceScaleFactor: 1 });

  const images = [];
  for (const size of ICO_SIZES) {
    images.push({ size, data: await render(page, svg, size, '') });
  }
  writeFileSync(join(PUBLIC_DIR, 'favicon.ico'), packIco(images));

  writeFileSync(
    join(PUBLIC_DIR, 'apple-touch-icon.png'),
    await render(page, svg, APPLE_SIZE, FULL_BLEED),
  );
} finally {
  await browser.close();
}
console.log(`wrote favicon.ico (${ICO_SIZES.join(', ')}) and apple-touch-icon.png (${APPLE_SIZE})`);
