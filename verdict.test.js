import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { executeAVF, resolveVerdict, setLockdownMode } from './src/runtimes/vm.js';
import { makeFakeBridge } from './fake-bridge.js';

const VM_DIR = '/tmp/fake-sunshine-vm';

function liveDeps(extra = {}) {
  return makeFakeBridge({
    binaries: { crosvm: '/usr/bin/crosvm', ssh: '/usr/bin/ssh' },
    files: { [`${VM_DIR}/debian.img`]: true, [`${VM_DIR}/Image`]: true },
    configs: {
      [`${VM_DIR}/sunshine-vm.json`]: { image: `${VM_DIR}/debian.img`, kernel: `${VM_DIR}/Image` },
      [`${VM_DIR}/.session-token`]: { token: 'tok-1', id: 'id-1', issuedAt: 'x' },
    },
    runHandler: () => ({ ok: true, code: 0, stdout: 'out\n', stderr: '', reason: null }),
    ...extra,
  });
}

describe('resolveVerdict', () => {
  it('auto-allows Tier 1', async () => {
    const v = await resolveVerdict('ls -la', { origin: 'human' });
    assert.equal(v.confirmed, true);
    assert.equal(v.via, 'auto-tier1');
  });
  it('denies Tier 3 in non-interactive contexts', async () => {
    const v = await resolveVerdict('rm -rf /tmp/x', { origin: 'agent' });
    // Test runner stdin is not a TTY → non-interactive deny.
    assert.equal(v.confirmed, false);
  });
  it('honors --approve-risk style autoApprove', async () => {
    const v = await resolveVerdict('rm -rf /tmp/x', { origin: 'agent', autoApprove: true });
    assert.equal(v.confirmed, true);
    assert.equal(v.via, 'flag');
  });
});

describe('executeAVF with policy', () => {
  it('runs Tier 1 end to end and audits decision + result', async () => {
    const deps = liveDeps();
    const res = await executeAVF('uname -a', { origin: 'human', deps });
    assert.equal(res.ok, true);
    const events = deps._audit.map((a) => a.event);
    assert.ok(events.includes('exec-decision'));
    assert.ok(events.includes('exec-result'));
    const decision = deps._audit.find((a) => a.event === 'exec-decision');
    assert.equal(decision.verdict, 'allow');
    assert.ok(!JSON.stringify(deps._audit).includes('tok-1'), 'token must not leak into audit');
  });

  it('refuses Tier 3 without contacting the guest', async () => {
    const deps = liveDeps();
    const res = await executeAVF('rm -rf /tmp/x', { origin: 'agent', deps });
    assert.equal(res.ok, false);
    assert.equal(deps._runs.length, 0);
    const decision = deps._audit.find((a) => a.event === 'exec-decision');
    assert.equal(decision.verdict, 'denied');
  });

  it('executes Tier 3 with autoApprove and confirmed engine verdict', async () => {
    const deps = liveDeps();
    const res = await executeAVF('apt purge -y oldpkg', { origin: 'agent', autoApprove: true, deps });
    assert.equal(res.ok, true);
  });
});

describe('setLockdownMode', () => {
  it('persists the flag for next provision', async () => {
    const deps = liveDeps();
    const on = await setLockdownMode(true, deps);
    assert.equal(on.ok, true);
    assert.equal(deps._written[`${VM_DIR}/sunshine-vm.json`].lockdown, true);
    const off = await setLockdownMode(false, deps);
    assert.equal(deps._written[`${VM_DIR}/sunshine-vm.json`].lockdown, false);
    void off;
  });
});
