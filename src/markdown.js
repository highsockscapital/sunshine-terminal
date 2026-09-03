// Sunshine Terminal Markdown Previewer — native rendering, zero dependencies.
// Block support: headings, fences, quotes, lists (incl. tasks), tables, hr.
// Inline support: bold, italic, inline code, links, images.
// Code highlighting: lightweight tokenizer (keywords/strings/comments/numbers)
// using only Sunshine theme roles (accent/primary/secondary/borderLight/error).
import { colors } from './ui.js';

const R = colors.reset;
const B = colors.bold;
const I = colors.italic;
const A = colors.accent;
const P = colors.primary;
const S = colors.secondary;
const BL = colors.borderLight;

const KEYWORDS = new Set([
  'import', 'from', 'export', 'default', 'const', 'let', 'var', 'function',
  'return', 'if', 'else', 'for', 'while', 'do', 'switch', 'case', 'break',
  'continue', 'new', 'class', 'extends', 'await', 'async', 'try', 'catch',
  'finally', 'throw', 'typeof', 'in', 'of', 'true', 'false', 'null',
  'undefined', 'this', 'def', 'elif', 'with', 'as', 'pass', 'lambda',
  'echo', 'fi', 'then', 'done', 'local',
]);

// Max chars per line fed to inline regex formatting (see renderInline).
export const INLINE_BUDGET = 4000;

function highlightCodeLine(line) {
  // Tokenize: comments (#... or //...), strings, numbers, keywords, plain.
  let out = '';
  let i = 0;
  const n = line.length;
  const pushPlain = (chunk) => {
    out += `${P}${chunk}${R}`;
  };
  while (i < n) {
    const rest = line.slice(i);
    // Line comments
    if (rest.startsWith('//') || rest.startsWith('#')) {
      out += `${S}${I}${rest}${R}`;
      break;
    }
    const ch = line[i];
    // Strings
    if (ch === '"' || ch === "'" || ch === '`') {
      let j = i + 1;
      while (j < n && line[j] !== ch) {
        if (line[j] === '\\') j++;
        j++;
      }
      j = Math.min(j + 1, n);
      out += `${A}${line.slice(i, j)}${R}`;
      i = j;
      continue;
    }
    // Numbers
    const numMatch = /^[0-9]+(\.[0-9]+)?/.exec(rest);
    if (numMatch) {
      out += `${A}${B}${numMatch[0]}${R}`;
      i += numMatch[0].length;
      continue;
    }
    // Words
    const wordMatch = /^[A-Za-z_$][A-Za-z0-9_$]*/.exec(rest);
    if (wordMatch) {
      const w = wordMatch[0];
      if (KEYWORDS.has(w)) out += `${A}${B}${w}${R}`;
      else if (/^[A-Z][A-Za-z0-9_]*$/.test(w)) out += `${P}${B}${w}${R}`;
      else pushPlain(w);
      i += w.length;
      continue;
    }
    pushPlain(ch);
    i++;
  }
  return out;
}

function renderInline(text) {
  // Length guard: lazy-quantifier regexes backtrack catastrophically on
  // huge single lines (minified JS/JSON, log dumps). Past the budget,
  // render plain text with no inline formatting.
  if (text.length > INLINE_BUDGET) {
    return `${P}${text.slice(0, INLINE_BUDGET)}${S}…[line truncated]${R}`;
  }
  // Order: inline code first (protect), then images/links, bold, italic.
  const codeSpans = [];
  let t = text.replace(/`([^`]+?)`/g, (_, code) => {
    codeSpans.push(`${A}\`${code}\`${R}`);
    return `\u0000${codeSpans.length - 1}\u0000`;
  });
  t = t.replace(/!\[([^\]]*?)\]\(([^)]+?)\)/g, (_, alt, src) => `${A}🖼 ${alt || 'image'}${R} ${S}(${src})${R}`);
  t = t.replace(/\[([^\]]+?)\]\(([^)]+?)\)/g, (_, label, url) => `${A}${B}${label}${R} ${S}(${url})${R}`);
  t = t.replace(/\*\*([^*]+?)\*\*|__([^_]+?)__/g, (_, a, b) => `${P}${B}${a ?? b}${R}`);
  t = t.replace(/(^|[^*\w])\*([^*\n]+?)\*(?![*\w])|(^|[^_\w])_([^_\n]+?)_(?![_\w])/g, (m, p1, a, p2, b) => `${(p1 ?? p2) || ''}${S}${I}${a ?? b}${R}`);
  t = t.replace(/\u0000(\d+)\u0000/g, (_, idx) => codeSpans[Number(idx)]);
  return t;
}

function isTableDelimiter(line) {
  return /^\s*\|?(\s*:?-+:?\s*\|)+\s*$/.test(line);
}

function renderTableRow(line, isHeader) {
  const cells = line.trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim());
  const rendered = cells.map((c) => {
    const inner = renderInline(c);
    return isHeader ? `${A}${B}${inner}${R}` : `${P}${inner}${R}`;
  });
  return `${BL}│${R} ${rendered.join(` ${BL}│${R} `)} ${BL}│${R}`;
}

function tableRule(widths) {
  return `${BL}├${widths.map((w) => '─'.repeat(Math.min(w + 2, 30))).join('┼')}┤${R}`;
}

export function renderMarkdown(text, { width = process.stdout.columns || 80 } = {}) {
  const lines = text.replace(/\r\n/g, '\n').split('\n');
  const out = [];
  let i = 0;
  let inFence = false;
  let fenceLang = '';
  let fenceBuf = [];

  const flushFence = () => {
    const label = fenceLang ? ` ${fenceLang} ` : ' code ';
    out.push(`${BL}┌─${'─'.repeat(Math.min(label.length, 24))}${R} ${A}${B}${fenceLang || 'code'}${R}`);
    fenceBuf.forEach((codeLine, idx) => {
      const num = `${S}${String(idx + 1).padStart(3)}${R}`;
      out.push(`${num} ${BL}│${R} ${highlightCodeLine(codeLine)}`);
    });
    out.push(`${BL}└${'─'.repeat(Math.min(width - 1, 40))}${R}`);
    fenceBuf = [];
  };

  while (i < lines.length) {
    const line = lines[i];

    // Fenced code blocks
    const fenceMatch = /^```(\w*)\s*$/.exec(line);
    if (fenceMatch) {
      if (!inFence) {
        inFence = true;
        fenceLang = fenceMatch[1] || '';
      } else {
        inFence = false;
        flushFence();
        fenceLang = '';
      }
      i++;
      continue;
    }
    if (inFence) {
      fenceBuf.push(line);
      i++;
      continue;
    }

    // Headings
    const hMatch = /^(#{1,6})\s+(.*)$/.exec(line);
    if (hMatch) {
      const level = hMatch[1].length;
      const marker = level === 1 ? '█' : level === 2 ? '▓' : level === 3 ? '▒' : '░';
      out.push(`${A}${B}${marker} ${renderInline(hMatch[2])}${R}`);
      if (level <= 2) out.push(`${BL}${'─'.repeat(Math.min(width, 60))}${R}`);
      i++;
      continue;
    }

    // Horizontal rule
    if (/^\s*(---|\*\*\*|___)\s*$/.test(line)) {
      out.push(`${BL}${'─'.repeat(Math.min(width, 60))}${R}`);
      i++;
      continue;
    }

    // Blockquote
    if (/^\s*>/.test(line)) {
      const quoteLines = [];
      while (i < lines.length && /^\s*>/.test(lines[i])) {
        quoteLines.push(lines[i].replace(/^\s*> ?/, ''));
        i++;
      }
      quoteLines.forEach((q) => {
        out.push(`${BL}▌${R} ${S}${I}${renderInline(q) || ' '}${R}`);
      });
      continue;
    }

    // Tables
    if (line.includes('|') && i + 1 < lines.length && isTableDelimiter(lines[i + 1])) {
      const headerCells = line.trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim().length);
      out.push(renderTableRow(line, true));
      out.push(tableRule(headerCells.length ? headerCells : [10]));
      i += 2;
      while (i < lines.length && lines[i].includes('|') && lines[i].trim() !== '') {
        out.push(renderTableRow(lines[i], false));
        i++;
      }
      continue;
    }

    // Lists (bullets, ordered, tasks) with nesting via 2-space indent
    const listMatch = /^(\s*)([-*+]|\d+[.)])\s+(\[[ xX]\]\s+)?(.*)$/.exec(line);
    if (listMatch) {
      const indent = listMatch[1].length;
      const bullet = listMatch[2];
      const task = listMatch[3];
      const content = listMatch[4];
      const pad = ' '.repeat(Math.min(indent, 8));
      const ordered = /^\d+[.)]$/.test(bullet);
      let marker;
      if (task) {
        const done = /\[x\]/i.test(task);
        marker = done ? `${A}${B}[✓]${R}` : `${S}[ ]${R}`;
      } else if (ordered) {
        marker = `${A}${B}${bullet}${R}`;
      } else {
        marker = `${A}•${R}`;
      }
      out.push(`${pad}${marker} ${renderInline(content)}`);
      i++;
      continue;
    }

    // Blank line preserves paragraph spacing
    if (/^\s*$/.test(line)) {
      out.push('');
      i++;
      continue;
    }

    // Indented code (4 spaces) outside fences
    if (/^ {4}\S/.test(line)) {
      out.push(`${S}  ${line.trim()}${R}`);
      i++;
      continue;
    }

    // Default paragraph
    out.push(renderInline(line));
    i++;
  }

  if (inFence) flushFence(); // unclosed fence: render what we have
  return out.join('\n');
}

export function isMarkdownFile(filePath) {
  return /\.(md|markdown|mdown|mkd)$/i.test(filePath || '');
}
