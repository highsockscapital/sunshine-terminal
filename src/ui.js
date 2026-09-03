// SunshineCLI UI & Parisian Styling
export const colors = {
  reset: '\x1b[0m',
  bold: '\x1b[1m',
  dim: '\x1b[2m',
  italic: '\x1b[3m',
  pink: '\x1b[38;5:213m',
  magenta: '\x1b[35m',
  cyan: '\x1b[36m',
  green: '\x1b[32m',
  yellow: '\x1b[33m',
  red: '\x1b[31m',
  white: '\x1b[37m',
  bgPink: '\x1b[48;5;213m'
};

export function banner() {
  return `
${colors.pink}${colors.bold}  ╔═══════════════════════════════════════════════════════╗
  ║                 ✨ SunshineCLI v1.0 ✨                ║
  ║      Dual-Runtime Workspace & Terminal Manager        ║
  ║        [ Android Bionic ⇄ Android 16 AVF VM ]         ║
  ╚═══════════════════════════════════════════════════════╝${colors.reset}
`;
}

export function promptSymbol() {
  return `${colors.pink}${colors.bold}sunshine ❯${colors.reset} `;
}

export function info(msg) {
  console.log(`${colors.cyan}ℹ [Sunshine]${colors.reset} ${msg}`);
}

export function success(msg) {
  console.log(`${colors.green}✓ [Magnifique]${colors.reset} ${msg}`);
}

export function warning(msg) {
  console.log(`${colors.yellow}⚠ [Attention]${colors.reset} ${msg}`);
}

export function error(msg) {
  console.log(`${colors.red}✕ [Mon Dieu]${colors.reset} ${msg}`);
}
