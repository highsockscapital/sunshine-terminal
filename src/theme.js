// Sunshine Design System — single source of truth (CLI + Android app)
// Tokens: windowBackground, textPrimary, textSecondary, cardSurface,
// surfaceVariant, primaryAccent, onPrimaryAccent, strokeBorder,
// strokeBorderLight, inputBorder, error. Shape 8/12/16/20/24dp.
// Elevation 0/1/4/8dp. Type: Inter labels + role scale, Monospace
// 13sp/18sp terminal/code.

export const tokens = {
  windowBackground: '#F6F3E7',
  textPrimary: '#161610',
  textSecondary: '#5C5C55',
  cardSurface: '#FFFFFF',
  surfaceVariant: '#F5F5E6',
  primaryAccent: '#FF9E43',
  onPrimaryAccent: '#161610',
  strokeBorder: '#161610',
  strokeBorderLight: '#E0E0D6',
  inputBorder: '#161616',
  error: '#C62828',
};

export const shape = {
  badge: 8, // inline badges
  canvas: 12, // terminal canvas & code blocks
  modal: 16, // message bubbles & system action cards
  sheet: 20, // bottom sheets, model selector drawer
  fab: 24, // floating action controls
};

export const elevation = {
  flat: 0, // message stream / embedded containers
  bubble: 1, // message bubbles, input field
  sidebar: 1, // floating file manager sidebar
  toast: 4, // system toasts, floating tools overlay
  sheet: 8, // bottom sheets, settings modals
};

export const typography = {
  label: 'Inter',
  mono: 'Monospace',
  screenTitle: { sizeSp: 20, lineHeightSp: 26, letterSpacingSp: -0.2, weight: 700 },
  sectionHeader: { sizeSp: 12, lineHeightSp: 16, letterSpacingSp: 0.8, weight: 600, uppercase: true },
  messageText: { sizeSp: 14, lineHeightSp: 20, letterSpacingSp: 0.25, weight: 400 },
  monoSizeSp: 13,
  monoLineHeightSp: 18,
  metadata: { sizeSp: 11, lineHeightSp: 14, letterSpacingSp: 0.4, weight: 500 },
  button: { sizeSp: 14, lineHeightSp: 20, letterSpacingSp: 0.5, weight: 600 },
};

export function hexToRgb(hex) {
  const h = hex.replace('#', '');
  const v = h.length === 3 ? h.split('').map((c) => c + c).join('') : h;
  const n = parseInt(v, 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

function envNoColor() {
  return (
    process.env.NO_COLOR !== undefined ||
    process.env.FORCE_COLOR === '0' ||
    process.env.TERM === 'dumb'
  );
}

function supportsTruecolor() {
  if (envNoColor()) return false;
  const ct = (process.env.COLORTERM || '').toLowerCase();
  if (ct.includes('truecolor') || ct.includes('24bit')) return true;
  // Termux / modern terminals handle truecolor; fall back otherwise.
  if (process.env.TERMUX_VERSION !== undefined) return true;
  if ((process.env.TERM || '').includes('256')) return false;
  return true;
}

// 256-color fallback approx for primaryAccent #FF9E43 -> xterm 208.
const FALLBACK_256 = {
  '#FF9E43': '208',
  '#161610': '16',
  '#5C5C55': '59',
  '#F6F3E7': '230',
  '#FFFFFF': '15',
  '#F5F5E6': '230',
  '#E0E0D6': '188',
  '#C62828': '160',
};

export function fg(hex) {
  if (envNoColor()) return '';
  if (supportsTruecolor()) {
    const [r, g, b] = hexToRgb(hex);
    return `\x1b[38;2;${r};${g};${b}m`;
  }
  const code = FALLBACK_256[hex] || '7';
  return `\x1b[38;5;${code}m`;
}

export function bg(hex) {
  if (envNoColor()) return '';
  if (supportsTruecolor()) {
    const [r, g, b] = hexToRgb(hex);
    return `\x1b[48;2;${r};${g};${b}m`;
  }
  const code = FALLBACK_256[hex] || '0';
  return `\x1b[48;5;${code}m`;
}
