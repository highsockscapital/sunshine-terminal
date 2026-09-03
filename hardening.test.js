import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createDebianEngine, GUEST_BUNDLE_VERSION } from '../src/runtimes/debian.js';
import { enforcePolicy } from '../src/runtimes/policy.js';
import { makeFakeBridge } from './fake-bridge.js';

const VM_DIR = '/tmp/fake-sunshine-vm';

function provisionedDeps(extra = {}) {
  return makeFakeBridge({
    binaries: { crosvm: '/usr/bin/crosvm', ssh: '/usr/bin/ssh' },
    files: { [`${VM_DIR}/debian.img`]: true, [`${VM_DIR}/Image`]: true },
    configs: {
      [`${VM_DIR}/sunshine-vm.json`]: {
        image: `${VM_DIR}/debian.img`,
        kernel: `${VM_DIR}/Image`,
      },
      [`${VM_DIR}/.session-token`]: { token: 'tok-123', id: 'tokid-1', issuedAt: 'x' },
    },
    ...extra,
  });
}

describe('enforcePolicy gate', () => {
  it('denies Tier 3 without a confirmed verdict', () => {
    const g = enforcePolicy('rm -rf /tmp/x', { origin: 'agent', verdict: null });
    assert.equal(g.allowed, false);
    assert.equal(g.denyReason, 'approval-required');
  });
  it('allows Tier 3 with confirmed verdict', () => {
    const g = enforcePolicy('rm -rf /tmp/x', { origin: 'agent', verdict: 'confirmed' });
    assert.equal(g.allowed, true);
  });
  it('allows Tier 1 with no verdict', () => {
    assert.equal(enforcePolicy('ls -la', {}).allowed, true);
  });
});

describe('debian hardened exec', () => {
  it('denies destructive commands before touching the guest', async () => {
    const deps = provisionedDeps();
    const res = await createDebianEngine(deps).exec('rm -rf /tmp/x', { origin: 'agent' });
    assert.equal(res.ok, false);
    assert.equal(res.reason, 'approval-required');
    assert.equal(deps._runs.length, 0);
  });

  it('fails honestly without a session token', async () => {
    const deps = provisionedDeps({ configs: {
      [`${VM_DIR}/sunshine-vm.json`]: { image: `${VM_DIR}/debian.img`, kernel: `${VM_DIR}/Image` },
    } });
    const res = await createDebianEngine(deps).exec('uname -a');
    assert.equal(res.ok, false);
    assert.equal(res.reason, 'no-session-token');
  });

  it('uses the shim channel with token+origin+command on stdin when provisioned', async () => {
    const deps = provisionedDeps({
      configs: {
        [`${VM_DIR}/sunshine-vm.json`]: { image: `${VM_DIR}/debian.img`, kernel: `${VM_DIR}/Image` },
        [`${VM_DIR}/.session-token`]: { token: 'tok-123', id: 'tokid-1', issuedAt: 'x' },
        [`${VM_DIR}/vm-state.json`]: { guestVersion: GUEST_BUNDLE_VERSION, sshPort: 22042 },
      },
      runHandler: () => ({ ok: true, code: 0, stdout: 'Linux guest\n', stderr: '', reason: null }),
    });
    const res = await createDebianEngine(deps).exec('uname -a', { origin: 'agent' });
    assert.equal(res.ok, true);
    assert.equal(res.channel, 'shim');
    const sshCall = deps._runs.find((r) => r.command === 'ssh');
    assert.ok(sshCall);
    assert.ok(sshCall.args.includes('sunshine-exec'));
    assert.ok(sshCall.args.includes('-p') && sshCall.args.includes('22042'));
    assert.equal(sshCall.opts.input, 'tok-123\nagent\nuname -a');
  });

  it('falls back to flagged direct-ssh on unprovisioned guests', async () => {
    const deps = provisionedDeps({
      runHandler: () => ({ ok: true, code: 0, stdout: 'ok\n', stderr: '', reason: null }),
    });
    const res = await createDebianEngine(deps).exec('uname -a');
    assert.equal(res.ok, true);
    assert.equal(res.channel, 'direct-ssh');
    assert.equal(res.downgraded, true);
  });

  it('boot picks an ephemeral port and records token id in state', async () => {
    const deps = provisionedDeps();
    const res = await createDebianEngine(deps).boot();
    assert.equal(res.ok, true);
    const state = deps._written[`${VM_DIR}/vm-state.json`];
    assert.ok(state.sshPort >= 22000 && state.sshPort <= 22999);
    assert.ok(state.tokenId && state.tokenId.length === 16);
    assert.ok(res.note.includes(String(state.sshPort)));
  });

  it('shutdown pre-confirms its own poweroff (explicit user action)', async () => {
    const deps = provisionedDeps({
      configs: {
        [`${VM_DIR}/sunshine-vm.json`]: { image: `${VM_DIR}/debian.img`, kernel: `${VM_DIR}/Image` },
        [`${VM_DIR}/.session-token`]: { token: 'tok-123', id: 'tokid-1', issuedAt: 'x' },
      },
      runHandler: () => ({ ok: true, code: 0, stdout: '', stderr: '', reason: null }),
    });
    const res = await createDebianEngine(deps).shutdown();
    assert.equal(res.ok, true);
  });
});
