// Sunshine Terminal session auth — per-boot channel tokens.
// A 256-bit token is issued at boot, stored 0600 host-side, injected into
// the guest (tmpfs, 0600), and validated per exec by the guest-side
// sunshine-exec shim. Only the token *id* (sha256 prefix) ever appears in
// logs or status output — never the token itself.
import { randomBytes, createHash } from 'crypto';
import fs from 'fs/promises';
import path from 'path';
import { liveBridge } from './bridge.js';

export const TOKEN_FILE = '.session-token';
export const TOKEN_BYTES = 32;

export function generateToken() {
  return randomBytes(TOKEN_BYTES).toString('hex');
}

export function tokenId(token) {
  return createHash('sha256').update(token, 'utf8').digest('hex').slice(0, 16);
}

export function tokenFile(vmDir) {
  return path.join(vmDir, TOKEN_FILE);
}

// Issue (or re-issue) the session token. Returns { token, tokenId, rotated }.
// rotated=true when a previous token was replaced. The file is created
// atomically with 0600 — no world-readable window (no write+chmod dance).
export async function issueSessionToken(deps = liveBridge) {
  const dir = deps.defaultVmDir();
  await deps.ensureVmDir(dir);
  const file = tokenFile(dir);
  let previous = null;
  try {
    previous = await deps.readJsonSafe(file, null);
  } catch {
    previous = null;
  }
  const token = generateToken();
  const record = { token, id: tokenId(token), issuedAt: new Date().toISOString() };
  if (deps.writeJsonSafe) {
    const saved = await deps.writeJsonSafe(file, record, { mode: 0o600 });
    if (!saved.ok) throw new Error(`Could not store session token: ${saved.reason}`);
  } else {
    await fs.writeFile(file, JSON.stringify(record), { mode: 0o600 });
  }
  return { token, tokenId: record.id, rotated: Boolean(previous && previous.token) };
}

// Load the current token record without exposing it to callers that log.
// Returns { token, id, issuedAt } or null.
export async function loadSessionToken(deps = liveBridge) {
  const record = await deps.readJsonSafe(tokenFile(deps.defaultVmDir()), null);
  if (!record || !record.token) return null;
  return record;
}

export async function revokeSessionToken(deps = liveBridge) {
  const file = tokenFile(deps.defaultVmDir());
  try {
    await fs.unlink(file);
    return { ok: true };
  } catch (err) {
    if (err.code === 'ENOENT') return { ok: true };
    // Fake bridges without unlink: overwrite with garbage then report.
    if (deps.writeJsonSafe) {
      await deps.writeJsonSafe(file, { revoked: true, at: new Date().toISOString() });
      return { ok: true };
    }
    return { ok: false, reason: err.message };
  }
}
