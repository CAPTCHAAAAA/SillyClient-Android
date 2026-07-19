import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const manifestName = 'sillyclient-build.json';
const ignoredSourceDirectories = new Set([
  'dist',
  'node_modules',
  '.vite-temp',
  '.microcompact',
  '.todo',
  '.plan',
]);
const textExtensions = new Set([
  '.css',
  '.html',
  '.js',
  '.json',
  '.md',
  '.mjs',
  '.toml',
  '.ts',
  '.tsx',
  '.txt',
  '.yaml',
  '.yml',
]);

function collectFiles(root, { sourceTree = false } = {}) {
  const files = [];

  function visit(directory) {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      if (sourceTree && ignoredSourceDirectories.has(entry.name)) continue;
      if (entry.isSymbolicLink()) continue;

      const absolutePath = path.join(directory, entry.name);
      if (entry.isDirectory()) {
        visit(absolutePath);
      } else if (entry.isFile() && entry.name !== manifestName) {
        files.push(absolutePath);
      }
    }
  }

  visit(root);
  return files.sort((left, right) => {
    const normalizedLeft = left.split(path.sep).join('/');
    const normalizedRight = right.split(path.sep).join('/');
    return normalizedLeft < normalizedRight ? -1 : normalizedLeft > normalizedRight ? 1 : 0;
  });
}

function normalizedContent(filePath) {
  const content = fs.readFileSync(filePath);
  const extension = path.extname(filePath).toLowerCase();
  const basename = path.basename(filePath);
  if (textExtensions.has(extension) || basename === '.npmrc') {
    return Buffer.from(content.toString('utf8').replace(/\r\n?/g, '\n'));
  }
  return content;
}

function digestTree(root, options) {
  const hash = crypto.createHash('sha256');
  for (const filePath of collectFiles(root, options)) {
    const relativePath = path.relative(root, filePath).split(path.sep).join('/');
    const content = normalizedContent(filePath);
    hash.update(relativePath);
    hash.update('\0');
    hash.update(String(content.length));
    hash.update('\0');
    hash.update(content);
    hash.update('\0');
  }
  return hash.digest('hex');
}

export function frontendSourceDigest(repositoryRoot) {
  return digestTree(path.join(repositoryRoot, 'web', 'capacitor-ui'), { sourceTree: true });
}

export function frontendAssetsDigest(assetsDirectory) {
  return digestTree(assetsDirectory);
}

export function writeFrontendManifest(repositoryRoot, assetsDirectory) {
  const manifest = {
    schema: 1,
    sourceSha256: frontendSourceDigest(repositoryRoot),
    assetsSha256: frontendAssetsDigest(assetsDirectory),
  };
  fs.writeFileSync(
    path.join(assetsDirectory, manifestName),
    `${JSON.stringify(manifest, null, 2)}\n`,
  );
}

export function verifyFrontendManifest(repositoryRoot, assetsDirectory) {
  const manifestPath = path.join(assetsDirectory, manifestName);
  if (!fs.existsSync(manifestPath)) {
    throw new Error(`Missing ${manifestName}. Run pnpm run build:android.`);
  }

  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  const sourceSha256 = frontendSourceDigest(repositoryRoot);
  const assetsSha256 = frontendAssetsDigest(assetsDirectory);
  if (manifest.schema !== 1) throw new Error('Unsupported frontend manifest schema.');
  if (manifest.sourceSha256 !== sourceSha256) {
    throw new Error('Committed Android assets do not match the current frontend source.');
  }
  if (manifest.assetsSha256 !== assetsSha256) {
    throw new Error('Committed Android assets were changed without regenerating the manifest.');
  }

  return manifest;
}
