import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { writeFrontendManifest } from './frontend-integrity.mjs';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const argumentMap = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  argumentMap.set(process.argv[index], process.argv[index + 1]);
}
const source = path.resolve(
  argumentMap.get('--source') ||
    path.join(repoRoot, 'web', 'capacitor-ui', 'dist'),
);
const sourceRoot = path.dirname(source);
const destination = path.join(repoRoot, 'app', 'src', 'main', 'assets', 'public');

if (!fs.existsSync(path.join(source, 'index.html'))) {
  console.error('Frontend build is missing. Run pnpm build first.');
  process.exit(1);
}

fs.rmSync(destination, { recursive: true, force: true });
fs.mkdirSync(destination, { recursive: true });
fs.cpSync(source, destination, { recursive: true });
writeFrontendManifest(sourceRoot, destination);
console.log(`Synced ${source} -> ${destination}`);
