import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { probeThermal, degradedResources, THERMAL_LEVELS } from './src/runtimes/thermal.js';
import { createDebianEngine } from './src/runtimes/debian.js';
import { showThermal } from './src/runtimes/vm.js';
import { makeFakeBridge } from './fake-bridge.js';

const VM_DIR = '/tmp/fake-sunshine-vm';
const BATT = '/sys/class/power_supply/battery/capacity';
const ZONE0 = '/sys/class/thermal/thermal_zone0/temp';

function fakeSys(files) {
  return {
    readFile: async (p) => {
      if (Object.hasOwn(files, p)) return files[p];
      const err = new Error(`ENOENT: ${p}`);
      err.code = 'ENOENT';
      throw err;
    },
  };
}

describe('probeThermal bands', () => {
  it('severe heat throttles', async () => {
    const t = await probeThermal(fakeSys({ [BATT]: '80\n', [ZONE0]: '49000\n' }));
    assert.equal(t.level, THERMAL_LEVELS.SEVERE);
    assert.equal(t.throttled, true);
    assert.equal(t.powerSaver, true);
    assert.equal(t.maxTempC, 49);
  });

  it('warm does not throttle', async () => {
    const t = await probeThermal(fakeSys({ [BATT]: '80\n', [ZONE0]: '44000\n' }));
    assert.equal(t.level, THERMAL_LEVELS.WARM);
    assert.equal(t.throttled, false);
  });

  it('cool + charged is ok', async () => {
    const t = await probeThermal(fakeSys({ [BATT]: '80\n', [ZONE0]: '35000\n' }));
    assert.equal(t.level, THERMAL_LEVELS.OK);
    assert.equal(t.throttled, false);
  });

  it('battery at 15% is the low boundary (inclusive)', async () => {
    const low = await probeThermal(fakeSys({ [BATT]: '15\n', [ZONE0]: '35000\n' }));
    assert.equal(low.batteryLow, true);
    assert.equal(low.throttled, true);
    const fine = await probeThermal(fakeSys({ [BATT]: '16\n', [ZONE0]: '35000\n' }));
    assert.equal(fine.throttled, false);
  });

  it('missing sensors never block (unknown, unthrottled)', async () => {
    const t = await probeThermal(fakeSys({}));
    assert.equal(t.level, THERMAL_LEVELS.UNKNOWN);
    assert.equal(t.throttled, false);
    assert.equal(t.batteryPct, null);
  });

  it('takes the hottest zone and honors custom thresholds', async () => {
    const sys = fakeSys({
      [BATT]: '90\n',
      [ZONE0]: '30000\n',
      '/sys/class/thermal/thermal_zone1/temp': '60000\n',
    });
    const t = await probeThermal(sys, { severeTempC: 55 });
    assert.equal(t.maxTempC, 60);
    assert.equal(t.level, THERMAL_LEVELS.SEVERE);
  });
});

describe('degradedResources', () => {
  it('passes through when cool', () => {
    const r = degradedResources({ cpus: 4, memoryMb: 4096 }, { throttled: false });
    assert.deepEqual(r, { cpus: 4, memoryMb: 4096, degraded: false });
  });
  it('floors to degraded minimums when throttled', () => {
    const r = degradedResources({ cpus: 4, memoryMb: 4096 }, { throttled: true });
    assert.deepEqual(r, { cpus: 1, memoryMb: 1024, degraded: true });
  });
  it('never exceeds configured resources', () => {
    const r = degradedResources({ cpus: 1, memoryMb: 512 }, { throttled: true });
    assert.deepEqual(r, { cpus: 1, memoryMb: 512, degraded: true });
  });
});

function hotDeps(extra = {}) {
  const base = makeFakeBridge({
    binaries: { crosvm: '/usr/bin/crosvm', ssh: '/usr/bin/ssh' },
    files: { [`${VM_DIR}/debian.img`]: true, [`${VM_DIR}/Image`]: true },
    configs: {
      [`${VM_DIR}/sunshine-vm.json`]: { image: `${VM_DIR}/debian.img`, kernel: `${VM_DIR}/Image` },
      [`${VM_DIR}/.session-token`]: { token: 'tok-1', id: 'id-1', issuedAt: 'x' },
    },
    ...extra,
  });
  return {
    ...base,
    thermal: fakeSys({ [BATT]: '10\n', [ZONE0]: '50000\n' }),
  };
}

describe('debian thermal enforcement', () => {
  it('boots degraded with a Power Saver note when hot', async () => {
    const deps = hotDeps();
    const res = await createDebianEngine(deps).boot();
    assert.equal(res.ok, true);
    assert.equal(res.powerSaver, true);
    assert.ok(res.note.includes('Power Saver'));
    const launch = deps._launches[0];
    const cpusIdx = launch.args.indexOf('--cpus');
    const memIdx = launch.args.indexOf('--mem');
    assert.equal(launch.args[cpusIdx + 1], '1');
    assert.equal(launch.args[memIdx + 1], '1024');
  });

  it('renices the guest on exec when throttled', async () => {
    const deps = hotDeps({
      configs: {
        [`${VM_DIR}/sunshine-vm.json`]: { image: `${VM_DIR}/debian.img`, kernel: `${VM_DIR}/Image` },
        [`${VM_DIR}/.session-token`]: { token: 'tok-1', id: 'id-1', issuedAt: 'x' },
        [`${VM_DIR}/vm-state.json`]: { sshPort: 22042, pid: 9999 },
      },
      runHandler: (command) => {
        if (command === 'renice') return { ok: true, code: 0, stdout: '', stderr: '', reason: null };
        return { ok: true, code: 0, stdout: 'out\n', stderr: '', reason: null };
      },
    });
    const res = await createDebianEngine(deps).exec('uname -a');
    assert.equal(res.ok, true);
    assert.equal(res.powerSaver, true);
    const renice = deps._runs.find((r) => r.command === 'renice');
    assert.ok(renice, 'crosvm pid should be reniced');
    assert.deepEqual(renice.args, ['-n', '10', '-p', '9999']);
  });

  it('skips renice without a known pid', async () => {
    const deps = hotDeps({
      runHandler: () => ({ ok: true, code: 0, stdout: 'out\n', stderr: '', reason: null }),
    });
    const res = await createDebianEngine(deps).exec('uname -a');
    assert.equal(res.ok, true);
    assert.ok(!deps._runs.some((r) => r.command === 'renice'));
  });
});

describe('showThermal', () => {
  it('returns the snapshot for the future Compose chip', async () => {
    const deps = { ...makeFakeBridge({}), thermal: fakeSys({ [BATT]: '42\n', [ZONE0]: '36000\n' }) };
    const t = await showThermal(deps);
    assert.equal(t.batteryPct, 42);
    assert.equal(t.maxTempC, 36);
    assert.equal(t.throttled, false);
  });
});
