const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto'), { spawnSync } = require('node:child_process');
const { stageAi } = require('./stage-ai.cjs');
const root = path.resolve(__dirname, '../..');
function run(command, args, cwd = root) {
  const result = spawnSync(command, args, { cwd, stdio: 'inherit', windowsHide: true });
  if (result.error) throw result.error; if (result.status !== 0) throw Error('Beta build step failed');
}
async function main() {
  const exportFolder = process.argv[2]; if (!exportFolder) throw Error('Supply the exact qualified export directory. Unqualified AI cannot enter a beta installer.');
  if (process.platform !== 'win32' || process.arch !== 'x64') throw Error('Build on Windows x64');
  run(process.execPath, [path.join(__dirname, 'build-inference-host.cjs')]);
  const manifest = await stageAi(path.resolve(root, exportFolder));
  run(process.execPath, [path.join(__dirname, 'prepare.cjs')]);
  run(process.execPath, [path.join(root, 'desktop/node_modules/electron-builder/out/cli/cli.js'), '--win', '--dir', '--x64'], path.join(root, 'desktop'));
  const source = path.join(root, 'desktop/release/win-unpacked');
  const compiler = path.join(root, 'target/installer-tools/inno/ISCC.exe');
  if (!fs.existsSync(compiler)) throw Error('Install the verified project-local Inno compiler before building');
  const output = path.join(root, 'target/private-beta'); fs.mkdirSync(output, { recursive: true });
  run(compiler, ['/DAppSource=' + source, '/DBetaOutput=' + output, '/DBetaVersion=0.3.0-beta.1', path.join(root, 'desktop/installer/folio.iss')]);
  const installer = path.join(output, 'Folio-0.3.0-beta.1-Windows-x64.exe');
  if (fs.statSync(installer).size >= 4 * 1024 ** 3) throw Error('Single installer exceeds Windows executable size limit');
  const hash = crypto.createHash('sha256'); for await (const c of fs.createReadStream(installer)) hash.update(c);
  const digest = hash.digest('hex'); fs.writeFileSync(installer + '.sha256', digest + '  ' + path.basename(installer) + '\n');
  fs.writeFileSync(path.join(output, 'build-record.json'), JSON.stringify({ version: '0.3.0-beta.1', sha256: digest, bytes: fs.statSync(installer).size,
    modelQualification: manifest.qualification, target: 'Windows 11 x64; RTX 4060 Laptop 8GB; 16GB RAM',
    installerSmokePassed: false, publicRelease: false }, null, 2) + '\n');
  console.log('Private beta built. Installation acceptance is still required.');
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
