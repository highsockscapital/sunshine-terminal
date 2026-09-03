// SunshineCLI Main Controller
import { banner, promptSymbol, info, success, warning, error, colors } from './ui.js';
import { listWorkspace, readFileContent, writeFileContent } from './workspace.js';
import { checkAVFStatus, executeAVF } from './runtimes/vm.js';

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
      info(`Bonjour! SunshineCLI is fully operational and ready.`);
      await checkAVFStatus();
      await listWorkspace();
      break;

    case 'workspace':
    case 'ws':
      await listWorkspace();
      break;

    case 'read':
      if (!subArg1) {
        error(`Please specify a file path to read. Usage: scli read <file>`);
        break;
      }
      await readFileContent(subArg1);
      break;

    case 'write':
      if (!subArg1 || !subArg2) {
        error(`Usage: scli write <file> "<content>"`);
        break;
      }
      await writeFileContent(subArg1, subArg2);
      break;

    case 'run':
    case 'exec':
      if (!subArg1) {
        error(`Please specify a command to run in AVF Linux VM. Usage: scli run "<command>"`);
        break;
      }
      await executeAVF(args.slice(1).join(' '));
      break;

    case 'vm':
      if (!subArg1) {
        await checkAVFStatus();
      } else if (subArg1 === 'status') {
        await checkAVFStatus();
      } else {
        const vmCommand = args.slice(1).join(' ');
        await executeAVF(vmCommand);
      }
      break;

    case 'bionic':
      error(`Bionic runtime operations are now handled directly via Termux. Use your Termux shell for native operations.`);
      info(`Tip: ${colors.pink}sunshine status${colors.reset} shows AVF VM status, or use your Termux prompt for bionic commands.`);
      break;

    case 'help':
    case '--help':
    case '-h':
      console.log(banner());
      console.log(`${colors.bold}Available Commands:${colors.reset}`);
      console.log(`  ${colors.pink}scli status${colors.reset}                  - Show dual runtime status & workspace summary`);
      console.log(`  ${colors.pink}scli workspace${colors.reset}             - List files in current workspace`);
      console.log(`  ${colors.pink}scli read <file>${colors.reset}           - Read workspace file content`);
      console.log(`  ${colors.pink}scli write <file> <text>${colors.reset}   - Write text to workspace file`);
      console.log(`  ${colors.pink}scli run <cmd>${colors.reset}             - Execute command in Bionic (Termux) runtime`);
      console.log(`  ${colors.pink}scli vm [cmd|status]${colors.reset}       - Inspect or run command in Android 16 AVF Linux VM`);
      console.log(`  ${colors.pink}scli help${colors.reset}                  - Display this help message\n`);
      break;

    default:
      console.log(banner());
      error(`Unknown command: "${command}"`);
      info(`Type ${colors.bold}scli help${colors.reset} for available commands.`);
      break;
  }
}
