/**
 * Port of the text helpers in `app/sharedLogic/.../Ingest.kt`.
 *
 * The rules there are the reason these exist: a stored `body_text` is
 * frequently raw MIME truncated at 10 kB, so the readable receipt lives in
 * `body_html`, quoted-printable encoded, behind a stylesheet. Anything that
 * reads a mail (regex, Jev, an embedding) must read it through here or it
 * measures selectors instead of content.
 */

/** Same currency-anchored money regex as `Ingest.kt`'s `MONEY`. */
export const MONEY = /(?:U\$S|US\$|USD|ARS|\$)\s*[0-9][0-9.,]*/i;

/** Digits with no currency marker: the wider net, kept to measure what the
 * narrow one discards (see the plan, §2). */
export const ANY_DIGIT = /[0-9]/;

export function hasAmount(text: string): boolean {
  return MONEY.test(text);
}

const TAG = /<[^>]*>/g;
const BLOCK = /<(style|script)\b[^>]*>[\s\S]*?<\/\1\s*>/gi;
// A body stored truncated at 10 kB often ends *inside* a stylesheet, so the
// closing tag `BLOCK` needs never arrives and the whole CSS tail survives as
// text. `Ingest.kt` has the same gap.
const OPEN_BLOCK = /<(style|script)\b[^>]*>[\s\S]*$/i;
const WHITESPACE = /[\s\u00a0]+/g;
const ENTITIES: Record<string, string> = {
  '&nbsp;': ' ', '&amp;': '&', '&lt;': '<', '&gt;': '>', '&quot;': '"',
  '&#39;': "'", '&aacute;': 'á', '&eacute;': 'é', '&iacute;': 'í',
  '&oacute;': 'ó', '&uacute;': 'ú', '&ntilde;': 'ñ',
};

/** Quoted-printable: `=XX` bytes and `=`-at-end-of-line soft breaks. */
export function decodeQuotedPrintable(raw: string): string {
  if (!raw.includes('=')) return raw;
  const bytes: number[] = [];
  let i = 0;
  while (i < raw.length) {
    const ch = raw[i]!;
    if (ch === '=' && i + 1 < raw.length) {
      const next = raw[i + 1]!;
      // A literal '=' is always encoded as "=3D", so a bare one before
      // whitespace is a soft line break — eating more than one would glue
      // "Comercio=\r\n COTO" into a single word.
      if (/\s/.test(next)) {
        i += next === '\r' && raw[i + 2] === '\n' ? 3 : 2;
        continue;
      }
      const hex = raw.slice(i + 1, i + 3);
      if (/^[0-9a-fA-F]{2}$/.test(hex)) {
        bytes.push(parseInt(hex, 16));
        i += 3;
        continue;
      }
    }
    // Non-encoded characters go through as UTF-8 so the decode below is one
    // pass over real bytes rather than a mix of code units and bytes.
    for (const b of new TextEncoder().encode(ch)) bytes.push(b);
    i += 1;
  }
  return new TextDecoder('utf-8').decode(new Uint8Array(bytes));
}

/** RFC 2047 encoded words in a header (`=?utf-8?B?...?=`). */
export function decodeMimeHeader(raw: string): string {
  if (!raw.includes('=?')) return raw;
  let out = '';
  let rest = raw;
  for (;;) {
    const start = rest.indexOf('=?');
    if (start < 0) break;
    const parts = rest.slice(start + 2).split('?');
    if (parts.length < 4) break;
    const [charset, encoding, payload] = [parts[0]!, parts[1]!, parts[2]!];
    const end = rest.indexOf('?=', start + 2 + charset.length + encoding.length + payload.length);
    if (end < 0) break;
    out += rest.slice(0, start);
    out += encoding.toUpperCase() === 'B'
      ? new TextDecoder('utf-8').decode(Buffer.from(payload, 'base64'))
      : encoding.toUpperCase() === 'Q'
        ? decodeQuotedPrintable(payload.replaceAll('_', ' '))
        : payload;
    rest = rest.slice(end + 2);
    // Adjacent encoded words are separated by whitespace that is not part of
    // the text ("=?..?= =?..?=" is one word split in two).
    if (rest.startsWith(' ') && rest.trimStart().startsWith('=?')) rest = rest.trimStart();
  }
  return (out + rest).trim();
}

/**
 * A stored `body_text` is sometimes the whole MIME entity, boundaries and
 * headers included: without this, the embedding measures
 * `Content-Type: multipart/related` instead of the receipt. Picks the richest
 * part (html over plain) and decodes its transfer encoding.
 */
export function unwrapMime(raw: string): string {
  // The declared boundary when the stored text includes the entity headers,
  // and otherwise the first `--token` line: rows cut at 10 kB frequently
  // start *inside* the multipart, with the declaration already gone.
  const boundary = raw.match(/boundary="?([^";\r\n]+)"?/i)?.[1]
    // A `--token` line immediately followed by a MIME header is a boundary;
    // requiring that next line is what keeps a line of dashes in a plain-text
    // signature from being mistaken for one.
    ?? raw.match(/^--(\S{4,})[ \t]*\r?\n(?=Content-)/im)?.[1];
  if (!boundary) return raw;
  const parts = raw.split(`--${boundary}`).slice(1);
  let best = '';
  let bestRank = -1;
  for (const part of parts) {
    // Headers end at the first blank line; nested multiparts get unwrapped
    // again, which is how `related` inside `alternative` reaches the html.
    const split = part.search(/\r?\n\r?\n/);
    if (split < 0) continue;
    const headers = part.slice(0, split).toLowerCase();
    let body = part.slice(split).trim();
    if (headers.includes('multipart/')) {
      const inner = unwrapMime(part);
      if (inner !== part && inner.length > best.length) {
        best = inner;
        bestRank = 2;
      }
      continue;
    }
    const isHtml = headers.includes('text/html');
    const isPlain = headers.includes('text/plain');
    if (!isHtml && !isPlain) continue;
    if (headers.includes('base64')) {
      body = new TextDecoder('utf-8').decode(Buffer.from(body.replace(/\s+/g, ''), 'base64'));
    }
    const rank = isHtml ? 2 : 1;
    if (rank > bestRank) {
      best = body;
      bestRank = rank;
    }
  }
  return best || raw;
}

/** Email body → one readable line. */
export function emailPlainText(raw: string): string {
  const decoded = decodeQuotedPrintable(unwrapMime(raw));
  const noBlocks = decoded.replace(BLOCK, ' ').replace(OPEN_BLOCK, ' ');
  const noTags = noBlocks.replace(TAG, ' ');
  const unescaped = Object.entries(ENTITIES).reduce(
    (acc, [k, v]) => acc.replaceAll(k, v),
    noTags,
  );
  return unescaped.replace(WHITESPACE, ' ').trim();
}

export type Kind = 'notification' | 'email';

export interface Message {
  kind: Kind;
  id: string;
  /** Package name or sender address. */
  origin: string;
  /** App label when known, else the raw origin. */
  originLabel: string;
  title: string;
  body: string;
  date: number;
  category: string | null;
}

/**
 * The canonical string that gets classified and embedded. Stored alongside
 * the vector so a bad result is always traceable to the text that produced it
 * rather than to a body that has to be re-derived to be inspected.
 */
export function canonicalText(m: Message, maxBody = 1500): string {
  const body = m.body.length > maxBody ? `${m.body.slice(0, maxBody)}…` : m.body;
  return [m.originLabel, m.title, body].filter(Boolean).join(' | ');
}
