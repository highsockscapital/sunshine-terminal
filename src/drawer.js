// Sunshine Terminal File Manager Drawer — visual tree + dual-panel renderer
// Left panel: directory tree (guides in strokeBorderLight).
// Right panel: file preview or directory summary.
// Styling: accent dirs/selection, primary files, secondary metadata.
import fs from 'fs/promises';
import path from 'path';
import { colors } from './ui.js';
import { renderMarkdown, isMarkdownFile } from './markdown.js';

export const DEFAULT_IGNORE = new Set(['node_modules', '.git']);
export const DEFAULT_MAX_DEPTH = 3;
export const MAX_DEPTH_CAP = 10;

export function safeResolve(root, target) {
  const full = path.resolve(root, target || '.');
  const rel = path.relative(root, full);
  if (rel.startsWith('..') || path.isAbsolute(rel)) {
    throw new Error(`Path escapes workspace: ${target}`);
  }
  return full;
}

// Canonical containment: like safeResolve, but resolves symlinks first so
// a link inside the workspace pointing at /etc (or ~/.ssh) cannot smuggle
// access outside the canonical workspace root. Async (touches fs).
// Nonexistent paths (fresh creates) resolve via the nearest existing
// ancestor. Throws on escape.
export async function resolveCanonical(root, target) {
  const full = safeResolve(root, target); // lexical pre-check (fast fail)
  const realRoot = await fs.realpath(root).catch(() => root);
  // Walk up to the nearest existing ancestor, then rejoin the remainder.
  const missing = [];
  let probe = full;
  let base = null;
  for (;;) {
    try {
      await fs.lstat(probe);
      base = await fs.realpath(probe);
      break;
    } catch (err) {
      if (err.code !== 'ENOENT') throw err;
      const parent = path.dirname(probe);
      if (parent === probe) throw new Error(`Path escapes workspace: ${target}`);
      missing.unshift(path.basename(probe));
      probe = parent;
    }
  }
  const realFull = missing.length > 0 ? path.join(base, ...missing) : base;
  const rel = path.relative(realRoot, realFull);
  if (rel === '' || rel === '.') return realFull; // the root itself
  if (rel.startsWith('..') || path.isAbsolute(rel)) {
    throw new Error(`Path escapes workspace: ${target}`);
  }
  return realFull;
}

// Resolved-path protection: workspace root itself, .git, node_modules
// (any spelling: './', trailing slashes, 'sub/..', absolute paths).
// Outside-workspace paths report protected too (deny by default;
// safeResolve throws for those before this is usually reached).
const PROTECTED_NAMES = new Set(['.git', 'node_modules']);

export function isProtectedPath(root, target) {
  const full = path.resolve(root, target || '.');
  const rel = path.relative(root, full);
  if (rel === '' || rel === '.') return true;
  if (rel.startsWith('..') || path.isAbsolute(rel)) return true;
  return PROTECTED_NAMES.has(rel.split(path.sep)[0]);
}

// For mutating ops: canonical containment + canonical protected check.
// Catches symlink-to-.git and link-to-outside that lexical checks miss.
// Throws on escape or protected target.
export async function resolveGuarded(root, target) {
  const full = await resolveCanonical(root, target);
  const realRoot = await fs.realpath(root).catch(() => root);
  if (isProtectedPath(realRoot, full)) {
    throw new Error(`Refusing protected path: ${target}`);
  }
  return full;
}

export async function buildTree(root, rel = '.', depth = 0, maxDepth = DEFAULT_MAX_DEPTH, seen = new Set()) {
  const full = path.join(root, rel);
  const stat = await fs.stat(full);
  const name = rel === '.' ? path.basename(root) || root : path.basename(full);
  if (!stat.isDirectory()) {
    return { name, rel, isDirectory: false, size: stat.size, mtime: stat.mtime };
  }
  // Canonical cycle guard: never visit the same real directory twice on an
  // active descent stack (kills symlink cycles like link -> .).
  let real;
  try {
    real = await fs.realpath(full);
  } catch {
    return { name, rel, isDirectory: true, unreadable: true };
  }
  if (seen.has(real)) {
    return { name, rel, isDirectory: true, cycle: true, children: [] };
  }
  const node = { name, rel, isDirectory: true, mtime: stat.mtime, children: [] };
  if (depth >= maxDepth) {
    node.truncated = true;
    return node;
  }
  seen.add(real);
  try {
    const entries = await fs.readdir(full, { withFileTypes: true });
    const visible = entries
      .filter((e) => !DEFAULT_IGNORE.has(e.name))
      .sort((a, b) => Number(b.isDirectory()) - Number(a.isDirectory()) || a.name.localeCompare(b.name));
    for (const e of visible) {
      const childRel = rel === '.' ? e.name : path.join(rel, e.name);
      try {
        // Never descend into symlinked directories: list as leaves.
        if (e.isSymbolicLink()) {
          let target = null;
          try {
            target = await fs.readlink(path.join(full, e.name));
          } catch {
            target = null;
          }
          node.children.push({ name: e.name, rel: childRel, isDirectory: false, symlink: true, linkTarget: target });
          continue;
        }
        node.children.push(await buildTree(root, childRel, depth + 1, maxDepth, seen));
      } catch {
        node.children.push({ name: e.name, rel: childRel, isDirectory: e.isDirectory(), unreadable: true });
      }
    }
  } finally {
    seen.delete(real);
  }
  return node;
}

// Render tree to styled lines. Returns string[] (ANSI included).
export function renderTreeLines(tree, { selected = null } = {}) {
  const lines = [];
  const guide = (s) => `${colors.borderLight}${s}${colors.reset}`;
  const rootLabel = `${colors.bold}${colors.accent}📁 ${tree.name}/${colors.reset}`;
  lines.push(rootLabel);
  const walk = (node, prefix) => {
    node.children?.forEach((child, i) => {
      const last = i === node.children.length - 1;
      const branch = last ? '└── ' : '├── ';
      const nextPrefix = prefix + (last ? '    ' : '│   ');
      const isSel = selected && (child.rel === selected || child.rel === selected?.replace(/^\.\//, ''));
      const icon = child.symlink ? '🔗' : child.isDirectory ? '📁' : '📄';
      const nameStyle = child.isDirectory ? colors.accent : colors.primary;
      const selOpen = isSel ? `${colors.accentBg}${colors.onAccent}${colors.bold}` : '';
      const suffix = child.symlink && child.linkTarget
        ? `${colors.secondary} → ${child.linkTarget}${colors.reset}`
        : child.isDirectory ? '/' : '';
      const label = `${guide(prefix + branch)}${selOpen}${nameStyle}${icon} ${child.name}${suffix}${colors.reset}`;
      lines.push(label);
      if (child.cycle) {
        lines.push(`${guide(nextPrefix + '└── ')}${colors.secondary}… (already visited — cycle skipped)${colors.reset}`);
      } else if (child.truncated) {
        lines.push(`${guide(nextPrefix + '└── ')}${colors.secondary}… (max depth)${colors.reset}`);
      } else if (child.isDirectory && child.children) {
        walk(child, nextPrefix);
      }
      if (child.unreadable) {
        lines.push(`${guide(nextPrefix)}${colors.secondary}(unreadable)${colors.reset}`);
      }
    });
  };
  walk(tree, '');
  return lines;
}

function stripAnsi(s) {
  return s.replace(/\x1b\[[0-9;]*m/g, '');
}

function padEndAnsi(s, width) {
  const raw = stripAnsi(s).length;
  if (raw >= width) return s;
  return s + ' '.repeat(width - raw);
}

function truncateAnsi(s, width) {
  if (stripAnsi(s).length <= width) return s;
  // Simple truncation on raw length; ANSI codes may remain but reset is appended by callers.
  let out = '';
  let raw = 0;
  const re = /\x1b\[[0-9;]*m/g;
  let last = 0;
  let m;
  const appendText = (text) => {
    for (const ch of text) {
      if (raw >= width - 1) {
        out += '…';
        raw = width;
        break;
      }
      out += ch;
      raw += 1;
    }
  };
  while ((m = re.exec(s)) !== null) {
    appendText(s.slice(last, m.index));
    out += m[0];
    last = m.index + m[0].length;
    if (raw >= width) break;
  }
  if (raw < width) appendText(s.slice(last));
  return out;
}

// Dual-panel: left tree lines, right preview lines. Returns single string.
export function renderDualPanel(treeLines, previewLines, { width = process.stdout.columns || 80, leftRatio = 0.38, title = '', previewTitle = '' } = {}) {
  const divider = `${colors.border}│${colors.reset}`;
  const leftW = Math.max(20, Math.floor((width - 3) * leftRatio));
  const rightW = Math.max(20, width - leftW - 3);
  const header = `${colors.bold}${colors.primary}${truncateAnsi(title, leftW)}${colors.reset} ${divider} ${colors.bold}${colors.accent}${truncateAnsi(previewTitle, rightW)}${colors.reset}`;
  const rule = `${colors.borderLight}${'─'.repeat(Math.min(width, 80))}${colors.reset}`;
  const rows = Math.max(treeLines.length, previewLines.length);
  const out = [header, rule];
  for (let i = 0; i < rows; i++) {
    const l = padEndAnsi(truncateAnsi(treeLines[i] || '', leftW), leftW);
    const r = truncateAnsi(previewLines[i] || '', rightW);
    out.push(`${l} ${divider} ${r}`);
  }
  return out.join('\n');
}

export async function previewLinesFor(root, targetRel, { maxLines = 30, maxBytes = 20000 } = {}) {
  if (!targetRel || targetRel === '.') {
    const entries = await fs.readdir(path.join(root, '.'), { withFileTypes: true });
    const visible = entries.filter((e) => !DEFAULT_IGNORE.has(e.name)).slice(0, maxLines);
    return [
      `${colors.secondary}Directory summary — pick a file to preview${colors.reset}`,
      ...visible.map((e) => `${e.isDirectory() ? `${colors.accent}[DIR]  ` : `${colors.secondary}[FILE] `}${colors.reset} ${colors.primary}${e.name}${colors.reset}`),
    ];
  }
  const full = await resolveCanonical(root, targetRel);
  const stat = await fs.stat(full);
  if (stat.isDirectory()) {
    const entries = await fs.readdir(full, { withFileTypes: true });
    const visible = entries.filter((e) => !DEFAULT_IGNORE.has(e.name)).slice(0, maxLines);
    return [
      `${colors.secondary}📁 ${targetRel}/ — ${entries.length} entries${colors.reset}`,
      ...visible.map((e) => `${e.isDirectory() ? `${colors.accent}[DIR]  ` : `${colors.secondary}[FILE] `}${colors.reset} ${colors.primary}${e.name}${colors.reset}`),
    ];
  }
  if (stat.size > maxBytes * 10) {
    return [`${colors.secondary}(binary or large file — ${stat.size} bytes, preview skipped)${colors.reset}`];
  }
  try {
    const content = await fs.readFile(full, 'utf8');
    if (isMarkdownFile(targetRel)) {
      const rendered = renderMarkdown(content.slice(0, maxBytes)).split('\n');
      const header = `${colors.secondary}📝 ${targetRel} — rendered markdown${colors.reset}`;
      return [header, ...rendered.slice(0, maxLines)];
    }
    const sliced = content.slice(0, maxBytes).split('\n').slice(0, maxLines);
    return sliced.map((line, i) => `${colors.secondary}${String(i + 1).padStart(3)}${colors.reset} ${colors.primary}${line}${colors.reset}`);
  } catch {
    return [`${colors.secondary}(binary file — preview unavailable)${colors.reset}`];
  }
}
