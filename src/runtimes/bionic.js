// SunshineCLI Local Bionic (Termux) Runtime Execution
import { spawn } from 'child_process';
import { info, success, error, colors } from '../ui.js';

export async function executeBionic(command, args = []) {
  info(`Executing on Android Native Bionic (Termux): ${colors.bold}${command} ${args.join(' ')}${colors.reset}`);
  
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      shell: true,
      stdio: 'inherit',
      env: process.env
    });

    child.on('close', (code) => {
      if (code === 0) {
        success(`Bionic command completed with exit code 0`);
        resolve(0);
      } else {
        error(`Bionic command exited with code ${code}`);
        resolve(code);
      }
    });

    child.on('error', (err) => {
      error(`Failed to spawn Bionic process: ${err.message}`);
      reject(err);
    });
  });
}
