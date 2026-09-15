/**
 * Document import: the app uploads a receipt image or (multi-page) bank
 * statement PDF; Gemini extracts candidate transactions; the app reviews
 * and creates them locally. Nothing is written to the ledger here — the
 * original file is stored in R2 (keyed by content hash) for provenance.
 */

export interface ImportEnv {
  AUTH_DB: D1Database;
  DOCS: R2Bucket;
  APP_SLUG: string;
  ACCOUNT_ID: string;
  AI_GATEWAY: string;
  /** Comma-separated; tried in order when the pool answers 503/429. */
  GEMINI_MODELS: string;
  GEMINI_API_KEY: string;
}

export interface AuthedUser {
  id: string;
  turso_db_name: string;
  turso_db_hostname: string;
}

const MAX_BYTES = 15 * 1024 * 1024;
const ALLOWED_TYPES = new Set([
  'application/pdf',
  'image/jpeg',
  'image/png',
  'image/webp',
  'image/heic',
  'image/heif',
]);

/**
 * Cookie-based auth, mirroring the auth worker's requireAuth: the app sends
 * its `auth_finance` refresh cookie; we hash it and resolve the session in
 * the shared D1. Revocation and expiry work exactly like on the auth worker.
 */
export async function authenticate(request: Request, env: ImportEnv): Promise<AuthedUser | Response> {
  const cookieHeader = request.headers.get('cookie') ?? '';
  const name = `auth_${env.APP_SLUG}=`;
  const cookie = cookieHeader
    .split(';')
    .map((c) => c.trim())
    .find((c) => c.startsWith(name))
    ?.slice(name.length);
  if (!cookie) return json({ error: 'missing session cookie' }, 401);

  const hash = await sha256HexOf(new TextEncoder().encode(cookie));
  const row = await env.AUTH_DB.prepare(
    `SELECT rt.user_id, rt.expires_at, u.turso_db_name, u.turso_db_hostname, c.revoked_at
     FROM refresh_tokens rt
     JOIN users u ON u.id = rt.user_id
     JOIN credentials c ON c.id = rt.credential_id
     WHERE rt.hash = ? AND u.app_slug = ?`
  )
    .bind(hash, env.APP_SLUG)
    .first<{
      user_id: string;
      expires_at: number;
      turso_db_name: string;
      turso_db_hostname: string;
      revoked_at: number | null;
    }>();

  if (!row || row.expires_at < Math.floor(Date.now() / 1000) || row.revoked_at !== null) {
    return json({ error: 'invalid or expired session' }, 401);
  }
  return { id: row.user_id, turso_db_name: row.turso_db_name, turso_db_hostname: row.turso_db_hostname };
}

interface CategoryAccount {
  id: string;
  name: string;
  path: string;
  type: 'expense' | 'income';
}

/** Expense/income leaves of the user's account tree, with colon-joined paths. */
async function loadCategories(
  queryUserDb: (sql: string) => Promise<Array<Record<string, unknown>>>
): Promise<CategoryAccount[]> {
  const rows = await queryUserDb('select id, name, parent_id, type from accounts');
  const byId = new Map<string, { id: string; name: string; parent_id: string | null; type: string }>();
  const hasChildren = new Set<string>();
  for (const r of rows) {
    const id = String(r.id);
    byId.set(id, {
      id,
      name: String(r.name),
      parent_id: r.parent_id == null ? null : String(r.parent_id),
      type: String(r.type),
    });
    if (r.parent_id != null) hasChildren.add(String(r.parent_id));
  }
  const pathOf = (id: string): string => {
    const acc = byId.get(id);
    if (!acc) return '';
    return acc.parent_id ? `${pathOf(acc.parent_id)}:${acc.name}` : acc.name;
  };
  const out: CategoryAccount[] = [];
  for (const acc of byId.values()) {
    if (acc.type !== 'expense' && acc.type !== 'income') continue;
    if (hasChildren.has(acc.id)) continue; // postings live on leaves
    out.push({ id: acc.id, name: acc.name, path: pathOf(acc.id), type: acc.type });
  }
  return out;
}

/** Gemini structured-output schema: amounts as decimal strings, parsed here. */
const RESPONSE_SCHEMA = {
  type: 'OBJECT',
  properties: {
    transactions: {
      type: 'ARRAY',
      items: {
        type: 'OBJECT',
        properties: {
          date: { type: 'STRING', description: 'ISO date YYYY-MM-DD of the transaction' },
          payee: { type: 'STRING', description: 'Merchant or counterparty name, cleaned up' },
          note: { type: 'STRING', nullable: true, description: 'Extra detail worth keeping, else null' },
          amount: { type: 'STRING', description: "Positive decimal amount, '.' as decimal separator, e.g. '1234.56'" },
          commodity: { type: 'STRING', description: "Currency code. 'ARS' unless the document explicitly shows another currency (e.g. USD)" },
          direction: { type: 'STRING', enum: ['expense', 'income'], description: 'expense = money leaves the user, income = money comes in' },
          category: { type: 'STRING', nullable: true, description: 'Best-matching category path from the provided list, verbatim, or null' },
        },
        required: ['date', 'payee', 'amount', 'commodity', 'direction'],
      },
    },
  },
  required: ['transactions'],
} as const;

function prompt(categories: CategoryAccount[]): string {
  const expense = categories.filter((c) => c.type === 'expense').map((c) => c.path);
  const income = categories.filter((c) => c.type === 'income').map((c) => c.path);
  return [
    'You extract financial transactions from a document (receipt, invoice, or bank/card statement, possibly multi-page).',
    'Return every distinct transaction you can see. For bank or card statements, emit one entry per statement row;',
    'ignore running balance columns, subtotals, opening/closing balances and summary rows.',
    'Use debit/credit columns or signs to decide direction: debits/charges are "expense", credits/deposits are "income".',
    'Amounts are always positive decimals. Assume currency ARS unless the document explicitly states another currency for that amount.',
    'Dates: use the document\'s dates in YYYY-MM-DD. If the year is missing, infer it from context (statement period or today).',
    'Payee: a short, human-readable merchant/counterparty name (strip codes, reference numbers and legal suffixes).',
    expense.length > 0
      ? `For "expense" entries, pick the best matching category from this list (verbatim path) or null: ${expense.join(' | ')}`
      : '',
    income.length > 0
      ? `For "income" entries, pick the best matching category from this list (verbatim path) or null: ${income.join(' | ')}`
      : '',
    'If the document contains no transactions, return an empty list.',
  ]
    .filter(Boolean)
    .join('\n');
}

interface GeminiCandidateTx {
  date: string;
  payee: string;
  note?: string | null;
  amount: string;
  commodity: string;
  direction: 'expense' | 'income';
  category?: string | null;
}

/**
 * generateContent through the account's AI Gateway when it exists, falling
 * back to the Google API directly (same request shape, same key header).
 */
async function callGemini(env: ImportEnv, mimeType: string, base64: string, promptText: string): Promise<GeminiCandidateTx[]> {
  const body = JSON.stringify({
    contents: [
      {
        parts: [
          { inline_data: { mime_type: mimeType, data: base64 } },
          { text: promptText },
        ],
      },
    ],
    generationConfig: {
      responseMimeType: 'application/json',
      responseSchema: RESPONSE_SCHEMA,
      temperature: 0.1,
    },
  });
  const headers = { 'x-goog-api-key': env.GEMINI_API_KEY, 'content-type': 'application/json' };

  // Through the account's AI Gateway when one exists (logs, caching, limits);
  // its own errors (missing/unauthorized gateway) fall back to Google directly
  // instead of failing an import the user already paid an upload for.
  let viaGateway = true;
  const send = async (model: string): Promise<Response> => {
    const path = `v1beta/models/${model}:generateContent`;
    const direct = () =>
      fetch(`https://generativelanguage.googleapis.com/${path}`, { method: 'POST', headers, body });
    if (!viaGateway) return direct();
    const res = await fetch(
      `https://gateway.ai.cloudflare.com/v1/${env.ACCOUNT_ID}/${env.AI_GATEWAY}/google-ai-studio/${path}`,
      { method: 'POST', headers, body }
    );
    if (res.ok || !(await res.clone().text()).includes('AiGatewayError')) return res;
    viaGateway = false;
    return direct();
  };

  // 503 = "high demand" on a shared model pool. It is common and can last
  // minutes on one model while a sibling answers in seconds, so the fallbacks
  // are other models rather than blind retries of the same one.
  const models = env.GEMINI_MODELS.split(',').map((m) => m.trim()).filter(Boolean);
  let res: Response | null = null;
  for (const model of models) {
    res = await send(model);
    if (res.status !== 503 && res.status !== 429) break;
    console.log(`gemini: ${model} unavailable (${res.status}), trying next model`);
  }
  if (!res) throw new Error('gemini: no model configured');
  if (!res.ok) throw new Error(`gemini: ${res.status} ${await res.text()}`);

  const data = (await res.json()) as {
    candidates?: Array<{ content?: { parts?: Array<{ text?: string }> } }>;
  };
  const text = data.candidates?.[0]?.content?.parts?.map((p) => p.text ?? '').join('') ?? '';
  if (!text) throw new Error('gemini: empty response');
  const parsed = JSON.parse(text) as { transactions?: GeminiCandidateTx[] };
  return parsed.transactions ?? [];
}

/** "1234.56" → 123456 minor units; null when unparseable. */
function toMinor(amount: string): number | null {
  const m = /^\s*(\d+)(?:[.,](\d{1,2}))?\s*$/.exec(amount.replace(/[\s,](?=\d{3}\b)/g, ''));
  if (!m) return null;
  const frac = (m[2] ?? '').padEnd(2, '0');
  const minor = Number(m[1]) * 100 + Number(frac);
  return Number.isSafeInteger(minor) ? minor : null;
}

/** "2024-05-03" → epoch ms at noon UTC (avoids TZ date flips); null if invalid. */
function toEpochMs(date: string): number | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date.trim());
  if (!m) return null;
  const ms = Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]), 12, 0, 0);
  return Number.isFinite(ms) ? ms : null;
}

export async function sha256HexOf(bytes: Uint8Array | ArrayBuffer): Promise<string> {
  const buf = await crypto.subtle.digest('SHA-256', bytes as BufferSource);
  return Array.from(new Uint8Array(buf), (b) => b.toString(16).padStart(2, '0')).join('');
}

function base64Of(bytes: Uint8Array): string {
  let binary = '';
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

export function json(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

/**
 * POST /import/analyze — raw file body + Content-Type. Stores the file in R2
 * at {userId}/{sha256} and returns candidate transactions for review.
 */
export async function handleAnalyze(
  request: Request,
  env: ImportEnv,
  user: AuthedUser,
  queryUserDb: (sql: string) => Promise<Array<Record<string, unknown>>>
): Promise<Response> {
  const mimeType = ((request.headers.get('content-type') ?? '').split(';')[0] ?? '').trim().toLowerCase();
  if (!ALLOWED_TYPES.has(mimeType)) {
    return json({ error: `unsupported content type: ${mimeType || '(none)'}` }, 415);
  }
  const bytes = new Uint8Array(await request.arrayBuffer());
  if (bytes.length === 0) return json({ error: 'empty body' }, 400);
  if (bytes.length > MAX_BYTES) return json({ error: 'file too large (max 15 MB)' }, 413);

  const docId = await sha256HexOf(bytes);
  const key = `${user.id}/${docId}`;

  const [categories] = await Promise.all([
    loadCategories(queryUserDb).catch((e) => {
      console.error('loadCategories failed (continuing without):', e);
      return [] as CategoryAccount[];
    }),
    env.DOCS.put(key, bytes, { httpMetadata: { contentType: mimeType } }),
  ]);

  const raw = await callGemini(env, mimeType, base64Of(bytes), prompt(categories));

  const byPath = new Map(categories.map((c) => [c.path.toLowerCase(), c]));
  const transactions = raw.flatMap((t) => {
    const amountMinor = toMinor(t.amount);
    const date = toEpochMs(t.date);
    if (amountMinor == null || amountMinor === 0 || date == null || !t.payee?.trim()) return [];
    const match = t.category ? byPath.get(t.category.trim().toLowerCase()) : undefined;
    const category = match && match.type === t.direction ? match : undefined;
    return [
      {
        date,
        payee: t.payee.trim(),
        note: t.note?.trim() || null,
        amountMinor,
        commodity: (t.commodity || 'ARS').trim().toUpperCase(),
        direction: t.direction,
        categoryAccountId: category?.id ?? null,
        categoryPath: category?.path ?? null,
      },
    ];
  });

  return json({ docId, transactions });
}

/** GET /import/document/{docId} — streams the stored original back. */
export async function handleDocument(env: ImportEnv, user: AuthedUser, docId: string): Promise<Response> {
  if (!/^[0-9a-f]{64}$/.test(docId)) return json({ error: 'bad document id' }, 400);
  const obj = await env.DOCS.get(`${user.id}/${docId}`);
  if (!obj) return json({ error: 'not found' }, 404);
  return new Response(obj.body, {
    headers: {
      'content-type': obj.httpMetadata?.contentType ?? 'application/octet-stream',
      'cache-control': 'private, max-age=31536000, immutable',
    },
  });
}
