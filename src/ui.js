// Sunshine Terminal UI — Sunshine Design System (sunset orange, no pink)
// Source of truth: ./theme.js + ./tokens.json
// CLI approximations: 12dp canvas -> bordered box, 8dp badge -> [ label ],
// 16dp modal -> padded panel, 24dp FAB -> accent prompt pill.
// Elevation is flat (0dp terminal, 1dp sidebar via divider rule only).
// Type: Inter labels (app), Monospace 13sp/18sp assumed for terminal/code.
import { tokens, fg, bg } from './theme.js';

const noColor =
  process.env.NO_COLOR !== undefined ||
  process.env.FORCE_COLOR === '0' ||
  process.env.TERM === 'dumb';
const style = (code) => (noColor ? '' : code);

export const colors = {
  reset: style('\x1b[0m'),
  bold: style('\x1b[1m'),
  dim: style('\x1b[2m'),
  italic: style('\x1b[3m'),
  // Semantic roles from tokens
  accent: fg(tokens.primaryAccent),
  accentBg: bg(tokens.primaryAccent),
  onAccent: fg(tokens.onPrimaryAccent),
  primary: fg(tokens.textPrimary),
  secondary: fg(tokens.textSecondary),
  border: fg(tokens.strokeBorder),
  borderLight: fg(tokens.strokeBorderLight),
  errorTok: fg(tokens.error),
  surfaceVariantBg: bg(tokens.surfaceVariant),
};

export function banner() {
  const a = colors.accent + colors.bold;
  const p = colors.primary;
  const s = colors.secondary;
  const r = colors.reset;
  return `
${a}  ╔═══════════════════════════════════════════════════════╗
  ║              ☀ Sunshine Terminal v1.0 ☀             ║${p}
  ║         AVF Native Debian Guest Workspace           ║${s}
  ║           [ Android 16 pVM · sunshine-exec ]        ║${a}
  ╚═══════════════════════════════════════════════════════╝${r}
`;
}

export function promptSymbol() {
  return `${colors.accent}${colors.bold}sunshine ❯${colors.reset} `;
}

// Badge helper: 8dp inline badge approximation -> [ label ] in accent.
export function badge(label) {
  return `${colors.accent}[ ${label} ]${colors.reset}`;
}

export function info(msg) {
  console.log(`${colors.secondary}ℹ [Sunshine]${colors.reset} ${colors.primary}${msg}${colors.reset}`);
}

export function success(msg) {
  console.log(`${colors.accent}✓ [Magnifique]${colors.reset} ${colors.primary}${msg}${colors.reset}`);
}

export function warning(msg) {
  console.log(`${colors.accent}⚠ [Attention]${colors.reset} ${colors.primary}${msg}${colors.reset}`);
}

export function error(msg) {
  console.log(`${colors.errorTok}✕ [Mon Dieu]${colors.reset} ${colors.primary}${msg}${colors.reset}`);
}
