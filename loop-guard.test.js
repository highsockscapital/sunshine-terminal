import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createRingBuffer, capOutputLines, RING_BUFFER_LINES } from './src/runtimes/bridge.js';
import { executeAVF, resetAgentLoop, checkAgentLoop, DEFAULT_AGENT_MAX_STEPS } from './src/runtimes/vm.js';
import { makeFakeBridge } from './fake-bridge.js';

const VM_DIR = '/tmp/fake-sunshine-vm';

describe('ring buffer', () => {
  it('caps at 10,000 lines by default', () => {
    assert.equal(RING_BUFFER_LINES, 10000);
  });

  it('evicts oldest-first in O(1) amortized time', () => {
    const rb = createRingBuffer(3);
    for (let i = 1; i <= 5; i++) rb.push(`line${i}`);
    assert.deepEqual(rb.lines(), ['line3', 'line4', 'line5']);
    assert.equal(rb.dropped, 2);
    assert.equal(rb.size, 3);
  });

  it('survives a flood without growing', () => {
    const rb = createRingBuffer(100);
    for (let i = 0; i < 20000; i++) rb.push(`l${i}`);
    assert.equal(rb.size, 100);
    assert.equal(rb.dropped, 19900);
    assert.equal(rb.lines()[99], 'l19999');
  });

  it('capOutputLines keeps the tail and counts drops', () => {
    const text = Array.from({ length: 10 }, (_, i) => `l${i}`).join('\n') + '\n';
    const r = capOutputLines(text, 4);
    assert.equal(r.text, 'l6\nl7\nl8\nl9\n');
    assert.equal(r.droppedLines, 6);
  });

  it('passes short output through untouched', () => {
    const r = capOutputLines('a\nb\n', 100);
    assert.deepEqual(r, { text: 'a\nb\n', droppedLines: 0 });
  });
});

function agentDeps(extra = {}) {
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

describe('agent step cap', () => {
  it(`allows ${DEFAULT_AGENT_MAX_STEPS} agent steps, denies the next`, async () => {
    const deps = agentDeps();
    for (let i = 0; i < DEFAULT_AGENT_MAX_STEPS; i++) {
      const res = await executeAVF('uname -a', { origin: 'agent', deps });
      assert.equal(res.ok, true, `step ${i + 1} should pass`);
    }
    const denied = await executeAVF('uname -a', { origin: 'agent', deps });
    assert.equal(denied.ok, false);
    assert.equal(denied.reason, 'agent-step-cap');
    const decision = deps._audit.find((a) => a.event === 'exec-decision' && a.via === 'agent-step-cap');
    assert.ok(decision);
  });

  it('a human command resets the counter (tap to continue)', async () => {
    const deps = agentDeps();
    for (let i = 0; i < DEFAULT_AGENT_MAX_STEPS; i++) {
      await executeAVF('uname -a', { origin: 'agent', deps });
    }
    assert.equal((await executeAVF('uname -a', { origin: 'agent', deps })).reason, 'agent-step-cap');
    const human = await executeAVF('uname -a', { origin: 'human', deps });
    assert.equal(human.ok, true);
    const again = await executeAVF('uname -a', { origin: 'agent', deps });
    assert.equal(again.ok, true);
  });

  it('vm continue resets explicitly', async () => {
    const deps = agentDeps();
    for (let i = 0; i < DEFAULT_AGENT_MAX_STEPS; i++) {
      await executeAVF('uname -a', { origin: 'agent', deps });
    }
    const reset = await resetAgentLoop(deps, 'vm-continue');
    assert.equal(reset.ok, true);
    assert.equal((await executeAVF('uname -a', { origin: 'agent', deps })).ok, true);
    assert.ok(deps._audit.some((a) => a.event === 'loop-continue'));
  });

  it('checkAgentLoop honors a custom cap from config', async () => {
    const deps = agentDeps({
      configs: {
        [`${VM_DIR}/sunshine-vm.json`]: { image: `${VM_DIR}/debian.img`, kernel: `${VM_DIR}/Image`, agentMaxSteps: 2 },
        [`${VM_DIR}/.session-token`]: { token: 'tok-1', id: 'id-1', issuedAt: 'x' },
      },
    });
    assert.equal((await executeAVF('uname -a', { origin: 'agent', deps })).ok, true);
    assert.equal((await executeAVF('uname -a', { origin: 'agent', deps })).ok, true);
    assert.equal((await checkAgentLoop(deps)).allowed, false);
  });
});
