// Sunshine Terminal VSOCK heartbeat monitor + guest recovery.
// The host pings the guest on a steady cadence (default every 2.5s); two
// consecutive misses mean the guest is gone (crash, LMK kill of the host
// side confusing state, or channel loss). Instead of leaving sockets
// hanging, the host revokes the session token, clears stale channel state,
// and — only with explicit opt-in — reboots.
//
// Transport note: the ping rides whatever channel the provider exposes
// today (SSH `true`) and moves unchanged onto the VSOCK binary ping/ack
// packet later. Miss semantics are transport-agnostic.
// heartbeat recovery.
import path from 'node:path';
import { revokeSessionToken } from './auth.js';

export const DEFAULT_HEARTBEAT_INTERVAL_MS = 2500;
export const DEFAULT_HEARTBEAT_MAX_MISSES = 2;

// One ping through the provider. Providers expose ping() as a lightweight,
// unaudited liveness check (constant payload, no policy surface).
export async function pingGuest(provider) {
  if (typeof provider?.ping !== 'function') {
    return { ok: false, reason: 'no-ping', latencyMs: null };
  }
  const started = Date.now();
  try {
    const res = await provider.ping();
    return { ok: Boolean(res.ok), reason: res.ok ? null : res.reason || 'ping-failed', latencyMs: Date.now() - started };
  } catch (err) {
    return { ok: false, reason: err.message, latencyMs: null };
  }
}

// Recovery: never leave an orphaned guest behind.
// 1. Revoke the session token (a half-dead guest keeps no authority).
// 2. Clear stale channel state (pid, port, token id) — config + guest
//    bundle version survive.
// 3. Audit the recovery.
// 4. Reboot ONLY with explicit opt-in ({ autoBoot: true }); default is to
//    report and let the human (or a supervised agent) decide.
export async function recoverGuest(deps, provider, { autoBoot = false } = {}) {
  const file = path.join(deps.defaultVmDir(), 'vm-state.json');
  await revokeSessionToken(deps).catch(() => ({ ok: false }));
  const state = (await deps.readJsonSafe(file, null)) || {};
  const { pid: _pid, sshPort: _port, tokenId: _tok, ...kept } = state;
  await deps.writeJsonSafe(file, {
    ...kept,
    pid: null,
    sshPort: null,
    tokenId: null,
    lastRecovery: new Date().toISOString(),
  });
  await deps.appendAuditLog({ event: 'guest-recovery', provider: provider?.id || 'unknown', autoBoot, command: 'heartbeat recovery' });

  if (autoBoot && typeof provider?.boot === 'function') {
    const booted = await provider.boot();
    return { ok: booted.ok, rebooted: booted.ok, reason: booted.reason || null, note: booted.note || null };
  }
  return {
    ok: true,
    rebooted: false,
    reason: null,
    remediation: 'Guest state fenced. Run `scli vm boot` to start clean, then `scli vm provision`.',
  };
}

// Tick-driven monitor. Production drives tick() on an interval; tests call
// tick() directly (no timers, no flakes).
export function createHeartbeatMonitor({
  ping,
  intervalMs = DEFAULT_HEARTBEAT_INTERVAL_MS,
  maxMisses = DEFAULT_HEARTBEAT_MAX_MISSES,
  onHeartbeat = null,
  onDrop = null,
  onRecover = null,
  recover = null,
} = {}) {
  if (typeof ping !== 'function') throw new Error('heartbeat monitor needs a ping() function');
  const state = {
    running: false,
    consecutiveMisses: 0,
    totalPings: 0,
    totalMisses: 0,
    lastOk: null,
    recovered: false,
  };
  let timer = null;

  async function tick() {
    const res = await ping();
    state.totalPings += 1;
    if (res.ok) {
      state.consecutiveMisses = 0;
      state.lastOk = new Date().toISOString();
      state.recovered = false;
      if (onHeartbeat) await onHeartbeat({ ...res, state: { ...state } });
      return { ok: true, state: { ...state } };
    }
    state.totalMisses += 1;
    state.consecutiveMisses += 1;
    if (onDrop) await onDrop({ ...res, state: { ...state } });
    if (state.consecutiveMisses >= maxMisses && !state.recovered) {
      state.recovered = true;
      let recovery = { ok: false, reason: 'no-recover-handler' };
      if (typeof recover === 'function') {
        recovery = await recover();
      }
      if (onRecover) await onRecover({ recovery, state: { ...state } });
      return { ok: false, recovered: true, recovery, state: { ...state } };
    }
    return { ok: false, recovered: false, state: { ...state } };
  }

  return {
    state,
    tick,
    start() {
      if (state.running) return;
      state.running = true;
      timer = setInterval(() => {
        tick().catch(() => {});
      }, intervalMs);
      if (typeof timer.unref === 'function') timer.unref();
    },
    stop() {
      state.running = false;
      if (timer) clearInterval(timer);
      timer = null;
    },
  };
}
