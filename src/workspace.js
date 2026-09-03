// Sunshine Terminal Workspace Manager — file ops + File Manager Drawer actions
import fs from 'fs/promises';
import path from 'path';
import { info, success, error, colors } from './ui.js';
import { renderMarkdown, isMarkdownFile } from './markdown.js';
import {
  buildTree,
  renderTreeLines,
  renderDualPanel,
  previewLinesFor,
  resolveCanonical,
  resolveGuarded,
  DEFAULT_MAX_DEPTH,
  MAX_DEPTH_CAP,
} from './drawer.js';

export async function getWorkspaceDir() {
  return process.cwd();
}

export async function listWorkspace() {
  const dir = await getWorkspaceDir();
  try {
    const entries = await fs.readdir(dir, { withFileTypes: true });
    console.log(`\n${colors.bold}${colors.primary}📁 Workspace Directory:${colors.accent} ${dir}${colors.reset}\n`);
    for (const entry of entries) {
      if (entry.name === 'node_modules' || entry.name === '.git') continue;
      const typeStr = entry.isDirectory() ? `${colors.accent}[DIR]  ` : `${colors.secondary}[FILE] `;
      console.log(`  ${typeStr}${colors.reset} ${colors.primary}${entry.name}${colors.reset}`);
    }
    console.log('');
  } catch (err) {
    error(`Failed to list workspace: ${err.message}`);
  }
}

export async function readFileContent(filePath, { raw = false } = {}) {
  const dir = await getWorkspaceDir();
  try {
    const fullPath = await resolveCanonical(dir, filePath);
    const content = await fs.readFile(fullPath, 'utf8');
    if (!raw && isMarkdownFile(filePath)) {
      console.log(`\n${colors.bold}${colors.primary}📝 Preview: ${colors.accent}${filePath}${colors.secondary} (rendered — use --raw for source)${colors.reset}\n`);
      console.log(renderMarkdown(content));
      console.log('');
      return;
    }
    console.log(`\n${colors.bold}${colors.primary}📄 File: ${colors.accent}${filePath}${colors.reset}\n`);
    console.log(`${colors.primary}${content}${colors.reset}`);
  } catch (err) {
    error(`Could not read file ${filePath}: ${err.message}`);
  }
}

export async function showPreview(filePath, { raw = false } = {}) {
  const dir = await getWorkspaceDir();
  try {
    const fullPath = await resolveCanonical(dir, filePath);
    const stat = await fs.stat(fullPath);
    if (stat.isDirectory()) {
      error(`"${filePath}" is a directory. Preview works on .md files.`);
      return;
    }
    const content = await fs.readFile(fullPath, 'utf8');
    if (raw || !isMarkdownFile(filePath)) {
      await readFileContent(filePath, { raw: true });
      return;
    }
    console.log(`\n${colors.bold}${colors.primary}📝 Preview: ${colors.accent}${filePath}${colors.reset}\n`);
    console.log(renderMarkdown(content));
    console.log('');
  } catch (err) {
    if (err.code === 'ENOENT') error(`No such file: ${filePath}`);
    else error(`Could not preview ${filePath}: ${err.message}`);
  }
}

export async function writeFileContent(filePath, content) {
  const dir = await getWorkspaceDir();
  try {
    const fullPath = await resolveGuarded(dir, filePath);
    await fs.mkdir(path.dirname(fullPath), { recursive: true });
    await fs.writeFile(fullPath, content, 'utf8');
    success(`Successfully wrote to ${filePath}`);
  } catch (err) {
    error(`Could not write file ${filePath}: ${err.message}`);
  }
}

export function parseDepth(raw) {
  const n = Number(raw);
  if (!Number.isFinite(n)) return DEFAULT_MAX_DEPTH;
  return Math.min(Math.max(1, Math.floor(n)), MAX_DEPTH_CAP);
}

export async function showTree(target = '.', maxDepth = DEFAULT_MAX_DEPTH) {
  const dir = await getWorkspaceDir();
  try {
    const tree = await buildTree(dir, target, 0, maxDepth);
    const lines = renderTreeLines(tree, { selected: null });
    console.log(`\n${colors.bold}${colors.primary}🌳 Tree:${colors.accent} ${target}${colors.secondary} (depth ${maxDepth})${colors.reset}\n`);
    console.log(lines.join('\n'));
    console.log('');
  } catch (err) {
    error(`Could not render tree for ${target}: ${err.message}`);
  }
}

export async function showDualPanel(target = '.', selected = null, maxDepth = DEFAULT_MAX_DEPTH) {
  const dir = await getWorkspaceDir();
  try {
    const tree = await buildTree(dir, target, 0, maxDepth);
    const treeLines = renderTreeLines(tree, { selected });
    const preview = await previewLinesFor(dir, selected || '.');
    const output = renderDualPanel(treeLines, preview, {
      title: `🗂 Drawer — ${target}`,
      previewTitle: selected ? `👁 ${selected}` : '👁 Preview',
    });
    console.log(`\n${output}\n`);
  } catch (err) {
    error(`Could not render dual panel: ${err.message}`);
  }
}

export async function createFileEntry(filePath, content = '', { overwrite = false } = {}) {
  const dir = await getWorkspaceDir();
  try {
    const fullPath = await resolveGuarded(dir, filePath);
    // mkdir is idempotent, so creating parents first is race-safe; the
    // exclusive 'wx' write below is the atomic existence check.
    await fs.mkdir(path.dirname(fullPath), { recursive: true });
    if (!overwrite) {
      try {
        await fs.writeFile(fullPath, content, { encoding: 'utf8', flag: 'wx' });
        success(`Created file ${filePath}`);
        return;
      } catch (e) {
        if (e.code === 'EEXIST') {
          error(`File already exists: ${filePath} (use write to overwrite)`);
          return;
        }
        throw e;
      }
    }
    await fs.writeFile(fullPath, content, 'utf8');
    success(`Created file ${filePath}`);
  } catch (err) {
    error(`Could not create file ${filePath}: ${err.message}`);
  }
}

export async function createDirEntry(dirPath) {
  const dir = await getWorkspaceDir();
  try {
    const fullPath = await resolveGuarded(dir, dirPath);
    await fs.mkdir(fullPath, { recursive: true });
    success(`Created directory ${dirPath}`);
  } catch (err) {
    error(`Could not create directory ${dirPath}: ${err.message}`);
  }
}

export async function deleteEntry(targetPath, { force = false } = {}) {
  const dir = await getWorkspaceDir();
  try {
    // Canonical guarded resolve: refuses root/.git (any spelling) and
    // symlink escapes before any destructive call.
    const fullPath = await resolveGuarded(dir, targetPath);
    const stat = await fs.stat(fullPath);
    if (stat.isDirectory() && !force) {
      error(`"${targetPath}" is a directory. Re-run with --force to delete recursively.`);
      return;
    }
    await fs.rm(fullPath, { recursive: true, force: true });
    success(`Deleted ${targetPath}`);
  } catch (err) {
    if (err.code === 'ENOENT') error(`No such file or directory: ${targetPath}`);
    else error(`Could not delete ${targetPath}: ${err.message}`);
  }
}

export async function moveEntry(src, dest, { force = false } = {}) {
  const dir = await getWorkspaceDir();
  try {
    const fullSrc = await resolveGuarded(dir, src);
    const fullDest = await resolveGuarded(dir, dest);
    try {
      await fs.stat(fullDest);
      if (!force) {
        error(`Destination exists: ${dest}. Re-run with --force to overwrite.`);
        return;
      }
    } catch (e) {
      if (e.code !== 'ENOENT') throw e;
    }
    await fs.mkdir(path.dirname(fullDest), { recursive: true });
    await fs.rename(fullSrc, fullDest);
    success(`Moved ${src} → ${dest}`);
  } catch (err) {
    error(`Could not move ${src} → ${dest}: ${err.message}`);
  }
}

export async function showContext(targetPath) {
  const dir = await getWorkspaceDir();
  try {
    const fullPath = await resolveCanonical(dir, targetPath);
    const stat = await fs.stat(fullPath);
    const ext = stat.isDirectory() ? '/' : path.extname(fullPath) || '(no ext)';
    console.log(`\n${colors.bold}${colors.accent}◈ Context:${colors.reset} ${colors.primary}${targetPath}${colors.reset}`);
    console.log(`  ${colors.secondary}Type:${colors.reset}     ${colors.primary}${stat.isDirectory() ? 'directory' : 'file'}${colors.reset}`);
    console.log(`  ${colors.secondary}Size:${colors.reset}     ${colors.primary}${stat.size} bytes${colors.reset}`);
    console.log(`  ${colors.secondary}Modified:${colors.reset} ${colors.primary}${stat.mtime.toLocaleString()}${colors.reset}`);
    console.log(`  ${colors.secondary}Ext:${colors.reset}      ${colors.primary}${ext}${colors.reset}`);
    console.log(`\n  ${colors.secondary}Actions:${colors.reset}`);
    if (stat.isDirectory()) {
      console.log(`  ${colors.accent}scli tree ${targetPath}${colors.reset}      - view tree`);
      console.log(`  ${colors.accent}scli panels ${targetPath}${colors.reset}    - dual-panel view`);
      console.log(`  ${colors.accent}scli mkdir ${targetPath}/<name>${colors.reset} - create subdir`);
    } else {
      if (isMarkdownFile(targetPath)) {
        console.log(`  ${colors.accent}scli preview ${targetPath}${colors.reset}  - rendered markdown`);
      }
      console.log(`  ${colors.accent}scli read ${targetPath}${colors.reset}      - preview content`);
      console.log(`  ${colors.accent}scli panels . ${targetPath}${colors.reset}  - preview in right panel`);
    }
    console.log(`  ${colors.accent}scli move ${targetPath} <dest>${colors.reset} - move/rename`);
    console.log(`  ${colors.accent}scli delete ${targetPath}${stat.isDirectory() ? ' --force' : ''}${colors.reset} - delete\n`);
  } catch (err) {
    if (err.code === 'ENOENT') error(`No such file or directory: ${targetPath}`);
    else error(`Could not inspect ${targetPath}: ${err.message}`);
  }
}
