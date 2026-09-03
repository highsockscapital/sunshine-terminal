// Sunshine Terminal MicrodroidEngine — stock AVF fallback provider.
// Uses the platform `vm` tool (com.android.virt APEX) when present.
// Stateless by design: Microdroid runs short-lived payloads, so exec()
// requires a configured payload; without one it reports honestly instead
// of pretending to succeed.
import path from 'path';
import { defineProvider, capability, okResult, failResult, PROVIDER_IDS } from './provider.js';
import { liveBridge } from './bridge.js';

export const VM_TOOL_CANDIDATES = ['/apex/com.android.virt/bin/vm'];
export const MICRODROID_CONFIG_FILE = 'microdroid.json';

async function detectAndroid(deps) {
  const getprop = (await deps.whichBinary('getprop')) || 'getprop';
  const release = await deps.runHost(getprop, ['ro.build.version.release'], { timeoutMs: 8000 });
  const virtFlag = await deps.runHost(getprop, ['ro.virtualization.support.enabled'], { timeoutMs: 8000 });
  const androidVersion = release.ok ? release.stdout.trim() || 'Unknown' : 'Unknown';
  const flagValue = virtFlag.ok ? virtFlag.stdout.trim() : '';
  const supported = flagValue === 'true' || Number(androidVersion) >= 14;
  return { androidVersion, supported };
}

async function findVmTool(deps) {
  for (const candidate of VM_TOOL_CANDIDATES) {
    if (await deps.fileExists(candidate)) return candidate;
  }
  return deps.whichBinary('vm');
}

export function createMicrodroidEngine(deps = liveBridge) {
  const vmDir = () => deps.defaultVmDir();
  const configFile = () => path.join(vmDir(), MICRODROID_CONFIG_FILE);

  async function loadConfig() {
    return (await deps.readJsonSafe(configFile(), null)) || {};
  }

  return defineProvider({
    id: PROVIDER_IDS.microdroid,
    label: 'Microdroid (stock AVF)',

    async probe() {
      const { androidVersion, supported } = await detectAndroid(deps);
      const vmTool = await findVmTool(deps);
      const config = await loadConfig();
      const details = { androidVersion, virtualizationSupported: supported, vmTool, hasPayload: Boolean(config.payload?.binary) };
      if (!supported) {
        return capability({
          id: PROVIDER_IDS.microdroid,
          label: 'Microdroid (stock AVF)',
          available: false,
          reason: 'avf-unsupported',
          remediation: 'Stock AVF needs Android 14+ with the com.android.virt APEX.',
          details,
        });
      }
      if (!vmTool) {
        return capability({
          id: PROVIDER_IDS.microdroid,
          label: 'Microdroid (stock AVF)',
          available: false,
          reason: 'no-vm-tool',
          remediation: 'Platform vm tool not found. On-device AVF access may need a userdebug build or the Linux Terminal app.',
          details,
        });
      }
      return capability({
        id: PROVIDER_IDS.microdroid,
        label: 'Microdroid (stock AVF)',
        available: true,
        details,
      });
    },

    // One-shot payload execution via `vm run-app`. Requires config.payload
    // { app, idsig, instanceImg, binary } in ~/.sunshine/vm/microdroid.json.
    // NOTE: `command` here is advisory (recorded in audit) — the guest runs
    // the configured payload binary, not the string. Injection surface is
    // the payload config (host file), so no Tier gate applies; vm.js still
    // audits every call.
    async exec(command) {
      const cap = await this.probe();
      if (!cap.available) return failResult(cap.reason, { remediation: cap.remediation, details: cap.details });
      const config = await loadConfig();
      const payload = config.payload || {};
      if (!payload.binary) {
        return failResult('no-payload', {
          remediation: `No Microdroid payload configured. Add one to ${configFile()} as { "payload": { "app": "...", "idsig": "...", "instanceImg": "...", "binary": "..." } }.`,
        });
      }
      // run-app takes positional app/idsig/instanceImg — omitting any of
      // them would silently shift the rest. Fail fast instead.
      const missing = ['app', 'idsig', 'instanceImg'].filter((k) => !payload[k]);
      if (missing.length > 0) {
        return failResult('payload-incomplete', {
          remediation: `Microdroid payload missing: ${missing.join(', ')}. Set them in ${configFile()} — all three positionals are mandatory.`,
        });
      }
      const args = [
        'run-app',
        '--log', path.join(vmDir(), 'microdroid.log'),
        ...(payload.console ? ['--console', path.join(vmDir(), 'microdroid-console.log')] : []),
        payload.app,
        payload.idsig,
        payload.instanceImg,
        '--payload-binary-name', payload.binary,
      ];
      const res = await deps.runHost(cap.details.vmTool, args, { timeoutMs: config.timeoutMs || 120000 });
      if (!res.ok) {
        return failResult('payload-failed', {
          remediation: 'Payload run failed — check microdroid.log in the VM dir. MANAGE_VIRTUAL_MACHINE grant is commonly required.',
          stdout: res.stdout, stderr: res.stderr, code: res.code,
        });
      }
      return okResult({ stdout: res.stdout, stderr: res.stderr, code: res.code, note: `Requested guest exec: ${command}` });
    },

    async shell() {
      const cap = await this.probe();
      if (!cap.available) return failResult(cap.reason, { remediation: cap.remediation });
      return failResult('no-interactive-shell', {
        remediation: 'Microdroid has no interactive shell; configure a Debian guest for shell access (`scli vm shell` targets Debian).',
      });
    },

    async boot() {
      const cap = await this.probe();
      if (!cap.available) return failResult(cap.reason, { remediation: cap.remediation });
      return okResult({ note: 'Microdroid boots on demand per payload run; nothing persistent to boot.' });
    },

    async shutdown() {
      return okResult({ note: 'Microdroid guests are ephemeral; nothing persistent to shut down.' });
    },

    async logs({ tail = 50 } = {}) {
      const log = await deps.readTextTail(path.join(vmDir(), 'microdroid.log'), tail);
      if (!log.ok) return failResult(log.reason, { remediation: 'No Microdroid runs yet — logs appear after the first payload exec.' });
      return okResult({ lines: log.lines });
    },
  });
}
