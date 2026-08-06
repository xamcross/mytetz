// Pulls the four Latin font subsets out of the design bundle.
// Run once: node tools/extract-fonts.mjs
// The output files are committed. This tool exists so that the extraction is reproducible.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';

const BUNDLE = '../docs/mytetz-design-reference.html';
const OUT = 'public/fonts';

/** Each UUID names one payload in the bundle manifest. Each name is the file this tool writes. */
const WANTED = {
  '6dac9c78-6a37-4a2e-8601-1ef1def4fe3c': 'figtree-latin.woff2',
  '93c25b2b-8352-4fdb-abf3-efd57f357d58': 'figtree-latin-ext.woff2',
  'f55d5df1-39ca-4bf1-9254-7533812bc4d2': 'fredoka-latin.woff2',
  '7e6aa8e7-c790-4be7-a86f-921665c978f8': 'fredoka-latin-ext.woff2',
};

const html = readFileSync(BUNDLE, 'utf8');
const match = html.match(/<script type="__bundler\/manifest">\s*([\s\S]*?)\s*<\/script>/);
if (!match) throw new Error('the bundle holds no manifest');
const manifest = JSON.parse(match[1]);

mkdirSync(OUT, { recursive: true });
for (const [uuid, name] of Object.entries(WANTED)) {
  const entry = manifest[uuid];
  if (!entry) throw new Error(`the manifest holds no entry ${uuid}`);
  if (!entry.mime.startsWith('font/')) throw new Error(`${uuid} is ${entry.mime}, not a font`);
  let bytes = Buffer.from(entry.data, 'base64');
  if (entry.compressed) bytes = gunzipSync(bytes);
  // Every woff2 file starts with the signature "wOF2". A payload that does not is the wrong one.
  if (bytes.subarray(0, 4).toString('latin1') !== 'wOF2') {
    throw new Error(`${uuid} is not a woff2 file`);
  }
  writeFileSync(`${OUT}/${name}`, bytes);
  console.log(`${name} ${bytes.length} bytes`);
}
