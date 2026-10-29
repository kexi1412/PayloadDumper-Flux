'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { build, Platform, Arch } = require('electron-builder');
const root = path.resolve(__dirname, '..');
const repository = path.dirname(root);
async function main() {
  for (const relative of ['dist/index.html', 'engine/build/libs/flux-engine.jar', 'runtime/bin/java.exe', 'assets/icon.ico']) {
    if (!fs.existsSync(path.join(root, relative))) throw new Error(`Missing ${relative}; run build, engine:build and runtime:build first.`);
  }
  if (process.platform !== 'win32') throw new Error('Build the Windows bundle on Windows so the bundled Java runtime matches.');
  let container;
  let alias;
  let project = root;
  try {
    if (/[^\x00-\x7f]/.test(repository)) {
      const parent = path.resolve(process.env.FLUX_BUILD_TEMP || process.env.PUBLIC || 'C:\\Users\\Public');
      if (/[^\x00-\x7f]/.test(parent)) throw new Error('FLUX_BUILD_TEMP must be an ASCII directory.');
      container = fs.mkdtempSync(path.join(parent, 'flux-pc-package-'));
      alias = path.join(container, 'project');
      fs.symlinkSync(repository, alias, 'junction');
      project = path.join(alias, 'desktop');
    }
    // Native NSIS uses a memory-mapped temporary file; keep it in the same
    // writable ASCII workspace path rather than an unrelated system Temp folder.
    process.env.TEMP = process.env.TMP = path.join(project, '.cache', 'package-tmp');
    process.env.ELECTRON_BUILDER_CACHE = path.join(project, '.cache', 'electron-builder');
    process.env.CSC_IDENTITY_AUTO_DISCOVERY = 'false';
    fs.mkdirSync(process.env.TEMP, { recursive: true });
    const files = await build({ projectDir: project, targets: Platform.WINDOWS.createTarget(['portable', 'zip'], Arch.x64), publish: 'never' });
    const version = require('../package.json').version;
    for (const extension of ['exe', 'zip']) {
      const file = path.join(root, 'release', `PayloadDumper-Flux-PC-${version}-x64.${extension}`);
      if (!fs.existsSync(file) || fs.statSync(file).size < 1024 * 1024) throw new Error(`Missing or incomplete package: ${file}`);
    }
    for (const file of files) console.log(file);
  } finally {
    if (alias) {
      if (!fs.lstatSync(alias).isSymbolicLink() || fs.realpathSync(alias).toLowerCase() !== fs.realpathSync(repository).toLowerCase()) throw new Error('Refusing to remove an unexpected package alias.');
      fs.unlinkSync(alias);
    }
    if (container) {
      if (!path.basename(container).startsWith('flux-pc-package-') || fs.lstatSync(container).isSymbolicLink()) throw new Error('Unexpected package container.');
      fs.rmdirSync(container);
    }
  }
}
main().catch(error => { console.error(error.stack || error.message); process.exit(1); });
