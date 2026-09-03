import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'fs/promises';
import os from 'os';
import path from 'path';
import { readJsonFile } from '../src/runtimes/bridge.js';
import { createDebianEngine } from '../src/runtimes/debian.js';
import { createMicrodroidEngine } from '../src/runtimes/microdroid.js';
import { readFileContent, writeFileContent } from '../src/workspace.js';
import { makeFakeBridge } from './fake-bridge.js';

const VM_DIR = '/tmp/fake-sunshine-vm';

describe('readJsonFile', () => {
  const dir = path.join(os.tmpdir(), `sunshine-json-test-${process.pid}`);
  before(async () => {
    await fs.rm(dir, { recursive: true, force: true });
    await fs.mkdir(dir, { recursive: true });
  });
  after(async () => {
    await fs.rm(dir, { recursive: true, force: true });
  });

  it('reports missing files distinctly', async () => {
    const res = await readJsonFile(path.join(dir, 'nope.json'));
    assert.equal(res.ok, false);
    assert.equal(res.reason, 'missing');
  });

  it('backs up corrupt files and reports corrupt', async () => {
    const file = path.join(dir, 'bad.json');
    await fs.writeFile(file, '{ not valid json,,,\n', 'utf8');
    const res = await readJsonFile(file);
    assert.equal(res.ok, false);
    assert.equal(res.reason, 'corrupt');
    assert.ok(res.backup.endsWith('bad.json.bak'));
    assert.equal(await fs.readFile(res.backup, 'utf8'), '{ not valid json,,,\n');
  });

  it('parses valid files', async () => {
    const file = path.join(dir, 'good.json');
    await fs.writeFile(file, '{"a":1}', 'utf8');
    const res = await readJsonFile(file);
    assert.equal(res.ok, true);
    assert.deepEqual(res.data, { a: 1 });
  });
});

describe('debian corrupt config', () => {
  it('reports config-corrupt and never overwrites user config', async () => {
    const base = makeFakeBridge({});
    const deps = {
      ...base,
      readJsonFile: async () => ({ ok: false, reason: 'corrupt', backup: '/tmp/x.bak' }),
    };
    const cap = await createDebianEngine(deps).probe();
    assert.equal(cap.available, false);
    assert.equal(cap.reason, 'config-corrupt');
    assert.ok(cap.remediation.includes('/tmp/x.bak'));
    assert.ok(!Object.keys(base._written).some((k) => k.endsWith('sunshine-vm.json')));
  });
});

describe('microdroid positional validation', () => {
  function payloadDeps(payload) {
    return makeFakeBridge({
      getpropRelease: '16',
      virtFlag: 'true',
      files: { '/apex/com.android.virt/bin/vm': true },
      configs: {
        [`${VM_DIR}/microdroid.json`]: { payload },
      },
    });
  }

  it('fails fast when positionals are missing', async () => {
    const deps = payloadDeps({ binary: 'libpayload.so' });
    const res = await createMicrodroidEngine(deps).exec('uname -a');
    assert.equal(res.ok, false);
    assert.equal(res.reason, 'payload-incomplete');
    assert.ok(res.remediation.includes('app'));
    assert.ok(!deps._runs.some((r) => String(r.command).includes('vm')),
      'vm tool must never run with shifted positionals');
  });

  it('passes positionals through unshifted when complete', async () => {
    const deps = payloadDeps({
      binary: 'libpayload.so', app: '/a.apk', idsig: '/a.idsig', instanceImg: '/i.img',
    });
    deps.runHost = async (command, args = [], opts = {}) => {
      deps._runs.push({ command, args, opts });
      return { ok: true, code: 0, stdout: 'ok\n', stderr: '', reason: null };
    };
    const res = await createMicrodroidEngine(deps).exec('uname -a');
    assert.equal(res.ok, true);
    const call = deps._runs[0];
    const appIdx = call.args.indexOf('/a.apk');
    assert.ok(appIdx > 0);
    assert.deepEqual(call.args.slice(appIdx, appIdx + 3), ['/a.apk', '/a.idsig', '/i.img']);
  });
});

describe('workspace traversal guards (tmp sandbox)', () => {
  const sandbox = path.join(os.tmpdir(), `sunshine-traverse-test-${process.pid}`);
  const outside = path.join(os.tmpdir(), `sunshine-outside-test-${process.pid}.txt`);
  const realCwd = process.cwd();
  before(async () => {
    await fs.rm(sandbox, { recursive: true, force: true });
    await fs.mkdir(sandbox, { recursive: true });
    await fs.writeFile(outside, 'untouched\n', 'utf8');
    process.chdir(sandbox);
  });
  after(async () => {
    process.chdir(realCwd);
    await fs.rm(sandbox, { recursive: true, force: true });
    await fs.rm(outside, { force: true });
  });

  it('read refuses to escape the workspace', async () => {
    await readFileContent('../sunshine-outside-test-' + process.pid + '.txt');
    assert.equal(await fs.readFile(outside, 'utf8'), 'untouched\n');
  });

  it('write refuses to escape the workspace', async () => {
    await writeFileContent('../sunshine-outside-test-' + process.pid + '.txt', 'pwned');
    assert.equal(await fs.readFile(outside, 'utf8'), 'untouched\n');
  });

  it('write round-trips multi-word content', async () => {
    await writeFileContent('multi.txt', 'hello brave new world');
    assert.equal(await fs.readFile(path.join(sandbox, 'multi.txt'), 'utf8'), 'hello brave new world');
  });
});
