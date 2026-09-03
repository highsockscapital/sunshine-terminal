import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { generateToken, tokenId, issueSessionToken, loadSessionToken, revokeSessionToken } from '../src/runtimes/auth.js';
import { makeFakeBridge } from './fake-bridge.js';

const VM_DIR = '/tmp/fake-sunshine-vm';
const TOKEN_PATH = `${VM_DIR}/.session-token`;

describe('session tokens', () => {
  it('generates 256-bit hex tokens with stable ids', () => {
    const t = generateToken();
    assert.equal(t.length, 64);
    assert.match(t, /^[0-9a-f]+$/);
    assert.equal(tokenId(t), tokenId(t));
    assert.notEqual(tokenId(generateToken()), tokenId(generateToken()));
  });

  it('issues and reloads a token record', async () => {
    const deps = makeFakeBridge({});
    const issued = await issueSessionToken(deps);
    assert.equal(issued.rotated, false);
    assert.equal(issued.token.length, 64);
    const reloaded = await loadSessionToken(deps);
    assert.equal(reloaded.token, issued.token);
    assert.equal(reloaded.id, issued.tokenId);
  });

  it('flags rotation when a previous token existed', async () => {
    const deps = makeFakeBridge({ configs: { [TOKEN_PATH]: { token: 'old', id: 'old-id', issuedAt: 'x' } } });
    const issued = await issueSessionToken(deps);
    assert.equal(issued.rotated, true);
    assert.notEqual(issued.token, 'old');
  });

  it('revokes cleanly', async () => {
    const deps = makeFakeBridge({});
    const res = await revokeSessionToken(deps);
    assert.equal(res.ok, true);
  });

  it('token ids are safe to log (no token material)', async () => {
    const deps = makeFakeBridge({});
    const { token, tokenId: id } = await issueSessionToken(deps);
    assert.ok(!id.includes(token.slice(0, 8)));
    assert.equal(id.length, 16);
  });
});
