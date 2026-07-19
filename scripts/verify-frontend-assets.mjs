import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { frontendAssetsDigest, verifyFrontendManifest } from './frontend-integrity.mjs';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const buildDirectory = path.join(repositoryRoot, 'web', 'capacitor-ui', 'dist');
const assetsDirectory = path.join(repositoryRoot, 'app', 'src', 'main', 'assets', 'public');

if (!fs.existsSync(path.join(buildDirectory, 'index.html'))) {
  throw new Error('Fresh frontend build is missing.');
}

const builtAssets = fs.readdirSync(path.join(buildDirectory, 'assets'));
if (!builtAssets.some((name) => name.endsWith('.js')) || !builtAssets.some((name) => name.endsWith('.css'))) {
  throw new Error('Fresh frontend build does not contain JavaScript and CSS assets.');
}

const manifest = verifyFrontendManifest(repositoryRoot, assetsDirectory);
if (manifest.assetsSha256 !== frontendAssetsDigest(buildDirectory)) {
  throw new Error('Committed Android assets do not match the fresh frontend build.');
}
console.log('Frontend build and committed Android assets are valid.');
