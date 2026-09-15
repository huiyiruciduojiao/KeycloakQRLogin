import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const app = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const resources = path.resolve(app, '../src/main/resources/theme/qrlogin/account/resources');
const generated = path.join(resources, 'avatar-dist');
// Only this build's dedicated output directory is replaced. Native theme assets remain untouched.
if (path.dirname(generated) !== resources || path.basename(generated) !== 'avatar-dist') throw Error('Unsafe output path');
await fs.rm(generated, { recursive: true, force: true });
await fs.cp(path.join(app, 'dist'), generated, { recursive: true });
const manifest = JSON.parse(await fs.readFile(path.join(app, 'dist/.vite/manifest.json'), 'utf8'));
for (const chunk of Object.values(manifest)) {
  chunk.file = `avatar-dist/${chunk.file}`;
  if (chunk.css) chunk.css = chunk.css.map(file => `avatar-dist/${file}`);
  if (chunk.assets) chunk.assets = chunk.assets.map(file => `avatar-dist/${file}`);
}
await fs.mkdir(path.join(resources, '.vite'), { recursive: true });
await fs.writeFile(path.join(resources, '.vite/manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
