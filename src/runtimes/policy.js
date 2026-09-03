// Sunshine Terminal command policy — 3-tier risk engine.
// Tier 1 (safe): read-only introspection → auto-approve, log only.
// Tier 2 (state change): installs, commits, restarts → inline confirm.
// Tier 3 (destructive): data loss / privesc / exfil pipes / DoS shapes →
//   explicit confirm showing command + tier + origin.
// A denylist raises cost; it is not a boundary. The guest-side
// sunshine-exec shim re-checks Tier 3 (defense in depth) and the audit log
// records every verdict.
export const TIERS = {
  SAFE: 1,
  STATE_CHANGE: 2,
  DESTRUCTIVE: 3,
};

export const TIER_NAMES = {
  1: 'Safe',
  2: 'State change',
  3: 'Destructive / high risk',
};

// Normalize for detection: strip quotes/backslashes, collapse whitespace,
// lowercase. Catches `r\m -rf`, `"rm" -rf /`, `R\M` variants.
export function normalizeCommand(cmd) {
  return (cmd || '')
    .replace(/\\/g, '')
    .replace(/['"]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
    .toLowerCase();
}

const T3_PATTERNS = [
  /\brm\s+.*-[a-z]*r[a-z]*f\b/, // rm -rf variants
  /\brm\s+-r\b/,
  /\bmkfs\b/,
  /\bdd\b.*\bof=/, // dd of= (destructive writes)
  /:\(\)\s*\{\s*:\|\:&\s*\}\s*;?\s*:/, // fork bomb
  /\bshutdown\b/, /\bpoweroff\b/, /\breboot\b/, /\bhalt\b/,
  /\bsudo\b/,
  /\bchmod\s+(-r\s+)?777\b/,
  /\bchown\s+-r\b.*\//,
  /\bcurl\b.*\|\s*(sh|bash)\b/, // curl|sh pipes
  /\bwget\b.*\|\s*(sh|bash)\b/,
  /base64\s+(-d|--decode)\b.*\|\s*(sh|bash)\b/,
  /\bnc\b.*\s-l\b/, // listeners
  /\bncat\b.*\s-l\b/,
  /\bsocat\b.*listen\b/,
  /\biptables\b/, /\bnft\b/,
  /\bdrop\s+table\b/, /\bdelete\s+from\b/,
  />\s*\/dev\/sd[a-z]\b/, // raw disk writes
  /\bapt\s+(purge|autoremove)\b/,
  /\bkill\s+-9\s+-1\b/,
  /\bpkill\s+-9\b/,
];

const T2_PATTERNS = [
  /\bapt(-get)?\s+install\b/,
  /\bpip\s+install\b/,
  /\bnpm\s+(install|i)\b/,
  /\bcargo\s+(install|add)\b/,
  /\bgit\s+(commit|push|publish)\b/,
  /\bdocker\s+(run|build|push|rm|rmi|compose)\b/,
  /\bsystemctl\s+(restart|stop|start|enable|disable)\b/,
  /\bservice\s+\w+\s+(restart|stop|start)\b/,
  /\bmv\b/, /\bcp\s+-r\b/,
  /\btar\s+.*-[a-z]*x/, // extraction
  /\bunzip\b/,
  /\buseradd\b/, /\bpasswd\b/,
  /\bssh\b/,
  /\bscp\b/, /\brsync\b/,
  /\bupload\b/, /\bpublish\b/,
];

// Remove double/single-quoted spans for destructive matching, with one
// exception: single-word spans (`"rm"`) are kept, because quoting the
// command word itself is a classic evasion (`"rm" -rf /`). Multi-word
// spans are string literals (`echo "don't rm -rf"`) and are dropped.
export function stripQuotedSpans(s) {
  const keepWord = (_, inner) => (/\s/.test(inner) ? ' ' : inner);
  return String(s || '')
    .replace(/"([^"]*)"/g, keepWord)
    .replace(/'([^']*)'/g, keepWord);
}

// Bodies of $() and `` substitutions — these execute even inside quotes.
export function substitutionBodies(command) {
  const bodies = [];
  for (const m of String(command || '').matchAll(/\$\(([^()]*)\)/g)) bodies.push(m[1]);
  for (const m of String(command || '').matchAll(/`([^`]*)`/g)) bodies.push(m[1]);
  return bodies;
}

export function classifyCommand(command) {
  const norm = normalizeCommand(command);
  if (!norm) return { tier: TIERS.SAFE, reason: 'empty-command' };
  // Tier-3 is tested against the command with string-literal contents
  // removed, so `echo "don't rm -rf"` doesn't false-positive. Substitution
  // bodies execute even inside quotes, so they are tested separately.
  const bare = normalizeCommand(stripQuotedSpans(command || ''));
  const t3Hit = (s) => {
    for (const re of T3_PATTERNS) {
      if (re.test(s)) return re;
    }
    return null;
  };
  const direct = t3Hit(bare);
  if (direct) return { tier: TIERS.DESTRUCTIVE, reason: `matched destructive pattern ${direct.source.slice(0, 40)}` };
  for (const body of substitutionBodies(command || '')) {
    const hit = t3Hit(normalizeCommand(body));
    if (hit) return { tier: TIERS.DESTRUCTIVE, reason: 'destructive pattern in command substitution' };
  }
  // Command substitution / process substitution piping into shells.
  if (/\|\s*(sh|bash|zsh)\b/.test(bare)) {
    return { tier: TIERS.DESTRUCTIVE, reason: 'pipe-to-shell' };
  }
  if (/\$\(.+\)/.test(norm) || /`[^`]+`/.test(norm)) {
    return { tier: TIERS.STATE_CHANGE, reason: 'command-substitution' };
  }
  for (const re of T2_PATTERNS) {
    if (re.test(norm)) return { tier: TIERS.STATE_CHANGE, reason: `matched state-change pattern ${re.source.slice(0, 40)}` };
  }
  return { tier: TIERS.SAFE, reason: 'no-risk-patterns' };
}

// Decide the verdict for a command + origin.
// Returns { tier, tierName, origin, verdict: 'allow'|'confirm'|'confirm-explicit', reason }.
// - T1 → allow (log only). T2 → confirm. T3 → confirm-explicit (wording
//   escalates for agent origin). Nothing auto-denies: the human stays in
//   the loop; denials happen at the prompt, and every verdict is audited.
export function decideExec(command, { origin = 'human' } = {}) {
  const { tier, reason } = classifyCommand(command);
  let verdict = 'allow';
  if (tier === TIERS.STATE_CHANGE) verdict = 'confirm';
  if (tier === TIERS.DESTRUCTIVE) verdict = 'confirm-explicit';
  return { tier, tierName: TIER_NAMES[tier], origin, verdict, reason };
}

// Engine-side gate (no prompting here — prompts live in vm.js).
// Callers pass the verdict obtained from the user; engines deny anything
// Tier 2+ that was not explicitly confirmed. Defense in depth alongside
// the guest-side shim re-check.
export function enforcePolicy(command, { origin = 'human', verdict = null } = {}) {
  const decision = decideExec(command, { origin });
  if (decision.tier >= TIERS.STATE_CHANGE && verdict !== 'confirmed') {
    return { ...decision, allowed: false, denyReason: 'approval-required' };
  }
  return { ...decision, allowed: true, denyReason: null };
}
