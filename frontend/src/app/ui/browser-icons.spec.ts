import { readFileSync } from 'node:fs';

/**
 * The icon set the browser loads, checked as bytes.
 *
 * `public/icon.svg` is the artwork every current browser takes. `public/favicon.ico` is the
 * fallback, and it is also what a crawler fetches from `/favicon.ico` whatever the page declares.
 * `frontend/tools/make-icons.mjs` builds the `.ico` from the SVG. This file proves that the
 * committed result is a real icon and not a placeholder.
 *
 * `e2e/layout.spec.ts` proves the separate claim that a real browser can fetch each one.
 */
describe('the browser icon set', () => {
  const PNG_MAGIC = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

  it('ships a favicon.ico that holds a 16, a 32 and a 48 pixel image', () => {
    const ico = readFileSync('public/favicon.ico');

    expect(ico.readUInt16LE(0)).toBe(0);
    // Type 1 is an icon. Type 2 is a cursor, and a browser refuses one.
    expect(ico.readUInt16LE(2)).toBe(1);
    expect(ico.readUInt16LE(4)).toBe(3);

    const widths = [0, 1, 2].map((i) => ico.readUInt8(6 + i * 16));
    expect(widths).toEqual([16, 32, 48]);
  });

  it('stores each icon size as a PNG, which keeps the file small', () => {
    const ico = readFileSync('public/favicon.ico');

    for (let i = 0; i < ico.readUInt16LE(4); i++) {
      const offset = ico.readUInt32LE(6 + i * 16 + 12);
      expect([...ico.subarray(offset, offset + 8)]).toEqual(PNG_MAGIC);
    }
  });

  it('ships an apple-touch-icon that iOS can read', () => {
    const png = readFileSync('public/apple-touch-icon.png');

    expect([...png.subarray(0, 8)]).toEqual(PNG_MAGIC);
    // The IHDR chunk carries the dimensions. iOS scales any size, but 180 is the size it asks
    // for, so no device has to resample.
    expect(png.readUInt32BE(16)).toBe(180);
    expect(png.readUInt32BE(20)).toBe(180);
  });
});
