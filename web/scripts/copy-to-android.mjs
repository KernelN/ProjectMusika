import { cpSync, existsSync, mkdirSync, rmSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const distDir = resolve(__dirname, '../dist');
const androidAssetsDir = resolve(__dirname, '../../app/src/main/assets/web');

if (!existsSync(distDir)) {
  console.error('web/dist not found; run npm run build first');
  process.exit(1);
}

if (existsSync(androidAssetsDir)) {
  rmSync(androidAssetsDir, { recursive: true, force: true });
}
mkdirSync(androidAssetsDir, { recursive: true });
cpSync(distDir, androidAssetsDir, { recursive: true });
console.log(`Copied Musika Web bundle to ${androidAssetsDir}`);
