// Sunshine Terminal DebianCrosvmEngine — primary production provider.
// Full Debian guest via crosvm: glibc, apt, systemd, Docker — the real home
// for Sunshine workloads. Config-driven; first run provisions
// ~/.sunshine/vm/sunshine-vm.json with defaults and reports exactly which
// artifacts (image, kernel, crosvm, ssh) are still missing.
//
// Hardened transport:
// - Ephemeral host-side SSH port per boot (no static 2222).
// - Per-boot session token (auth.js), validated per exec by the guest-side
//   sunshine-exec shim; token travels via ssh stdin, never argv.
// - Agent-origin commands run under the sunshine-agent.slice (cgroup caps).
// - Policy gate: Tier 2+ requires a confirmed verdict from the caller.
import fs from 'fs/promises';
import path from 'path';
import { randomInt } from 'crypto';
import { fileURLToPath } from 'url';
import { defineProvider, capability, okResult, failResult, PROVIDER_IDS } from './provider.js';
import { enforcePolicy } from './policy.js';
import { issueSessionToken, loadSessionToken } from './auth.js';
import { capOutputLines, RING_BUFFER_LINES } from './bridge.js';
import { probeThermal, liveThermalSys, degradedResources, DEFAULT_THERMAL_CONFIG } from './thermal.js';
import { liveBridge } from './bridge.js';

export const DEBIAN_CONFIG_FILE = 'sunshine-vm.json';
export const DEBIAN_STATE_FILE = 'vm-state.json';
export const GUEST_BUNDLE_VERSION = 2;

const BUNDLE_DIR = path.join(path.dirname(path.dirname(fileURLToPath(import.meta.url))), '..', 'guest');
// Payload files shipped inside bundle.json; provision.sh travels separately
// (it is the installer that consumes the bundle).
export const GUEST_INSTALLER = 'provision.sh';
export const GUEST_FILES = ['sunshine-exec', 'sunshine-vsock-agent.py', 'sunshine-vsock-agent.service', 'sunshine-agent.slice', 'nftables-sunshine.nft'];

export function defaultDebianConfig(vmDir) {
  return {
    guest: 'debian',
    image: path.join(vmDir, 'debian.img'),
    kernel: path.join(vmDir, 'Image'),
    cmdline: 'root=/dev/vda1 rw console=hvc0',
    memoryMb: 2048,
    cpus: 2,
    cid: 42,
    ssh: {
      user: 'sunshine',
      port: 2222, // base only — boot picks an ephemeral port (see sshPortMin/Max)
      key: path.join(vmDir, 'id_sunshine'),
      connectTimeoutSec: 10,
    },
    sshPortMin: 22000,
    sshPortMax: 22999,
    lockdown: false, // opt-in outbound allowlist profile (default open)
    allowedOutbound: [],
    // Thermal overrides (optional — merged over thermal.js defaults):
    // { batteryLowPct, warmTempC, severeTempC, degradedCpus, degradedMemoryMb, renice }
    thermal: {},
    crosvmArgs: [],
    bootTimeoutMs: 120000,
    execTimeoutMs: 60000,
    outputMaxLines: 10000, // ring-buffer cap for guest stdout/stderr
    consoleLog: path.join(vmDir, 'debian-console.log'),
    execLog: path.join(vmDir, 'debian-exec.log'),
  };
}

export function pickEphemeralPort(min, max) {
  return randomInt(min, max + 1);
}

export function createDebianEngine(deps = liveBridge) {
  const vmDir = () => deps.defaultVmDir();
  const configFile = () => path.join(vmDir(), DEBIAN_CONFIG_FILE);
  const stateFile = () => path.join(vmDir(), DEBIAN_STATE_FILE);

  async function loadOrProvisionConfig() {
    if (typeof deps.readJsonFile === 'function') {
      const res = await deps.readJsonFile(configFile());
      if (res.ok) return { config: { ...defaultDebianConfig(vmDir()), ...res.data }, provisioned: false };
      if (res.reason === 'corrupt') {
        throw new Error(
          `Corrupt VM config at ${configFile()}${res.backup ? ` (backed up to ${res.backup})` : ''}. Fix or delete it, then re-run.`,
        );
      }
      // 'missing' (or unreadable) → provision fresh below.
    } else {
      const existing = await deps.readJsonSafe(configFile(), null);
      if (existing) return { config: { ...defaultDebianConfig(vmDir()), ...existing }, provisioned: false };
    }
    await deps.ensureVmDir(vmDir());
    const fresh = defaultDebianConfig(vmDir());
    await deps.writeJsonSafe(configFile(), fresh);
    return { config: fresh, provisioned: true };
  }

  async function loadState() {
    return (await deps.readJsonSafe(stateFile(), null)) || {};
  }

  // Best-effort deprioritization of the crosvm host process. Never fails
  // the caller — returns true when the renice landed.
  async function reniceGuest(pid, config) {
    if (!pid) return false;
    const level = config.thermal?.renice ?? DEFAULT_THERMAL_CONFIG.renice;
    const res = await deps.runHost('renice', ['-n', String(level), '-p', String(pid)], { timeoutMs: 8000 });
    return res.ok;
  }

  async function saveState(patch) {
    const current = await loadState();
    const next = { ...current, ...patch, updatedAt: new Date().toISOString() };
    await deps.writeJsonSafe(stateFile(), next);
    return next;
  }

  return defineProvider({
    id: PROVIDER_IDS.debian,
    label: 'Debian (crosvm)',

    async probe() {
      let loaded;
      try {
        loaded = await loadOrProvisionConfig();
      } catch (err) {
        return capability({
          id: PROVIDER_IDS.debian,
          label: 'Debian (crosvm)',
          available: false,
          reason: 'config-corrupt',
          remediation: err.message,
          details: { configFile: configFile() },
        });
      }
      const { config, provisioned } = loaded;
      const [imageOk, kernelOk] = await Promise.all([
        deps.fileExists(config.image),
        deps.fileExists(config.kernel),
      ]);
      const crosvm = await deps.whichBinary('crosvm');
      const ssh = await deps.whichBinary('ssh');
      const state = await loadState();
      const thermal = await probeThermal(deps.thermal || liveThermalSys, config.thermal || {});
      const details = {
        configFile: configFile(),
        provisioned,
        image: config.image, imagePresent: imageOk,
        kernel: config.kernel, kernelPresent: kernelOk,
        crosvm, sshPresent: Boolean(ssh),
        cid: config.cid,
        sshPort: state.sshPort || null,
        guestProvisioned: state.guestVersion === GUEST_BUNDLE_VERSION,
        lockdown: Boolean(config.lockdown),
        thermal, powerSaver: thermal.powerSaver,
      };
      const missing = [];
      if (!imageOk) missing.push(`guest image (${config.image})`);
      if (!kernelOk) missing.push(`guest kernel (${config.kernel})`);
      if (!crosvm) missing.push('crosvm binary');
      if (missing.length > 0) {
        return capability({
          id: PROVIDER_IDS.debian,
          label: 'Debian (crosvm)',
          available: false,
          reason: 'image-not-provisioned',
          remediation: `Missing: ${missing.join(', ')}. Place a Debian rootfs + kernel at the paths in ${configFile()} (see Android 16 Linux Terminal guest images), then re-run.`,
          details,
        });
      }
      return capability({ id: PROVIDER_IDS.debian, label: 'Debian (crosvm)', available: true, details });
    },

    sshArgs(config, remoteCmd, port) {
      return [
        '-i', config.ssh.key,
        '-p', String(port || config.ssh.port),
        '-o', 'StrictHostKeyChecking=no',
        '-o', `ConnectTimeout=${config.ssh.connectTimeoutSec}`,
        '-o', 'BatchMode=yes',
        `${config.ssh.user}@127.0.0.1`,
        remoteCmd,
      ];
    },

    // Wrap a command for the guest shim: stdin carries
    //   line1: session token
    //   line2: origin (human|agent)
    //   rest:  command text
    // The shim validates the token, re-checks Tier 3, and applies the
    // agent slice for agent-origin commands.
    shimInput(token, origin, command) {
      return `${token}\n${origin}\n${command}`;
    },

    // Ring-buffer the guest output: runaway loops drop old lines instead
    // of growing host memory without bound.
    capGuestOutput(res, config) {
      const cap = Number(config.outputMaxLines) || RING_BUFFER_LINES;
      const out = capOutputLines(res.stdout || '', cap);
      const err = capOutputLines(res.stderr || '', cap);
      return {
        stdout: out.text, stderr: err.text,
        droppedLines: out.droppedLines + err.droppedLines,
      };
    },

    async guestSsh(config, port, remoteCmd, { input = null, timeoutMs = null } = {}) {
      return deps.runHost('ssh', this.sshArgs(config, remoteCmd, port), {
        timeoutMs: timeoutMs || config.execTimeoutMs,
        input,
      });
    },

    // Lightweight liveness probe for the heartbeat monitor: constant
    // payload, short timeout, no policy gate, no audit trail (it fires
    // every 2.5s — auditing each ping would drown the log).
    async ping() {
      const { config } = await loadOrProvisionConfig().catch(() => ({ config: null }));
      if (!config) return { ok: false, reason: 'no-config' };
      const state = await loadState();
      if (!(await deps.whichBinary('ssh'))) return { ok: false, reason: 'no-ssh-client' };
      const port = state.sshPort || config.ssh.port;
      const res = await this.guestSsh(config, port, 'true', { timeoutMs: 5000 });
      if (!res.ok) return { ok: false, reason: res.reason || 'ping-failed' };
      return { ok: true };
    },

    async exec(command, { origin = 'human', verdict = null, bypassShim = false } = {}) {
      const gate = enforcePolicy(command, { origin, verdict });
      if (!gate.allowed) {
        return failResult('approval-required', {
          tier: gate.tier, tierName: gate.tierName, origin,
          remediation: `Tier ${gate.tier} (${gate.tierName}) command needs explicit approval before execution.`,
        });
      }
      const cap = await this.probe();
      if (!cap.available) return failResult(cap.reason, { remediation: cap.remediation, details: cap.details });
      const { config } = await loadOrProvisionConfig();
      if (!cap.details.sshPresent) {
        return failResult('no-ssh-client', { remediation: 'OpenSSH client not found on host; install openssh to reach the guest.' });
      }
      const state = await loadState();
      const port = state.sshPort || config.ssh.port;
      const tokenRec = await loadSessionToken(deps);
      if (!tokenRec) {
        return failResult('no-session-token', {
          remediation: 'No session token issued. Boot the guest (`scli vm boot`) to rotate one in.',
        });
      }

      // Thermal enforcement: deprioritize the guest when the device is hot
      // or low on battery (best-effort; never fails the exec itself).
      const thermal = await probeThermal(deps.thermal || liveThermalSys, config.thermal || {});
      let reniced = false;
      if (thermal.throttled && state.pid) {
        reniced = await reniceGuest(state.pid, config);
      }
      const powerNote = { powerSaver: thermal.powerSaver, throttled: thermal.throttled, reniced };

      if (state.guestVersion === GUEST_BUNDLE_VERSION && !bypassShim) {
        const res = await this.guestSsh(config, port, 'sunshine-exec', {
          input: this.shimInput(tokenRec.token, origin, command),
        });
        if (!res.ok) {
          if ((res.stderr || '').includes('SUNSHINE-AUTH-DENIED')) {
            return failResult('guest-auth-denied', {
              remediation: 'Guest rejected the session token. Re-boot to rotate (`scli vm boot`).',
              stdout: res.stdout, stderr: res.stderr, code: res.code,
            });
          }
          return failResult('guest-exec-failed', {
            remediation: `Guest unreachable — is it booted? Try \`scli vm boot\`, then \`scli vm logs\`. (ssh: ${res.reason})`,
            stdout: res.stdout, stderr: res.stderr, code: res.code,
          });
        }
        return okResult({ ...this.capGuestOutput(res, config), code: res.code, channel: 'shim', ...powerNote });
      }

      // Downgraded channel: guest predates the shim bundle. Works, but the
      // per-exec token check and slice confinement are absent — say so.
      const res = await this.guestSsh(config, port, command);
      if (!res.ok) {
        return failResult('guest-exec-failed', {
          remediation: `Guest unreachable — is it booted? Try \`scli vm boot\`, then \`scli vm logs\`. (ssh: ${res.reason})`,
          stdout: res.stdout, stderr: res.stderr, code: res.code,
        });
      }
      return okResult({ ...this.capGuestOutput(res, config), code: res.code, channel: 'direct-ssh', downgraded: true, ...powerNote });
    },

    async shell() {
      const cap = await this.probe();
      if (!cap.available) return failResult(cap.reason, { remediation: cap.remediation });
      if (!cap.details.sshPresent) {
        return failResult('no-ssh-client', { remediation: 'OpenSSH client not found on host; install openssh to reach the guest.' });
      }
      const { config } = await loadOrProvisionConfig();
      const state = await loadState();
      // Interactive: hand the terminal to ssh directly (inherits stdio).
      try {
        const { spawnSync } = await import('child_process');
        const res = spawnSync('ssh', this.sshArgs(config, '', state.sshPort || config.ssh.port), { stdio: 'inherit' });
        return okResult({ code: res.status });
      } catch (err) {
        return failResult('shell-failed', { remediation: err.message });
      }
    },

    async boot() {
      const cap = await this.probe();
      if (!cap.available) return failResult(cap.reason, { remediation: cap.remediation, details: cap.details });
      const { config } = await loadOrProvisionConfig();
      const port = pickEphemeralPort(config.sshPortMin, config.sshPortMax);
      // Adaptive throttling: hot / low-battery devices boot small.
      const thermal = await probeThermal(deps.thermal || liveThermalSys, config.thermal || {});
      const sizing = degradedResources(config, thermal);
      const args = [
        'run',
        '--cid', String(config.cid),
        '--mem', String(sizing.memoryMb),
        '--cpus', String(sizing.cpus),
        '--kernel', config.kernel,
        '--cmdline', config.cmdline,
        '--serial', `file:${config.consoleLog}`,
        config.image,
        ...config.crosvmArgs,
      ];
      const res = await deps.launchDetachedAsync(cap.details.crosvm, args, {
        stdoutFile: config.execLog,
        stderrFile: config.execLog,
      });
      if (!res.ok) return failResult('boot-failed', { remediation: res.reason });
      const issued = await issueSessionToken(deps);
      await saveState({ sshPort: port, tokenId: issued.tokenId, pid: res.pid, bootedAt: new Date().toISOString() });
      if (thermal.throttled && res.pid) {
        await reniceGuest(res.pid, config);
      }
      const saverNote = sizing.degraded
        ? ` [Power Saver: throttled (${thermal.reasons.join('; ')}) — ${sizing.cpus} CPU / ${sizing.memoryMb}MB]`
        : '';
      return okResult({
        pid: res.pid,
        powerSaver: thermal.powerSaver,
        note: `Booting (CID ${config.cid}, channel port ${port}, token ${issued.tokenId}). Follow with \`scli vm logs\`; then \`scli vm provision\` once ssh is up.${saverNote}`,
      });
    },

    // Push the guest/ bundle and activate it: token into tmpfs, shim on
    // PATH, slice + nftables installed. Idempotent — safe to re-run.
    async provision() {
      const cap = await this.probe();
      if (!cap.available) return failResult(cap.reason, { remediation: cap.remediation, details: cap.details });
      const { config } = await loadOrProvisionConfig();
      if (!cap.details.sshPresent) {
        return failResult('no-ssh-client', { remediation: 'OpenSSH client not found on host; install openssh to reach the guest.' });
      }
      const state = await loadState();
      const port = state.sshPort || config.ssh.port;
      const tokenRec = await loadSessionToken(deps);
      if (!tokenRec) {
        return failResult('no-session-token', { remediation: 'Boot first (`scli vm boot`) so a session token exists to install.' });
      }
      let installer;
      let bundle;
      try {
        installer = await fs.readFile(path.join(BUNDLE_DIR, GUEST_INSTALLER), 'utf8');
        bundle = {};
        for (const name of GUEST_FILES) {
          bundle[name] = await fs.readFile(path.join(BUNDLE_DIR, name), 'utf8');
        }
      } catch (err) {
        return failResult('bundle-missing', { remediation: `Guest bundle unreadable: ${err.message}` });
      }
      const sendInstaller = await this.guestSsh(config, port,
        `mkdir -p /tmp/sunshine-guest && cat > /tmp/sunshine-guest/provision.sh`,
        { input: installer, timeoutMs: 30000 });
      if (!sendInstaller.ok) {
        return failResult('provision-transfer-failed', {
          remediation: `Guest not reachable on port ${port}. Wait for boot, check \`scli vm logs\`. (ssh: ${sendInstaller.reason})`,
        });
      }
      const transfer = await this.guestSsh(config, port,
        `cat > /tmp/sunshine-guest/bundle.json`,
        { input: JSON.stringify(bundle), timeoutMs: 30000 });
      if (!transfer.ok) {
        return failResult('provision-transfer-failed', {
          remediation: `Guest not reachable on port ${port}. Wait for boot, check \`scli vm logs\`. (ssh: ${transfer.reason})`,
        });
      }
      const apply = await this.guestSsh(config, port,
        `sudo bash /tmp/sunshine-guest/provision.sh`,
        { input: `${tokenRec.token}\n${GUEST_BUNDLE_VERSION}\n${config.lockdown ? 'lockdown' : 'open'}\n`, timeoutMs: 120000 });
      if (!apply.ok) {
        return failResult('provision-apply-failed', {
          remediation: 'Bundle transferred but activation failed. See guest output above.',
          stdout: apply.stdout, stderr: apply.stderr, code: apply.code,
        });
      }
      await saveState({ guestVersion: GUEST_BUNDLE_VERSION });
      return okResult({ note: `Guest bundle v${GUEST_BUNDLE_VERSION} active (token ${tokenRec.id}, network: ${config.lockdown ? 'lockdown' : 'open'}).` });
    },

    async shutdown() {
      const { config } = await loadOrProvisionConfig();
      // Explicit user action (already confirmed at the CLI layer); bypasses
      // the shim because sunshine-exec denies sudo/poweroff by design.
      // Channel still gated by SSH key auth + policy gate inside exec().
      const res = await this.exec('sudo poweroff', { origin: 'human', verdict: 'confirmed', bypassShim: true });
      if (!res.ok) {
        return failResult('shutdown-failed', {
          remediation: res.remediation || 'Guest did not respond to poweroff.',
          stdout: res.stdout, stderr: res.stderr,
        });
      }
      return okResult({ note: 'Shutdown requested via guest poweroff.' });
    },

    async logs({ tail = 50 } = {}) {
      const { config } = await loadOrProvisionConfig();
      const consoleLog = await deps.readTextTail(config.consoleLog, tail);
      if (!consoleLog.ok) {
        return failResult(consoleLog.reason, {
          remediation: `No console output yet at ${config.consoleLog}. Boot first with \`scli vm boot\`.`,
        });
      }
      return okResult({ lines: consoleLog.lines, file: config.consoleLog });
    },
  });
}
