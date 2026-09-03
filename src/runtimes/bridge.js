// Sunshine Terminal AVF bridge — stdlib-only host transports.
// Everything here is injectable: engines take `deps` so tests can fake
// the host without a shell, a device, or AVF hardware.
import { spawnSync, spawn } from 'child_process';
import fs from 'fs/promises';
import os from 'os';
import path from 'path';

export function defaultVmDir() {
  return path.join(os.homedir(), '.sunshine', 'vm');
}

export async function ensureVmDir(vmDir = defaultVmDir()) {
  await fs.mkdir(vmDir, { recursive: true });
  return vmDir;
}

// Run a host binary synchronously with a timeout. Never throws for
// expected failures — returns { ok, code, stdout, stderr, reason }.
// maxOutputBytes truncates each stream (fork-bomb / log-spam backstop);
// truncated streams set `truncated: true`.
export const DEFAULT_MAX_OUTPUT_BYTES = 256 * 1024;

export function truncateOutput(text, maxBytes = DEFAULT_MAX_OUTPUT_BYTES) {
  if (text.length <= maxBytes) return { text, truncated: false };
  return { text: text.slice(0, maxBytes) + `\n…[truncated at ${maxBytes} bytes]\n`, truncated: true };
}

export function runHost(command, args = [], { timeoutMs = 15000, input = null, maxOutputBytes = DEFAULT_MAX_OUTPUT_BYTES } = {}) {
  try {
    const res = spawnSync(command, args, {
      encoding: 'utf8',
      timeout: timeoutMs,
      input,
      windowsHide: true,
      // Generous ceiling so large outputs (apt logs, container output)
      // arrive intact; truncateOutput enforces the policy cap below.
      // (A tight maxBuffer would ENOBUFS-kill the child first.)
      maxBuffer: 16 * 1024 * 1024,
    });
    if (res.error) {
      const code = res.error.code || 'SPAWN_ERROR';
      return { ok: false, code: null, stdout: res.stdout || '', stderr: res.stderr || '', reason: String(code) };
    }
    const out = truncateOutput(res.stdout || '', maxOutputBytes);
    const err = truncateOutput(res.stderr || '', maxOutputBytes);
    return {
      ok: res.status === 0,
      code: res.status,
      stdout: out.text,
      stderr: err.text,
      truncated: out.truncated || err.truncated,
      reason: res.status === 0 ? null : `exit-${res.status}`,
    };
  } catch (err) {
    return { ok: false, code: null, stdout: '', stderr: '', reason: err.message };
  }
}

// Open a file for appending, returning its fd (for detached stdio redirect).
async function openAppendFd(filePath) {
  const { open } = await import('fs');
  return new Promise((resolve, reject) => {
    open(filePath, 'a', (err, fd) => (err ? reject(err) : resolve(fd)));
  });
}

export async function launchDetachedAsync(command, args = [], { stdoutFile, stderrFile, cwd } = {}) {
  // Launch a long-lived process detached, stdio redirected to files.
  // Returns { ok, pid, reason }. Used for crosvm boot.
  try {
    const stdio = ['ignore'];
    stdio.push(stdoutFile ? await openAppendFd(stdoutFile) : 'ignore');
    stdio.push(stderrFile ? await openAppendFd(stderrFile) : 'ignore');
    const child = spawn(command, args, { detached: true, stdio, cwd, windowsHide: true });
    child.unref();
    return { ok: true, pid: child.pid || null, reason: null };
  } catch (err) {
    return { ok: false, pid: null, reason: err.message };
  }
}

export async function fileExists(p) {
  try {
    await fs.stat(p);
    return true;
  } catch {
    return false;
  }
}

// Pure-fs PATH lookup (no shell needed).
export async function whichBinary(name, extraDirs = []) {
  const pathEnv = process.env.PATH || '';
  const dirs = [...extraDirs, ...pathEnv.split(path.delimiter).filter(Boolean)];
  for (const dir of dirs) {
    const candidate = path.join(dir, name);
    try {
      const stat = await fs.stat(candidate);
      if (stat.isFile()) return candidate;
    } catch {
      // keep searching
    }
  }
  return null;
}

export async function readJsonSafe(filePath, fallback = null) {
  try {
    const raw = await fs.readFile(filePath, 'utf8');
    return JSON.parse(raw);
  } catch {
    return fallback;
  }
}

// Strict JSON read that distinguishes a missing file from a corrupt one.
// - missing → { ok:false, reason:'missing' } (caller provisions defaults)
// - corrupt → backs the file up to <path>.bak and returns
//   { ok:false, reason:'corrupt', backup } (caller must abort, never
//   silently overwrite user configuration)
// - ok → { ok:true, data }
export async function readJsonFile(filePath) {
  let raw;
  try {
    raw = await fs.readFile(filePath, 'utf8');
  } catch (err) {
    if (err.code === 'ENOENT') return { ok: false, reason: 'missing' };
    return { ok: false, reason: err.message };
  }
  try {
    return { ok: true, data: JSON.parse(raw) };
  } catch (err) {
    const backup = `${filePath}.bak`;
    try {
      await fs.copyFile(filePath, backup);
    } catch (copyErr) {
      return { ok: false, reason: 'corrupt', backup: null, backupError: copyErr.message, detail: err.message };
    }
    return { ok: false, reason: 'corrupt', backup, detail: err.message };
  }
}

export async function writeJsonSafe(filePath, data, { mode = null } = {}) {
  try {
    await fs.mkdir(path.dirname(filePath), { recursive: true });
    await fs.writeFile(filePath, JSON.stringify(data, null, 2) + '\n', mode ? { encoding: 'utf8', mode } : 'utf8');
    return { ok: true, reason: null };
  } catch (err) {
    return { ok: false, reason: err.message };
  }
}

export async function readTextTail(filePath, maxLines = 50) {
  try {
    const content = await fs.readFile(filePath, 'utf8');
    const lines = content.split('\n');
    return { ok: true, lines: lines.slice(-maxLines), reason: null };
  } catch (err) {
    return { ok: false, lines: [], reason: err.code === 'ENOENT' ? 'no-log-file' : err.message };
  }
}

// Terminal ring buffer: guest stdout streams into a capped circular
// buffer (default 10,000 lines) so a runaway loop
// (`while true; do echo bug; done`) drops old lines instead of OOMing
// the host. Oldest lines evict first; `dropped` counts evictions.
export const RING_BUFFER_LINES = 10000;

export function createRingBuffer(cap = RING_BUFFER_LINES) {
  const size = Math.max(1, Math.floor(cap));
  const buf = new Array(size);
  let start = 0;
  let count = 0;
  let dropped = 0;
  return {
    push(line) {
      if (count < size) {
        buf[(start + count) % size] = line;
        count += 1;
      } else {
        buf[start] = line;
        start = (start + 1) % size;
        dropped += 1;
      }
    },
    lines() {
      const out = [];
      for (let i = 0; i < count; i++) out.push(buf[(start + i) % size]);
      return out;
    },
    get dropped() {
      return dropped;
    },
    get size() {
      return count;
    },
  };
}

// Cap already-materialized output through a ring buffer. Returns
// { text, droppedLines } — drop-in for stored guest stdout/stderr.
export function capOutputLines(text, cap = RING_BUFFER_LINES) {
  const lines = String(text || '').split('\n');
  // A trailing newline creates one empty phantom line; don't count it.
  const trailingEmpty = lines.length > 0 && lines[lines.length - 1] === '' ? 1 : 0;
  const content = lines.slice(0, lines.length - trailingEmpty);
  if (content.length <= cap) return { text: String(text || ''), droppedLines: 0 };
  const kept = content.slice(content.length - cap);
  return { text: kept.join('\n') + '\n', droppedLines: content.length - cap };
}

// Best-effort secret scrubbing for log previews. Not a boundary —
// full secrecy comes from logging hashes only (default).
export function redactSecrets(text) {
  return String(text)
    .replace(/(--token[=\s]+)(\S+)/gi, '$1[redacted]')
    .replace(/((?:password|passwd|secret|api[_-]?key|bearer)[=\s:]+)(\S+)/gi, '$1[redacted]');
}

// Default live deps object engines consume. Tests inject fakes instead.
export const liveBridge = {
  runHost,
  launchDetachedAsync,
  fileExists,
  whichBinary,
  readJsonSafe,
  readJsonFile,
  writeJsonSafe,
  readTextTail,
  ensureVmDir,
  defaultVmDir,
  appendAuditLog,
};

// Append one audit record (JSONL). Best-effort: never throws.
// Records command *hashes* plus tier/origin/verdict — full command text only
// when includeCommand is set (default redacts to 120 chars to avoid
// credential leakage into logs).
export async function appendAuditLog(entry, { includeCommand = false, vmDir = null } = {}) {
  try {
    const { createHash } = await import('crypto');
    const { command, ...rest } = entry;
    const record = {
      at: new Date().toISOString(),
      ...rest,
      commandHash: command ? createHash('sha256').update(String(command), 'utf8').digest('hex').slice(0, 16) : null,
    };
    if (includeCommand && command) {
      record.command = redactSecrets(String(command)).slice(0, 120);
    } else if (command) {
      record.commandPreview = redactSecrets(String(command)).slice(0, 60);
    }
    const line = JSON.stringify(record) + '\n';
    const logFile = path.join(vmDir || defaultVmDir(), 'audit.log');
    await fs.mkdir(path.dirname(logFile), { recursive: true });
    await fs.appendFile(logFile, line, 'utf8');
    return { ok: true };
  } catch (err) {
    return { ok: false, reason: err.message };
  }
}
