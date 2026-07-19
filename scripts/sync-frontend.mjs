import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { writeFrontendManifest } from './frontend-integrity.mjs';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = path.join(repoRoot, 'web', 'capacitor-ui', 'dist');
const destination = path.join(repoRoot, 'app', 'src', 'main', 'assets', 'public');

if (!fs.existsSync(path.join(source, 'index.html'))) {
  console.error('Frontend build is missing. Run pnpm build first.');
  process.exit(1);
}

fs.rmSync(destination, { recursive: true, force: true });
fs.mkdirSync(destination, { recursive: true });
fs.cpSync(source, destination, { recursive: true });
writeFrontendManifest(repoRoot, destination);
console.log(`Synced ${source} -> ${destination}`);
