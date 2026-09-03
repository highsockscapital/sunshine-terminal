import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'fs/promises';
import os from 'os';
import path from 'path';
import { TIERS, classifyCommand, stripQuotedSpans } from './src/runtimes/policy.js';
import { writeJsonSafe } from './src/runtimes/bridge.js';
import { issueSessionToken } from './src/runtimes/auth.js';
import { liveBridge } from './src/runtimes/bridge.js';

describe('quoted string literals', () => {
  it('drops multi-word literals, keeps single-word command words', () => {
    assert.equal(stripQuotedSpans('echo "don\'t rm -rf"').trim(), 'echo');
    assert.equal(stripQuotedSpans('"rm" -rf /tmp/x').trim(), 'rm -rf /tmp/x');
  });

  const safe = ['echo "don\'t rm -rf /"', 'grep "rm -rf" file.log', "echo 'sudo is scary'"];
  for (const cmd of safe) {
    it(`Tier 1 (quoted literal): ${cmd.slice(0, 40)}`, () => {
      assert.equal(classifyCommand(cmd).tier, TIERS.SAFE);
    });
  }

  const destructive = ['rm -rf "my dir"', '"rm" -rf /tmp/x', 'echo "$(rm -rf /)"', 'sudo "apt install foo"'];
  for (const cmd of destructive) {
    it(`Tier 3 (quotes are not cover): ${cmd.slice(0, 40)}`, () => {
      assert.equal(classifyCommand(cmd).tier, TIERS.DESTRUCTIVE);
    });
  }
});

describe('bare ssh/mv stay Tier 2 (inline chip, not modal)', () => {
  for (const cmd of ['ssh sunshine@127.0.0.1', 'mv old.txt new.txt', 'scp a b:']) {
    it(`Tier 2: ${cmd}`, () => {
      const d = classifyCommand(cmd);
      assert.equal(d.tier, TIERS.STATE_CHANGE);
    });
  }
});

describe('atomic 0600 file creation', () => {
  const dir = path.join(os.tmpdir(), `sunshine-mode-test-${process.pid}`);
  before(async () => {
    await fs.rm(dir, { recursive: true, force: true });
    await fs.mkdir(dir, { recursive: true });
  });
  after(async () => {
    await fs.rm(dir, { recursive: true, force: true });
  });

  it('writeJsonSafe honors mode', async () => {
    const file = path.join(dir, 'secret.json');
    const res = await writeJsonSafe(file, { a: 1 }, { mode: 0o600 });
    assert.equal(res.ok, true);
    assert.equal((await fs.stat(file)).mode & 0o777, 0o600);
  });

  it('session tokens land 0600 with no chmod dance', async () => {
    const deps = { ...liveBridge, defaultVmDir: () => dir };
    const issued = await issueSessionToken(deps);
    assert.equal(issued.token.length, 64);
    const st = await fs.stat(path.join(dir, '.session-token'));
    assert.equal(st.mode & 0o777, 0o600);
  });
});
