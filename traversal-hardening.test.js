import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'fs/promises';
import os from 'os';
import path from 'path';
import { buildTree, renderTreeLines, resolveCanonical } from '../src/drawer.js';
import { renderMarkdown, INLINE_BUDGET } from '../src/markdown.js';
import { createFileEntry } from '../src/workspace.js';

describe('buildTree symlink handling', () => {
  const sandbox = path.join(os.tmpdir(), `sunshine-symlink-test-${process.pid}`);
  before(async () => {
    await fs.rm(sandbox, { recursive: true, force: true });
    await fs.mkdir(path.join(sandbox, 'a', 'b'), { recursive: true });
    await fs.writeFile(path.join(sandbox, 'a', 'b', 'f.txt'), 'f\n');
    await fs.symlink('..', path.join(sandbox, 'a', 'loop'));
    await fs.symlink('../..', path.join(sandbox, 'a', 'b', 'up'));
    await fs.symlink('b/f.txt', path.join(sandbox, 'a', 'filelink'));
  });
  after(async () => {
    await fs.rm(sandbox, { recursive: true, force: true });
  });

  it('terminates on cyclic symlinks and lists them as leaves', async () => {
    const tree = await buildTree(sandbox, '.', 0, 10);
    const lines = renderTreeLines(tree).join('\n');
    assert.ok(lines.includes('🔗'), 'symlinks render as link leaves');
    assert.ok(lines.includes('loop'), 'cyclic link is listed, not descended');
    const count = (lines.match(/f\.txt/g) || []).length;
    assert.ok(count <= 2, `no exponential revisit, saw f.txt ${count}x`);
  });
});

describe('resolveCanonical containment', () => {
  const sandbox = path.join(os.tmpdir(), `sunshine-canon-test-${process.pid}`);
  const outside = path.join(os.tmpdir(), `sunshine-canon-outside-${process.pid}.txt`);
  before(async () => {
    await fs.rm(sandbox, { recursive: true, force: true });
    await fs.mkdir(sandbox, { recursive: true });
    await fs.writeFile(outside, 'secret\n', 'utf8');
    await fs.symlink(outside, path.join(sandbox, 'evil'));
    await fs.writeFile(path.join(sandbox, 'ok.txt'), 'ok\n', 'utf8');
  });
  after(async () => {
    await fs.rm(sandbox, { recursive: true, force: true });
    await fs.rm(outside, { force: true });
  });

  it('denies symlink escapes to outside files', async () => {
    await assert.rejects(resolveCanonical(sandbox, 'evil'), /escapes workspace/);
    await assert.rejects(resolveCanonical(sandbox, 'evil/../evil'), /escapes workspace/);
  });

  it('resolves normal and fresh paths inside the root', async () => {
    const real = await resolveCanonical(sandbox, 'ok.txt');
    assert.equal(real, await fs.realpath(path.join(sandbox, 'ok.txt')));
    const fresh = await resolveCanonical(sandbox, 'new/sub/file.txt');
    assert.ok(fresh.endsWith(path.join('new', 'sub', 'file.txt')));
  });
});

describe('renderInline length guard', () => {
  it('truncates hostile single lines instead of regex-churning', () => {
    const hostile = '*'.repeat(INLINE_BUDGET + 500);
    const out = renderMarkdown(`# t\n\n${hostile}\n`);
    assert.ok(out.includes('[line truncated]'));
    assert.ok(out.length < INLINE_BUDGET * 2);
  });

  it('keeps formatting for normal lines', () => {
    const out = renderMarkdown('Hello **bold** and `code`\n');
    assert.ok(out.includes('bold'));
    assert.ok(out.includes('code'));
  });
});

describe('createFileEntry atomicity', () => {
  const sandbox = path.join(os.tmpdir(), `sunshine-wx-test-${process.pid}`);
  const realCwd = process.cwd();
  before(async () => {
    await fs.rm(sandbox, { recursive: true, force: true });
    await fs.mkdir(sandbox, { recursive: true });
    process.chdir(sandbox);
  });
  after(async () => {
    process.chdir(realCwd);
    await fs.rm(sandbox, { recursive: true, force: true });
  });

  it('creates in missing parents and refuses clobber atomically', async () => {
    await createFileEntry('deep/nested/f.txt', 'first');
    assert.equal(await fs.readFile(path.join(sandbox, 'deep', 'nested', 'f.txt'), 'utf8'), 'first');
    await createFileEntry('deep/nested/f.txt', 'second');
    assert.equal(await fs.readFile(path.join(sandbox, 'deep', 'nested', 'f.txt'), 'utf8'), 'first');
  });
});
