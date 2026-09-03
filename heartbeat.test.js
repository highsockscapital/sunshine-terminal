import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { pingGuest, recoverGuest, createHeartbeatMonitor } from './src/runtimes/heartbeat.js';
import { createDebianEngine } from './src/runtimes/debian.js';
import { makeFakeBridge } from './fake-bridge.js';

const VM_DIR = '/tmp/fake-sunshine-vm';

describe('pingGuest', () => {
  it('reports no-ping when the provider has no probe', async () => {
    const res = await pingGuest({ id: 'x' });
    assert.equal(res.ok, false);
    assert.equal(res.reason, 'no-ping');
  });

  it('measures ok pings and surfaces failures', async () => {
    const ok = await pingGuest({ ping: async () => ({ ok: true }) });
    assert.equal(ok.ok, true);
    assert.ok(typeof ok.latencyMs === 'number');
    const bad = await pingGuest({ ping: async () => { throw new Error('boom'); } });
    assert.equal(bad.ok, false);
    assert.equal(bad.reason, 'boom');
  });
});

describe('heartbeat monitor', () => {
  function scripted(results) {
    let i = 0;
    const events = [];
    const monitor = createHeartbeatMonitor({
      ping: async () => results[Math.min(i++, results.length - 1)],
      maxMisses: 2,
      onHeartbeat: async () => events.push('beat'),
      onDrop: async () => events.push('drop'),
      recover: async () => {
        events.push('recover');
        return { ok: true };
      },
      onRecover: async () => events.push('recovered'),
    });
    return { monitor, events };
  }

  it('successes reset the miss counter', async () => {
    const { monitor } = scripted([{ ok: true }, { ok: false }, { ok: true }]);
    await monitor.tick();
    await monitor.tick();
    assert.equal(monitor.state.consecutiveMisses, 1);
    await monitor.tick();
    assert.equal(monitor.state.consecutiveMisses, 0);
  });

  it('two consecutive misses trigger exactly one recovery', async () => {
    const { monitor, events } = scripted([{ ok: false }]);
    await monitor.tick();
    assert.deepEqual(events, ['drop']);
    await monitor.tick();
    assert.deepEqual(events, ['drop', 'drop', 'recover', 'recovered']);
    await monitor.tick();
    assert.deepEqual(events, ['drop', 'drop', 'recover', 'recovered', 'drop']);
    assert.equal(monitor.state.totalMisses, 3);
  });

  it('a success after recovery re-arms the trigger', async () => {
    const { monitor, events } = scripted([{ ok: false }, { ok: false }, { ok: true }, { ok: false }, { ok: false }]);
    await monitor.tick();
    await monitor.tick();
    await monitor.tick();
    await monitor.tick();
    await monitor.tick();
    assert.equal(events.filter((e) => e === 'recover').length, 2);
  });

  it('requires a ping function', () => {
    assert.throws(() => createHeartbeatMonitor({}), /needs a ping/);
  });
});

describe('recoverGuest', () => {
  function recoveryDeps() {
    return makeFakeBridge({
      configs: {
        [`${VM_DIR}/vm-state.json`]: { sshPort: 22042, pid: 9999, tokenId: 'tokid', guestVersion: 2 },
      },
    });
  }

  it('fences token and channel state, keeps config, audits', async () => {
    const deps = recoveryDeps();
    const res = await recoverGuest(deps, { id: 'debian' }, { autoBoot: false });
    assert.equal(res.ok, true);
    assert.equal(res.rebooted, false);
    assert.ok(res.remediation.includes('scli vm boot'));
    const state = deps._written[`${VM_DIR}/vm-state.json`];
    assert.equal(state.pid, null);
    assert.equal(state.sshPort, null);
    assert.equal(state.tokenId, null);
    assert.equal(state.guestVersion, 2);
    assert.ok(state.lastRecovery);
    assert.ok(deps._audit.some((a) => a.event === 'guest-recovery'));
  });

  it('reboots only with explicit opt-in', async () => {
    const deps = recoveryDeps();
    let booted = false;
    const res = await recoverGuest(deps, { id: 'debian', boot: async () => { booted = true; return { ok: true }; } }, { autoBoot: true });
    assert.equal(res.rebooted, true);
    assert.equal(booted, true);
  });
});

describe('debian ping()', () => {
  function pingDeps(runHandler) {
    return makeFakeBridge({
      binaries: { crosvm: '/usr/bin/crosvm', ssh: '/usr/bin/ssh' },
      files: { [`${VM_DIR}/debian.img`]: true, [`${VM_DIR}/Image`]: true },
      configs: {
        [`${VM_DIR}/sunshine-vm.json`]: { image: `${VM_DIR}/debian.img`, kernel: `${VM_DIR}/Image` },
      },
      runHandler,
    });
  }

  it('ok on reachable guest', async () => {
    const deps = pingDeps(() => ({ ok: true, code: 0, stdout: '', stderr: '', reason: null }));
    assert.deepEqual((await createDebianEngine(deps).ping()).ok, true);
  });

  it('misses surface the reason', async () => {
    const deps = pingDeps(() => ({ ok: false, code: 255, stdout: '', stderr: '', reason: 'exit-255' }));
    const res = await createDebianEngine(deps).ping();
    assert.equal(res.ok, false);
  });
});
