import { describe, it, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'fs/promises';
import os from 'os';
import path from 'path';
import { truncateOutput, appendAuditLog, DEFAULT_MAX_OUTPUT_BYTES } from './src/runtimes/bridge.js';

describe('truncateOutput', () => {
  it('passes short output through untouched', () => {
    const r = truncateOutput('hello\n');
    assert.equal(r.truncated, false);
    assert.equal(r.text, 'hello\n');
  });
  it('truncates with a marker', () => {
    const r = truncateOutput('x'.repeat(100), 10);
    assert.equal(r.truncated, true);
    assert.ok(r.text.includes('truncated at 10 bytes'));
    assert.ok(r.text.length < 100);
  });
  it('default cap is 256 KiB', () => {
    assert.equal(DEFAULT_MAX_OUTPUT_BYTES, 256 * 1024);
  });
});

describe('appendAuditLog', () => {
  const dir = path.join(os.tmpdir(), `sunshine-audit-test-${process.pid}`);
  after(async () => {
    await fs.rm(dir, { recursive: true, force: true });
  });
  it('writes JSONL with hash, never raw secrets by default', async () => {
    const res = await appendAuditLog(
      { provider: 'debian', origin: 'agent', tier: 3, verdict: 'confirm-explicit', command: 'rm -rf /tmp/x --token s3cr3t' },
      { vmDir: dir },
    );
    assert.equal(res.ok, true);
    const raw = await fs.readFile(path.join(dir, 'audit.log'), 'utf8');
    const rec = JSON.parse(raw.trim());
    assert.equal(rec.provider, 'debian');
    assert.equal(rec.commandHash.length, 16);
    assert.ok(!raw.includes('s3cr3t'), 'token material must not reach the log');
    assert.ok(!('command' in rec));
    assert.ok(rec.commandPreview.includes('[redacted]'));
  });

  it('redacts common secret shapes', async () => {
    const { redactSecrets } = await import('./src/runtimes/bridge.js');
    assert.ok(redactSecrets('x --token abc123').includes('[redacted]'));
    assert.ok(!redactSecrets('x --token abc123').includes('abc123'));
    assert.ok(!redactSecrets('password=hunter2').includes('hunter2'));
  });
});
