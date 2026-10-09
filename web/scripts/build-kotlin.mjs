import { spawnSync } from 'node:child_process';
import { cpSync, existsSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

const projectRoot = fileURLToPath(new URL('../../', import.meta.url));
const webRoot = join(projectRoot, 'web');
const windows = process.platform === 'win32';
const wrapper = join(projectRoot, windows ? 'kotlin.bat' : 'kotlin');
// Kotlin Toolchain 0.13 only schedules JS linking for the release variant.
const args = ['build', '-m', 'web', '-v', 'release'];
const result = windows
  ? spawnSync('cmd.exe', ['/d', '/c', `""${wrapper}" ${args.join(' ')}"`], {
      cwd: projectRoot, stdio: 'inherit',
    })
  : spawnSync(wrapper, args, { cwd: projectRoot, stdio: 'inherit' });

if (result.error) throw result.error;
if (result.status !== 0) process.exit(result.status ?? 1);

// The release artifact contains the entry point and all per-file JS modules.
const output = join(projectRoot, 'build', 'artifacts', 'CompiledWebArtifact',
  'webjsrelease', 'kotlin-output');
if (!existsSync(output)) {
  throw new Error(`Kotlin/JS release output is missing: ${output}`);
}
const modules = readdirSync(output).filter((name) => name.endsWith('.mjs'));
const entry = modules.includes('web.mjs') ? 'web.mjs'
  : modules.length === 1 ? modules[0] : undefined;
if (!entry || !existsSync(join(output, entry))) {
  throw new Error(`Cannot identify the Kotlin/JS entry point in ${output}`);
}

const destination = join(webRoot, '.kotlin-js');
rmSync(destination, { recursive: true, force: true });
cpSync(output, destination, { recursive: true });
writeFileSync(join(destination, 'entry.mjs'), `import './${entry}';\n`);
