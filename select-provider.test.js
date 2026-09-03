import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { selectProvider } from '../src/runtimes/vm.js';
import { PROVIDER_IDS } from '../src/runtimes/provider.js';
import { makeFakeBridge } from './fake-bridge.js';

const VM_DIR = '/tmp/fake-sunshine-vm';

describe('selectProvider', () => {
  it('prefers Debian when image, kernel and crosvm are present', async () => {
    const deps = makeFakeBridge({
      binaries: { crosvm: '/usr/bin/crosvm', ssh: '/usr/bin/ssh' },
      files: {
        [`${VM_DIR}/debian.img`]: true,
        [`${VM_DIR}/Image`]: true,
      },
      configs: {
        [`${VM_DIR}/sunshine-vm.json`]: {
          image: `${VM_DIR}/debian.img`,
          kernel: `${VM_DIR}/Image`,
        },
      },
    });
    const { provider, capability } = await selectProvider(deps);
    assert.equal(provider.id, PROVIDER_IDS.debian);
    assert.equal(capability.available, true);
  });

  it('falls back to Microdroid when Debian is not provisioned but stock AVF exists', async () => {
    const deps = makeFakeBridge({
      getpropRelease: '16',
      virtFlag: 'true',
      binaries: {},
      files: { '/apex/com.android.virt/bin/vm': true },
    });
    const { provider, capability } = await selectProvider(deps);
    assert.equal(provider.id, PROVIDER_IDS.microdroid);
    assert.equal(capability.available, true);
    assert.equal(capability.details.vmTool, '/apex/com.android.virt/bin/vm');
  });

  it('reports honestly when nothing is available', async () => {
    const deps = makeFakeBridge({
      getpropRelease: null,
      virtFlag: '',
      binaries: {},
      files: {},
    });
    const { provider, capability } = await selectProvider(deps);
    assert.equal(provider.id, PROVIDER_IDS.none);
    assert.equal(capability.available, false);
    assert.ok(capability.remediation && capability.remediation.length > 0);
    const execRes = await provider.exec('uname -a');
    assert.equal(execRes.ok, false);
  });

  it('Debian without image is not selected even when AVF is supported', async () => {
    const deps = makeFakeBridge({
      getpropRelease: '16',
      virtFlag: 'true',
      binaries: { ssh: '/usr/bin/ssh' },
      files: { '/apex/com.android.virt/bin/vm': true },
    });
    const { provider } = await selectProvider(deps);
    assert.equal(provider.id, PROVIDER_IDS.microdroid);
  });
});
