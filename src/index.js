// Sunshine Terminal Main Controller
import { banner, promptSymbol, info, success, warning, error, colors } from './ui.js';
import {
  listWorkspace,
  readFileContent,
  writeFileContent,
  showPreview,
  showTree,
  showDualPanel,
  createFileEntry,
  createDirEntry,
  deleteEntry,
  moveEntry,
  showContext,
  parseDepth,
} from './workspace.js';
import { checkAVFStatus, executeAVF, bootVM, shutdownVM, showVMLogs, openVMShell, provisionVM, setLockdownMode, showThermal, resetAgentLoop, pingGuestVM, watchVM } from './runtimes/vm.js';

function hasFlag(args, ...flags) {
  return flags.some((f) => args.includes(f));
}

function argAfter(args, ...flags) {
  for (let i = 0; i < args.length; i++) {
    const a = args[i];
    for (const f of flags) {
      if (a === f && args[i + 1]) return args[i + 1];
      if (a.startsWith(f + '=') && f.startsWith('--')) return a.slice(f.length + 1);
    }
  }
  return null;
}

// Positional args after the command, with flags (and their values) removed.
function positionals(args, flagsWithValue = ['--depth', '-d', '--path', '-p', '--tail', '-n', '--interval-ms']) {
  const out = [];
  for (let i = 1; i < args.length; i++) {
    const a = args[i];
    if (flagsWithValue.includes(a)) {
      i++; // skip its value
      continue;
    }
    if (a.startsWith('--') && a.includes('=')) continue;
    if (a === '--force' || a === '-f' || a === '--tree' || a === '--raw' || a === '--rendered'
      || a === '--agent' || a === '--approve-risk' || a === '--yes') continue;
    if (a.startsWith('-')) continue;
    out.push(a);
  }
  return out;
}

function depthFrom(args, fallbackPos) {
  const flagVal = argAfter(args, '--depth', '-d');
  const raw = flagVal ?? fallbackPos;
  return parseDepth(raw);
}

export async function main() {
  const args = process.argv.slice(2);
  const command = args[0] || 'status';
  const subArg1 = args[1];
  const subArg2 = args[2];

  switch (command) {
    case 'status':
    case '--status':
    case '-s':
      console.log(banner());
      info(`Bonjour! Sunshine Terminal is fully operational and ready.`);
      await checkAVFStatus();
      await listWorkspace();
      break;

    case 'workspace':
    case 'ws':
      if (subArg1 === 'tree' || hasFlag(args, '--tree')) {
        const pos = positionals(args).filter((p) => p !== 'tree');
        await showTree(pos[0] || '.', depthFrom(args, pos[1]));
      } else {
        await listWorkspace();
      }
      break;

    case 'tree': {
      const pos = positionals(args);
      await showTree(pos[0] || '.', depthFrom(args, pos[1]));
      break;
    }

    case 'panels':
    case 'drawer':
    case 'split': {
      // scli panels [dir] [selected] — either may be omitted
      const pos = positionals(args);
      const target = pos[0] || '.';
      const selected = pos[1] || null;
      await showDualPanel(target, selected, depthFrom(args));
      break;
    }

    case 'create': {
      const pos = positionals(args);
      if (!pos[0]) {
        error(`Usage: scli create <file> ["<content>"]`);
        break;
      }
      await createFileEntry(pos[0], pos.slice(1).join(' '));
      break;
    }

    case 'mkdir':
      if (!subArg1) {
        error(`Usage: scli mkdir <dir>`);
        break;
      }
      await createDirEntry(subArg1);
      break;

    case 'delete':
    case 'rm':
    case 'remove': {
      const target = positionals(args)[0] || argAfter(args, '--path', '-p');
      if (!target) {
        error(`Usage: scli delete <path> [--force]`);
        break;
      }
      await deleteEntry(target, { force: hasFlag(args, '--force', '-f') });
      break;
    }

    case 'move':
    case 'mv':
    case 'rename': {
      const pos = positionals(args);
      if (!pos[0] || !pos[1]) {
        error(`Usage: scli move <src> <dest> [--force]`);
        break;
      }
      await moveEntry(pos[0], pos[1], { force: hasFlag(args, '--force', '-f') });
      break;
    }

    case 'context':
    case 'info-path':
    case 'stat':
      if (!subArg1) {
        error(`Usage: scli context <path>`);
        break;
      }
      await showContext(subArg1);
      break;

    case 'read': {
      const pos = positionals(args);
      if (!pos[0]) {
        error(`Please specify a file path to read. Usage: scli read <file> [--raw]`);
        break;
      }
      // .md files render by default; --raw shows source.
      await readFileContent(pos[0], { raw: hasFlag(args, '--raw') });
      break;
    }

    case 'preview':
    case 'md': {
      const pos = positionals(args);
      if (!pos[0]) {
        error(`Usage: scli preview <file.md> [--raw]`);
        break;
      }
      await showPreview(pos[0], { raw: hasFlag(args, '--raw') });
      break;
    }

    case 'write': {
      const pos = positionals(args);
      if (!pos[0] || pos.length < 2) {
        error(`Usage: scli write <file> "<content>"`);
        break;
      }
      await writeFileContent(pos[0], pos.slice(1).join(' '));
      break;
    }

    case 'run':
    case 'exec': {
      const origin = hasFlag(args, '--agent') ? 'agent' : 'human';
      const autoApprove = hasFlag(args, '--approve-risk', '--yes');
      const cmd = args.slice(1).filter((a) => !['--agent', '--approve-risk', '--yes'].includes(a)).join(' ');
      if (!cmd) {
        error(`Please specify a command to run in AVF Linux VM. Usage: scli run [--agent] [--approve-risk] "<command>"`);
        break;
      }
      await executeAVF(cmd, { origin, autoApprove });
      break;
    }

    case 'vm': {
      if (!subArg1 || subArg1 === 'status') {
        await checkAVFStatus();
      } else if (subArg1 === 'boot') {
        await bootVM();
      } else if (subArg1 === 'shutdown') {
        await shutdownVM();
      } else if (subArg1 === 'provision') {
        await provisionVM();
      } else if (subArg1 === 'lockdown') {
        const mode = (positionals(args)[1] || '').toLowerCase();
        if (mode !== 'on' && mode !== 'off') {
          error(`Usage: scli vm lockdown <on|off>`);
          break;
        }
        await setLockdownMode(mode === 'on');
      } else if (subArg1 === 'logs') {
        const tailRaw = argAfter(args, '--tail', '-n') || positionals(args)[1];
        const tail = Math.min(Math.max(Number(tailRaw) || 50, 1), 500);
        await showVMLogs(tail);
      } else if (subArg1 === 'shell') {
        await openVMShell();
      } else if (subArg1 === 'thermal') {
        await showThermal();
      } else if (subArg1 === 'ping') {
        await pingGuestVM();
      } else if (subArg1 === 'watch') {
        const intervalMs = Math.min(Math.max(Number(argAfter(args, '--interval-ms') || positionals(args)[1]) || 2500, 500), 60000);
        await watchVM({ intervalMs, autoBoot: hasFlag(args, '--auto-boot') });
      } else if (subArg1 === 'continue') {
        const res = await resetAgentLoop(undefined, 'vm-continue');
        if (res.ok) success(`Agent step counter reset — execution may continue.`);
      } else {
        const origin = hasFlag(args, '--agent') ? 'agent' : 'human';
        const autoApprove = hasFlag(args, '--approve-risk', '--yes');
        const vmCommand = args.slice(1).filter((a) => !['--agent', '--approve-risk', '--yes'].includes(a)).join(' ');
        await executeAVF(vmCommand, { origin, autoApprove });
      }
      break;
    }

    case 'bionic':
      error(`Bionic runtime operations are now handled directly via Termux. Use your Termux shell for native operations.`);
      info(`Tip: ${colors.accent}sunshine status${colors.reset} shows AVF VM status, or use your Termux prompt for bionic commands.`);
      break;

    case 'help':
    case '--help':
    case '-h':
      console.log(banner());
      console.log(`${colors.bold}Available Commands:${colors.reset}`);
      console.log(`  ${colors.accent}scli status${colors.reset}                  - Show AVF runtime status & workspace summary`);
      console.log(`  ${colors.accent}scli workspace${colors.reset} [--tree]      - List files or show tree drawer`);
      console.log(`  ${colors.accent}scli tree [path]${colors.reset}             - Visual tree viewer (use --depth N)`);
      console.log(`  ${colors.accent}scli panels [dir] [file]${colors.reset}     - Dual-panel: drawer + preview`);
      console.log(`  ${colors.accent}scli read <file>${colors.reset} [--raw]      - Read file (.md renders by default)`);
      console.log(`  ${colors.accent}scli preview <file.md>${colors.reset} [--raw] - Native markdown rendering`);
      console.log(`  ${colors.accent}scli write <file> <text>${colors.reset}     - Write text to workspace file`);
      console.log(`  ${colors.accent}scli create <file> [text]${colors.reset}    - Create new file (no overwrite)`);
      console.log(`  ${colors.accent}scli mkdir <dir>${colors.reset}             - Create directory`);
      console.log(`  ${colors.accent}scli delete <path>${colors.reset} [--force] - Delete file (dirs need --force)`);
      console.log(`  ${colors.accent}scli move <src> <dest>${colors.reset} [--force] - Move / rename (no clobber by default)`);
      console.log(`  ${colors.accent}scli context <path>${colors.reset}          - Stat + suggested actions`);
      console.log(`  ${colors.accent}scli run <cmd>${colors.reset} [--agent] [--approve-risk] - Execute in AVF guest`);
      console.log(`  ${colors.accent}scli vm status${colors.reset}               - AVF provider + capability report`);
      console.log(`  ${colors.accent}scli vm boot${colors.reset}                 - Boot the selected AVF guest`);
      console.log(`  ${colors.accent}scli vm provision${colors.reset}            - Install guest security bundle`);
      console.log(`  ${colors.accent}scli vm lockdown <on|off>${colors.reset}    - Outbound allowlist profile`);
      console.log(`  ${colors.accent}scli vm shutdown${colors.reset}             - Shut down the AVF guest`);
      console.log(`  ${colors.accent}scli vm logs${colors.reset} [--tail N]      - Show guest console logs`);
      console.log(`  ${colors.accent}scli vm shell${colors.reset}                - Open interactive guest shell (Debian)`);
      console.log(`  ${colors.accent}scli vm thermal${colors.reset}              - Battery + skin temp + throttle state`);
      console.log(`  ${colors.accent}scli vm ping${colors.reset}                 - One-shot guest heartbeat`);
      console.log(`  ${colors.accent}scli vm watch${colors.reset} [--auto-boot]  - Heartbeat loop with recovery`);
      console.log(`  ${colors.accent}scli vm continue${colors.reset}             - Reset agent step counter after cap pause`);
      console.log(`  ${colors.accent}scli vm <cmd>${colors.reset}                - Run command in the AVF guest`);
      console.log(`  ${colors.accent}scli help${colors.reset}                    - Display this help message\n`);
      break;

    default:
      console.log(banner());
      error(`Unknown command: "${command}"`);
      info(`Type ${colors.bold}scli help${colors.reset} for available commands.`);
      break;
  }
}
