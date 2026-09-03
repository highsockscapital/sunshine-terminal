import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { defineProvider } from '../src/runtimes/provider.js';
import { createMicrodroidEngine } from '../src/runtimes/microdroid.js';
import { createDebianEngine, defaultDebianConfig } from '../src/runtimes/debian.js';
import { makeFakeBridge } from './fake-bridge.js';

const VM_DIR = '/tmp/fake-sunshine-vm';

describe('provider contract', () => {
  it('rejects incomplete engines', () => {
    assert.throws(() => defineProvider({ id: 'x' }), /missing/);
  });
});

describe('microdroid engine', () => {
  it('is unavailable below Android 14 without a virtualization flag', async () => {
    const deps = makeFakeBridge({ getpropRelease: '13', virtFlag: '' });
    const cap = await createMicrodroidEngine(deps).probe();
    assert.equal(cap.available, false);
    assert.equal(cap.reason, 'avf-unsupported');
  });

  it('is unavailable when the vm tool is missing', async () => {
    const deps = makeFakeBridge({ getpropRelease: '16', virtFlag: 'true', files: {} });
    const cap = await createMicrodroidEngine(deps).probe();
    assert.equal(cap.available, false);
    assert.equal(cap.reason, 'no-vm-tool');
  });

  it('exec without a payload fails honestly (never fake success)', async () => {
    const deps = makeFakeBridge({
      getpropRelease: '16',
      virtFlag: 'true',
      files: { '/apex/com.android.virt/bin/vm': true },
    });
    const res = await createMicrodroidEngine(deps).exec('uname -a');
    assert.equal(res.ok, false);
    assert.equal(res.reason, 'no-payload');
    assert.ok(res.remediation.includes('microdroid.json'));
  });
});

describe('debian engine', () => {
  it('provisions a default config on first probe', async () => {
    const deps = makeFakeBridge({ files: {}, binaries: {} });
    const cap = await createDebianEngine(deps).probe();
    assert.equal(cap.available, false);
    assert.equal(cap.reason, 'image-not-provisioned');
    const written = deps._written[`${VM_DIR}/sunshine-vm.json`];
    assert.ok(written);
    assert.equal(written.image, `${VM_DIR}/debian.img`);
    assert.equal(written.cid, 42);
  });

  it('default config carries expected production values', () => {
    const cfg = defaultDebianConfig(VM_DIR);
    assert.equal(cfg.memoryMb, 2048);
    assert.equal(cfg.ssh.user, 'sunshine');
    assert.ok(cfg.cmdline.includes('console=hvc0'));
  });

  it('boot launches crosvm detached when provisioned', async () => {
    const deps = makeFakeBridge({
      binaries: { crosvm: '/usr/bin/crosvm', ssh: '/usr/bin/ssh' },
      files: { [`${VM_DIR}/debian.img`]: true, [`${VM_DIR}/Image`]: true },
      configs: {
        [`${VM_DIR}/sunshine-vm.json`]: {
          image: `${VM_DIR}/debian.img`,
          kernel: `${VM_DIR}/Image`,
        },
      },
    });
    const engine = createDebianEngine(deps);
    const res = await engine.boot();
    assert.equal(res.ok, true);
    assert.equal(deps._launches.length, 1);
    assert.equal(deps._launches[0].command, '/usr/bin/crosvm');
    assert.ok(deps._launches[0].args.includes('run'));
  });

  it('exec reports guest-exec-failed with remediation when ssh fails', async () => {
    const deps = makeFakeBridge({
      binaries: { crosvm: '/usr/bin/crosvm', ssh: '/usr/bin/ssh' },
      files: { [`${VM_DIR}/debian.img`]: true, [`${VM_DIR}/Image`]: true },
      configs: {
        [`${VM_DIR}/sunshine-vm.json`]: {
          image: `${VM_DIR}/debian.img`,
          kernel: `${VM_DIR}/Image`,
        },
        [`${VM_DIR}/.session-token`]: { token: 'test-token', id: 'test-id', issuedAt: 'x' },
      },
      runHandler: () => ({ ok: false, code: 255, stdout: '', stderr: 'refused', reason: 'exit-255' }),
    });
    const res = await createDebianEngine(deps).exec('uname -a');
    assert.equal(res.ok, false);
    assert.equal(res.reason, 'guest-exec-failed');
    assert.ok(res.remediation.includes('scli vm boot'));
  });
});
