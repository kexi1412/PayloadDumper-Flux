'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const repository = path.dirname(root);
const windows = process.platform === 'win32';
if (!process.env.JAVA_HOME || !fs.existsSync(path.join(process.env.JAVA_HOME, 'bin', windows ? 'java.exe' : 'java'))) {
  console.error('Set JAVA_HOME to a JDK 21 installation before building the engine.');
  process.exit(1);
}
let container = null;
let alias = null;
let buildRoot = root;
let exitCode = 1;
try {
  // protoc on Windows misinterprets non-ASCII absolute arguments. A temporary
  // junction keeps all reads/writes in the original project, without duplicating
  // source files, changing permissions, or changing system codepages.
  if (windows && /[^\x00-\x7f]/.test(repository)) {
    const parent = path.resolve(process.env.FLUX_BUILD_TEMP || process.env.PUBLIC || 'C:\\Users\\Public');
    if (/[^\x00-\x7f]/.test(parent) || !fs.statSync(parent).isDirectory()) throw new Error('FLUX_BUILD_TEMP must be an existing ASCII-only directory.');
    container = fs.mkdtempSync(path.join(parent, 'flux-pc-path-'));
    alias = path.join(container, 'project');
    fs.symlinkSync(repository, alias, 'junction');
    buildRoot = path.join(alias, 'desktop');
    console.log(`Using temporary ASCII project alias: ${buildRoot}`);
  }
  const javaTemp = path.join(buildRoot, '.cache', 'java-tmp');
  fs.mkdirSync(javaTemp, { recursive: true });
  const cacheHome = process.env.GRADLE_USER_HOME || path.join(root, '.cache', 'gradle');
  const relativeCache = path.relative(repository, cacheHome);
  const mappedCache = alias && !relativeCache.startsWith('..') && !path.isAbsolute(relativeCache)
    ? path.join(alias, relativeCache) : cacheHome;
  const env = { ...process.env, GRADLE_USER_HOME: mappedCache,
    JAVA_TOOL_OPTIONS: `${process.env.JAVA_TOOL_OPTIONS || ''} "-Djava.io.tmpdir=${javaTemp}"`.trim(),
  };
  const wrapper = path.resolve(buildRoot, windows ? '../gradlew.bat' : '../gradlew');
  const command = windows ? `"${wrapper}"` : wrapper;
  const result = spawnSync(command, ['-p', 'engine', 'test', 'fatJar', '--no-daemon'], {
    cwd: buildRoot, stdio: 'inherit', shell: windows, env,
  });
  if (result.error) console.error(result.error.message);
  exitCode = result.status ?? 1;
  if (!exitCode) console.log(`Engine ready: ${path.join(root, 'engine/build/libs/flux-engine.jar')}`);
} catch (error) {
  console.error(error.stack || error.message);
  exitCode = 1;
} finally {
  if (alias && fs.existsSync(alias)) {
    if (!fs.lstatSync(alias).isSymbolicLink() || fs.realpathSync(alias).toLowerCase() !== fs.realpathSync(repository).toLowerCase()) {
      throw new Error(`Refusing to unlink unexpected project alias: ${alias}`);
    }
    // Unlink only the junction itself. Never recursively delete a project alias.
    fs.unlinkSync(alias);
  }
  if (container) {
    if (!path.basename(container).startsWith('flux-pc-path-') || fs.lstatSync(container).isSymbolicLink()) throw new Error('Unexpected alias container.');
    fs.rmdirSync(container); // Must be empty; refuses to remove unrelated files.
    console.log('Removed temporary ASCII project alias.');
  }
}
process.exitCode = exitCode;
