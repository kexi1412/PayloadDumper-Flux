'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const runtime = path.join(root, 'runtime');
const javaHome = process.env.JAVA_HOME;
if (!javaHome) throw new Error('JAVA_HOME must point to JDK 21.');
const jlink = path.join(javaHome, 'bin', process.platform === 'win32' ? 'jlink.exe' : 'jlink');
if (!fs.existsSync(jlink)) throw new Error(`JDK jlink not found: ${jlink}`);
if (fs.existsSync(runtime)) {
  const resolved = fs.realpathSync(runtime);
  // Only the generated runtime directly under this desktop project may be replaced.
  if (resolved !== runtime || path.dirname(resolved) !== root || path.basename(resolved) !== 'runtime') {
    throw new Error(`Refusing to replace unexpected runtime directory: ${resolved}`);
  }
  console.log(`Replacing generated runtime: ${resolved}`);
  fs.rmSync(resolved, { recursive: true });
}
const result = spawnSync(jlink, [
  '--add-modules', 'java.se,jdk.crypto.ec,jdk.unsupported',
  '--strip-debug', '--no-header-files', '--no-man-pages', '--compress=2',
  '--output', runtime,
], { stdio: 'inherit' });
if (result.error) throw result.error;
if (result.status) process.exit(result.status);
console.log(`Bundled runtime ready: ${runtime}`);
