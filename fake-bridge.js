// Shared fakes for AVF engine tests — no shell, no device, no AVF hardware.
export function makeFakeBridge({
  getpropRelease = '16',
  virtFlag = 'true',
  binaries = {},
  files = {},
  configs = {},
  runHandler = null,
} = {}) {
  const written = {};
  const launches = [];
  const runs = [];
  const audit = [];

  return {
    _written: written,
    _launches: launches,
    _runs: runs,
    _audit: audit,

    defaultVmDir() {
      return '/tmp/fake-sunshine-vm';
    },
    async ensureVmDir(dir) {
      return dir;
    },
    async runHost(command, args = [], opts = {}) {
      runs.push({ command, args, opts });
      if (runHandler) return runHandler(command, args);
      if (command === 'getprop' || command.endsWith('/getprop')) {
        const prop = args[0] || '';
        if (prop === 'ro.build.version.release') {
          return getpropRelease === null
            ? { ok: false, code: 1, stdout: '', stderr: '', reason: 'exit-1' }
            : { ok: true, code: 0, stdout: `${getpropRelease}\n`, stderr: '', reason: null };
        }
        if (prop === 'ro.virtualization.support.enabled') {
          return { ok: true, code: 0, stdout: `${virtFlag}\n`, stderr: '', reason: null };
        }
      }
      return { ok: true, code: 0, stdout: '', stderr: '', reason: null };
    },
    async launchDetachedAsync(command, args = [], _opts = {}) {
      launches.push({ command, args });
      return { ok: true, pid: 4242, reason: null };
    },
    async fileExists(p) {
      return Boolean(files[p]);
    },
    async whichBinary(name) {
      if (Object.hasOwn(binaries, name)) return binaries[name];
      return null;
    },
    async readJsonSafe(filePath, fallback = null) {
      if (Object.hasOwn(configs, filePath)) return configs[filePath];
      if (Object.hasOwn(written, filePath)) return written[filePath];
      return fallback;
    },
    async readJsonFile(filePath) {
      if (Object.hasOwn(configs, filePath)) return { ok: true, data: configs[filePath] };
      if (Object.hasOwn(written, filePath)) return { ok: true, data: written[filePath] };
      return { ok: false, reason: 'missing' };
    },
    async writeJsonSafe(filePath, data) {
      written[filePath] = data;
      return { ok: true, reason: null };
    },
    async readTextTail(_filePath, _maxLines = 50) {
      return { ok: false, lines: [], reason: 'no-log-file' };
    },
    async appendAuditLog(entry) {
      audit.push(entry);
      return { ok: true };
    },
  };
}
