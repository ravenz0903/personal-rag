const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const root = path.resolve(__dirname, '../..');
const destination = path.join(root, 'desktop/resources');
function run(command, args, cwd = root) {
  const result = spawnSync(command, args, { cwd, stdio: 'inherit', shell: false, windowsHide: true });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${path.basename(command)} failed`);
}
if (process.platform !== 'win32') throw new Error('This build packages the Windows runtime. Build it on Windows.');
fs.mkdirSync(destination, { recursive: true });
run('cmd.exe', ['/d', '/c', 'mvnw.cmd -DskipTests package']);
run('cmd.exe', ['/d', '/c', 'npm run build'], path.join(root, 'frontend'));
fs.copyFileSync(path.join(root, 'target/study-rag-0.0.1-SNAPSHOT.jar'), path.join(destination, 'backend.jar'));
fs.cpSync(path.join(root, 'frontend/dist'), path.join(destination, 'frontend'), { recursive: true });
const settings = spawnSync('java', ['-XshowSettings:properties', '-version'], { encoding: 'utf8', windowsHide: true });
const javaHome = process.env.JAVA_HOME || settings.stderr.match(/java.home\s*=\s*(.+)/)?.[1]?.trim();
if (!javaHome) throw new Error('Install JDK 21 or later to build the bundled runtime.');
const runtime = path.join(destination, 'runtime');
if (!fs.existsSync(path.join(runtime, 'bin/java.exe'))) {
  run(path.join(javaHome, 'bin/jlink.exe'), ['--add-modules', 'java.se,jdk.unsupported,jdk.crypto.ec,jdk.zipfs', '--strip-debug', '--no-header-files', '--no-man-pages', '--output', runtime]);
}
console.log('Desktop resources are ready. Run npm start, npm run package, or npm run dist in desktop.');
