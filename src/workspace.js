// SunshineCLI Workspace Manager
import fs from 'fs/promises';
import path from 'path';
import { info, success, error, colors } from './ui.js';

export async function getWorkspaceDir() {
  return process.cwd();
}

export async function listWorkspace() {
  const dir = await getWorkspaceDir();
  try {
    const entries = await fs.readdir(dir, { withFileTypes: true });
    console.log(`\n${colors.bold}📁 Workspace Directory:${colors.cyan} ${dir}${colors.reset}\n`);
    for (const entry of entries) {
      if (entry.name === 'node_modules' || entry.name === '.git') continue;
      const typeStr = entry.isDirectory() ? `${colors.magenta}[DIR]  ` : `${colors.green}[FILE] `;
      console.log(`  ${typeStr}${colors.reset} ${entry.name}`);
    }
    console.log('');
  } catch (err) {
    error(`Failed to list workspace: ${err.message}`);
  }
}

export async function readFileContent(filePath) {
  const dir = await getWorkspaceDir();
  const fullPath = path.resolve(dir, filePath);
  try {
    const content = await fs.readFile(fullPath, 'utf8');
    console.log(`\n${colors.bold}📄 File: ${colors.cyan}${filePath}${colors.reset}\n`);
    console.log(content);
  } catch (err) {
    error(`Could not read file ${filePath}: ${err.message}`);
  }
}

export async function writeFileContent(filePath, content) {
  const dir = await getWorkspaceDir();
  const fullPath = path.resolve(dir, filePath);
  try {
    await fs.mkdir(path.dirname(fullPath), { recursive: true });
    await fs.writeFile(fullPath, content, 'utf8');
    success(`Successfully wrote to ${filePath}`);
  } catch (err) {
    error(`Could not write file ${filePath}: ${err.message}`);
  }
}
