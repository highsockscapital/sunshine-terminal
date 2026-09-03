// Sunshine Terminal AVF facade — provider selection + status/exec entry points.
// Selection order: Debian (primary) → Microdroid (stock fallback) → honest
// unavailable report. Exports keep their historic names so index.js routing
// is unchanged: checkAVFStatus(), executeAVF(), plus lifecycle helpers.
import { info, success, warning, error, colors } from '../ui.js';
import { unavailableProvider, PROVIDER_IDS } from './provider.js';
import { decideExec, TIERS } from './policy.js';
import { probeThermal, liveThermalSys } from './thermal.js';
import { pingGuest, recoverGuest, createHeartbeatMonitor, DEFAULT_HEARTBEAT_INTERVAL_MS, DEFAULT_HEARTBEAT_MAX_MISSES } from './heartbeat.js';
import { createDebianEngine } from './debian.js';
import { createMicrodroidEngine } from './microdroid.js';
import { liveBridge } from './bridge.js';
import path from 'node:path';

// Legacy callers passed the deps object positionally: executeAVF(cmd, deps).
// (Kept inline in executeAVF; no helper needed.)

// Interactive approval. T2: [y/N]. T3: type YES (explicit).
// Non-TTY without autoApprove: T1 allowed, T2/T3 denied (automation-safe).
export async function confirmRisk(decision, command) {
  const { stdin, stdout } = await import('node:process');
  if (!stdin.isTTY) {
    return { confirmed: false, reason: 'non-interactive' };
  }
  const { createInterface } = await import('node:readline/promises');
  const rl = createInterface({ input: stdin, output: stdout });
  try {
    if (decision.tier === TIERS.DESTRUCTIVE) {
      warning(`Tier 3 · Destructive · origin=${decision.origin}: ${command}`);
      warning(`Blast radius may include data loss, privilege change, or network exposure.`);
      const answer = await rl.question(`Type YES to proceed: `);
      return { confirmed: answer.trim() === 'YES', reason: answer.trim() === 'YES' ? null : 'declined' };
    }
    const answer = await rl.question(
      `${colors.accent}⚠ [Tier 2 · State change · ${decision.origin}]${colors.reset} run: ${command}\nProceed? [y/N] `,
    );
    const yes = /^(y|yes)$/i.test(answer.trim());
    return { confirmed: yes, reason: yes ? null : 'declined' };
  } finally {
    rl.close();
  }
}

export async function resolveVerdict(command, { origin = 'human', autoApprove = false } = {}) {
  const decision = decideExec(command, { origin });
  if (decision.verdict === 'allow') return { ...decision, confirmed: true, via: 'auto-tier1' };
  if (autoApprove) return { ...decision, confirmed: true, via: 'flag' };
  const prompt = await confirmRisk(decision, command);
  return { ...decision, confirmed: prompt.confirmed, via: prompt.confirmed ? 'prompt' : 'denied', denyReason: prompt.reason };
}

// Probe in priority order. Accepts injected deps for tests.
export async function selectProvider(deps = liveBridge) {
  const debian = createDebianEngine(deps);
  const debianCap = await debian.probe();
  if (debianCap.available) return { provider: debian, capability: debianCap };

  const microdroid = createMicrodroidEngine(deps);
  const microCap = await microdroid.probe();
  if (microCap.available) return { provider: microdroid, capability: microCap };

  return {
    provider: unavailableProvider([debianCap, microCap]),
    capability: {
      id: PROVIDER_IDS.none,
      label: 'No AVF provider available',
      available: false,
      reason: 'no-provider',
      remediation: [debianCap.remediation, microCap.remediation].filter(Boolean).join(' '),
      details: { debian: debianCap, microdroid: microCap },
    },
  };
}

export const POWER_SAVER_COPY = 'Power Saver: Sunshine throttled to prevent overheating.';
export const AGENT_STEP_COPY = (n) => `Sunshine has run ${n} commands. Run any command or \`scli vm continue\` to continue execution.`;

// Consecutive agent-origin execs without human input are capped so a stuck
// loop (failing git/apt retried forever) can't burn API tokens, fill logs,
// or pin CPUs. Any human-origin exec — or `scli vm continue` — resets the
// counter (the CLI equivalent of "tap to continue").
export const DEFAULT_AGENT_MAX_STEPS = 20;
const LOOP_STATE_FILE = 'vm-state.json';

async function readLoopState(deps) {
  const file = path.join(deps.defaultVmDir(), LOOP_STATE_FILE);
  const state = (await deps.readJsonSafe(file, null)) || {};
  let cap = DEFAULT_AGENT_MAX_STEPS;
  try {
    const cfg = (await deps.readJsonSafe(path.join(deps.defaultVmDir(), 'sunshine-vm.json'), null)) || {};
    if (Number.isFinite(Number(cfg.agentMaxSteps)) && Number(cfg.agentMaxSteps) > 0) {
      cap = Math.floor(Number(cfg.agentMaxSteps));
    }
  } catch {
    // Config unreadable → default cap stands.
  }
  return { file, count: Number(state.agentStepCount) || 0, cap };
}

export async function checkAgentLoop(deps = liveBridge) {
  const { count, cap } = await readLoopState(deps);
  if (count >= cap) {
    return { allowed: false, count, cap, reason: 'agent-step-cap' };
  }
  return { allowed: true, count, cap, reason: null };
}

export async function resetAgentLoop(deps = liveBridge, via = 'human', { quiet = false } = {}) {
  const file = path.join(deps.defaultVmDir(), LOOP_STATE_FILE);
  const state = (await deps.readJsonSafe(file, null)) || {};
  if (quiet && (Number(state.agentStepCount) || 0) === 0) return { ok: true, reset: false };
  await deps.writeJsonSafe(file, { ...state, agentStepCount: 0 });
  await deps.appendAuditLog({ event: 'loop-continue', via, command: 'loop counter reset' });
  return { ok: true, reset: true };
}

async function noteAgentStep(deps) {
  const file = path.join(deps.defaultVmDir(), LOOP_STATE_FILE);
  const state = (await deps.readJsonSafe(file, null)) || {};
  const next = (Number(state.agentStepCount) || 0) + 1;
  await deps.writeJsonSafe(file, { ...state, agentStepCount: next });
  return next;
}

export async function checkAVFStatus(deps = liveBridge) {
  info(`Inspecting AVF runtime status...`);
  const { capability: cap } = await selectProvider(deps);
  const d = cap.details || {};

  const androidVersion =
    d.androidVersion || d.microdroid?.details?.androidVersion || 'Unknown';
  const virtSupported =
    d.virtualizationSupported ?? d.microdroid?.details?.virtualizationSupported ?? false;

  console.log(`\n${colors.bold}${colors.primary}🛡️ AVF Runtime Status:${colors.reset}`);
  console.log(`  ${colors.secondary}• Android Version:${colors.reset}       ${colors.primary}${androidVersion}${colors.reset}`);
  console.log(`  ${colors.secondary}• AVF Support:${colors.reset}           ${virtSupported ? colors.accent + 'Enabled (Active)' : colors.secondary + 'Simulated / Standby'}${colors.reset}`);
  console.log(`  ${colors.secondary}• Provider:${colors.reset}              ${cap.available ? colors.accent + cap.label : colors.secondary + cap.label}${colors.reset}`);
  if (cap.id === PROVIDER_IDS.debian) {
    console.log(`  ${colors.secondary}• Guest Image:${colors.reset}           ${colors.primary}${d.image || '?'}${colors.reset}`);
    console.log(`  ${colors.secondary}• Guest Kernel:${colors.reset}          ${colors.primary}${d.kernel || '?'}${colors.reset}`);
    console.log(`  ${colors.secondary}• Guest CID:${colors.reset}             ${colors.primary}${d.cid ?? '?'}${colors.reset}`);
  }
  if (cap.id === PROVIDER_IDS.microdroid) {
    console.log(`  ${colors.secondary}• VM Tool:${colors.reset}               ${colors.primary}${d.vmTool || 'not found'}${colors.reset}`);
    console.log(`  ${colors.secondary}• Payload:${colors.reset}               ${colors.primary}${d.hasPayload ? 'configured' : 'not configured'}${colors.reset}`);
  }
  console.log(`  ${colors.secondary}• Runtime Status:${colors.reset}        ${cap.available ? colors.accent + 'Ready' : colors.secondary + 'Unavailable'}${colors.reset}`);
  const thermal = await probeThermal(deps.thermal || liveThermalSys);
  if (thermal.powerSaver) {
    console.log(`  ${colors.accent}🔋 ${POWER_SAVER_COPY}${colors.reset}`);
    if (thermal.reasons.length > 0) {
      console.log(`  ${colors.secondary}  (${thermal.reasons.join('; ')})${colors.reset}`);
    }
  }
  if (!cap.available && cap.remediation) {
    console.log(`\n  ${colors.secondary}Next step:${colors.reset} ${colors.primary}${cap.remediation}${colors.reset}`);
  }
  console.log('');

  return cap;
}

function printGuestOutput(res) {
  if (res.stdout) process.stdout.write(res.stdout.endsWith('\n') ? res.stdout : res.stdout + '\n');
  if (res.stderr) process.stderr.write(res.stderr.endsWith('\n') ? res.stderr : res.stderr + '\n');
  if (res.droppedLines > 0) {
    warning(`Ring buffer dropped ${res.droppedLines} older output lines (cap reached).`);
  }
}

export async function executeAVF(command, opts = {}) {
  const legacy = opts && typeof opts.runHost === 'function';
  const { origin = 'human', autoApprove = false, deps = liveBridge } = legacy ? { deps: opts } : (opts || {});
  const bridge = deps;

  const verdict = await resolveVerdict(command, { origin, autoApprove });
  await bridge.appendAuditLog({
    event: 'exec-decision', provider: 'selecting', origin,
    tier: verdict.tier, tierName: verdict.tierName,
    verdict: verdict.confirmed ? (verdict.via === 'auto-tier1' ? 'allow' : 'confirmed') : 'denied',
    via: verdict.via, command,
  });
  if (!verdict.confirmed) {
    error(verdict.via === 'denied' && verdict.denyReason === 'non-interactive'
      ? `Refusing Tier ${verdict.tier} command in non-interactive mode. Re-run with --approve-risk or in a terminal.`
      : `Declined. Command not executed.`);
    return { ok: false, reason: verdict.denyReason === 'non-interactive' ? 'non-interactive' : 'declined', tier: verdict.tier };
  }

  // Agent loop guard: human input resets the counter (the "tap to
  // continue"); agent steps count up to the cap, then pause for a human.
  let agentStep = null;
  if (origin === 'agent') {
    const loop = await checkAgentLoop(bridge);
    if (!loop.allowed) {
      error(`Agent step cap reached (${loop.count}/${loop.cap}).`);
      info(AGENT_STEP_COPY(loop.cap));
      await bridge.appendAuditLog({
        event: 'exec-decision', provider: 'selecting', origin,
        tier: verdict.tier, tierName: verdict.tierName,
        verdict: 'denied', via: 'agent-step-cap', command,
      });
      return { ok: false, reason: 'agent-step-cap', count: loop.count, cap: loop.cap };
    }
    agentStep = await noteAgentStep(bridge);
  } else {
    await resetAgentLoop(bridge, 'human-command', { quiet: true });
  }

  const { provider, capability: cap } = await selectProvider(bridge);
  if (!cap.available) {
    error(`No AVF guest reachable (${cap.reason || 'no-provider'}).`);
    if (cap.remediation) info(cap.remediation);
    return { ok: false, reason: cap.reason || 'no-provider' };
  }
  info(`Routing to ${cap.label}: ${colors.bold}${command}${colors.reset}${agentStep !== null ? `${colors.secondary} [agent step ${agentStep}]${colors.reset}` : ''}`);
  const execVerdict = verdict.via === 'auto-tier1' ? null : 'confirmed';
  const res = await provider.exec(command, { origin, verdict: execVerdict });
  await bridge.appendAuditLog({
    event: 'exec-result', provider: cap.id, origin,
    tier: verdict.tier, tierName: verdict.tierName,
    ok: res.ok, reason: res.reason || null, code: res.code ?? null, command,
  });
  if (!res.ok) {
    error(`AVF execution failed (${res.reason}).`);
    if (res.remediation) info(res.remediation);
    printGuestOutput(res);
    return res;
  }
  printGuestOutput(res);
  if (res.powerSaver) {
    warning(POWER_SAVER_COPY);
  }
  success(`AVF execution completed${res.code === 0 || res.code === undefined ? ' successfully' : ` (exit ${res.code})`}.`);
  return res;
}

export async function bootVM(deps = liveBridge) {
  const { provider, capability: cap } = await selectProvider(deps);
  if (!cap.available) {
    error(`Nothing to boot (${cap.reason || 'no-provider'}).`);
    if (cap.remediation) info(cap.remediation);
    return { ok: false, reason: cap.reason || 'no-provider' };
  }
  info(`Booting ${cap.label}...`);
  const res = await provider.boot();
  await deps.appendAuditLog({
    event: 'boot', provider: cap.id, ok: res.ok, reason: res.reason || null, command: `vm boot (${cap.id})`,
  });
  if (!res.ok) {
    error(`Boot failed (${res.reason}).`);
    if (res.remediation) info(res.remediation);
    return res;
  }
  success(res.note || `${cap.label} boot requested.`);
  return res;
}

export async function provisionVM(deps = liveBridge) {
  const { provider, capability: cap } = await selectProvider(deps);
  if (!cap.available) {
    error(`Nothing to provision (${cap.reason || 'no-provider'}).`);
    if (cap.remediation) info(cap.remediation);
    return { ok: false, reason: cap.reason || 'no-provider' };
  }
  if (typeof provider.provision !== 'function') {
    warning(`${cap.label} has no guest bundle to provision.`);
    return { ok: true, note: 'no-provision-needed' };
  }
  info(`Provisioning ${cap.label} guest bundle...`);
  const res = await provider.provision();
  await deps.appendAuditLog({
    event: 'provision', provider: cap.id, ok: res.ok, reason: res.reason || null, command: `vm provision (${cap.id})`,
  });
  if (!res.ok) {
    error(`Provision failed (${res.reason}).`);
    if (res.remediation) info(res.remediation);
    return res;
  }
  success(res.note || `${cap.label} provisioned.`);
  return res;
}

export async function shutdownVM(deps = liveBridge) {
  const { provider, capability: cap } = await selectProvider(deps);
  if (!cap.available) {
    error(`Nothing to shut down (${cap.reason || 'no-provider'}).`);
    return { ok: false, reason: cap.reason || 'no-provider' };
  }
  info(`Shutting down ${cap.label}...`);
  const res = await provider.shutdown();
  await deps.appendAuditLog({
    event: 'shutdown', provider: cap.id, ok: res.ok, reason: res.reason || null, command: `vm shutdown (${cap.id})`,
  });
  if (!res.ok) {
    error(`Shutdown failed (${res.reason}).`);
    if (res.remediation) info(res.remediation);
    return res;
  }
  success(res.note || `${cap.label} shutdown requested.`);
  return res;
}

export async function showVMLogs(tail = 50, deps = liveBridge) {
  const { provider, capability: cap } = await selectProvider(deps);
  if (!cap.available) {
    error(`No VM logs (${cap.reason || 'no-provider'}).`);
    return { ok: false, reason: cap.reason || 'no-provider' };
  }
  const res = await provider.logs({ tail });
  if (!res.ok) {
    warning(res.remediation || `No logs available (${res.reason}).`);
    return res;
  }
  if (res.file) info(`Logs: ${res.file} (last ${res.lines.length} lines)`);
  for (const line of res.lines) console.log(line);
  return res;
}

export async function openVMShell(deps = liveBridge) {
  const { provider, capability: cap } = await selectProvider(deps);
  if (!cap.available) {
    error(`No shell available (${cap.reason || 'no-provider'}).`);
    if (cap.remediation) info(cap.remediation);
    return { ok: false, reason: cap.reason || 'no-provider' };
  }
  info(`Opening shell on ${cap.label}...`);
  await deps.appendAuditLog({ event: 'shell', provider: cap.id, origin: 'human', command: `vm shell (${cap.id})` });
  return provider.shell();
}

/** Render the thermal/battery snapshot (also the future Compose chip's data). */export async function showThermal(deps = liveBridge) {
  const t = await probeThermal(deps.thermal || liveThermalSys);
  const batt = t.batteryPct === null ? 'unknown' : `${t.batteryPct}%`;
  const temp = t.maxTempC === null ? 'unknown' : `${t.maxTempC.toFixed(1)}°C`;
  console.log(`\n${colors.bold}${colors.primary}🌡️ Thermal & Battery:${colors.reset}`);
  console.log(`  ${colors.secondary}• Battery:${colors.reset}  ${colors.primary}${batt}${colors.reset}`);
  console.log(`  ${colors.secondary}• Skin temp:${colors.reset} ${colors.primary}${temp}${colors.reset}`);
  console.log(`  ${colors.secondary}• Level:${colors.reset}    ${t.throttled ? colors.accent : colors.primary}${t.level}${colors.reset}`);
  console.log(`  ${colors.secondary}• Throttled:${colors.reset} ${t.throttled ? colors.accent + 'yes' : colors.primary + 'no'}${colors.reset}`);
  if (t.reasons.length > 0) {
    console.log(`  ${colors.secondary}• Why:${colors.reset}      ${colors.primary}${t.reasons.join('; ')}${colors.reset}`);
  }
  console.log(`  ${colors.secondary}• Bands:${colors.reset}     ${colors.primary}warm ≥${t.thresholds.warmTempC}°C · severe ≥${t.thresholds.severeTempC}°C · low-battery ≤${t.thresholds.batteryLowPct}%${colors.reset}\n`);
  return t;
}

/** One-shot guest liveness check. */
export async function pingGuestVM(deps = liveBridge) {
  const { provider, capability: cap } = await selectProvider(deps);
  if (!cap.available) {
    error(`No guest to ping (${cap.reason || 'no-provider'}).`);
    return { ok: false, reason: cap.reason || 'no-provider' };
  }
  const res = await pingGuest(provider);
  if (res.ok) {
    success(`Heartbeat ok from ${cap.label} (${res.latencyMs}ms).`);
  } else {
    error(`Heartbeat missed (${res.reason}).`);
  }
  return res;
}

/**
 * Foreground watch loop: ping every intervalMs, recover after maxMisses
 * consecutive misses. Runs until SIGINT. This is the CLI stand-in for the
 * Compose ForegroundService anchor (see android/VmControllerService.kt):
 * same cadence, same miss budget, same recovery path.
 */
export async function watchVM({ intervalMs = DEFAULT_HEARTBEAT_INTERVAL_MS, maxMisses = DEFAULT_HEARTBEAT_MAX_MISSES, autoBoot = false, deps = liveBridge } = {}) {
  const { provider, capability: cap } = await selectProvider(deps);
  if (!cap.available) {
    error(`Nothing to watch (${cap.reason || 'no-provider'}).`);
    if (cap.remediation) info(cap.remediation);
    return { ok: false, reason: cap.reason || 'no-provider' };
  }
  const monitor = createHeartbeatMonitor({
    ping: () => pingGuest(provider),
    intervalMs,
    maxMisses,
    onHeartbeat: ({ latencyMs }) => info(`♥ ${cap.label} alive (${latencyMs}ms)`),
    onDrop: ({ state }) => warning(`Heartbeat missed (${state.consecutiveMisses}/${maxMisses}).`),
    recover: () => recoverGuest(deps, provider, { autoBoot }),
    onRecover: ({ recovery }) => {
      if (recovery.ok) {
        success(recovery.rebooted ? 'Guest recovered and rebooting.' : 'Guest state fenced cleanly.');
        if (recovery.remediation) info(recovery.remediation);
      } else {
        error(`Recovery failed (${recovery.reason || 'unknown'}).`);
      }
    },
  });
  monitor.start();
  info(`Watching ${cap.label} every ${intervalMs}ms — Ctrl-C to stop.`);
  await new Promise((resolve) => {
    process.once('SIGINT', () => {
      monitor.stop();
      resolve();
    });
  });
  return { ok: true, state: monitor.state };
}

// Toggle the opt-in outbound allowlist profile. Persists to
// sunshine-vm.json; takes effect on next `vm provision` (idempotent).
export async function setLockdownMode(on, deps = liveBridge) {
  const configFile = path.join(deps.defaultVmDir(), 'sunshine-vm.json');
  let current = {};
  if (typeof deps.readJsonFile === 'function') {
    const res = await deps.readJsonFile(configFile);
    if (res.ok) {
      current = res.data;
    } else if (res.reason === 'corrupt') {
      error(`VM config is corrupt${res.backup ? ` (backed up to ${res.backup})` : ''}. Fix or delete it, then re-run.`);
      return { ok: false, reason: 'config-corrupt' };
    }
    // 'missing' → start from defaults below.
  } else {
    current = (await deps.readJsonSafe(configFile, null)) || {};
  }
  const next = { ...current, lockdown: Boolean(on) };
  const saved = await deps.writeJsonSafe(configFile, next);
  if (!saved.ok) {
    error(`Could not persist lockdown setting: ${saved.reason}`);
    return { ok: false, reason: saved.reason };
  }
  await deps.appendAuditLog({ event: 'lockdown', lockdown: Boolean(on), command: `vm lockdown ${on ? 'on' : 'off'}` });
  success(`Outbound lockdown ${on ? 'ENABLED' : 'disabled'}. Re-run \`scli vm provision\` to apply inside the guest.`);
  return { ok: true, lockdown: Boolean(on) };
}
