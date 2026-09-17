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

type PostableType = 'expense' | 'income' | 'asset' | 'liability';

interface PostableAccount {
  id: string;
  name: string;
  path: string;
  type: PostableType;
}

/**
 * The user's postable accounts with colon-joined paths. Expense/income are the
 * categories; asset/liability are the payment methods ("Forma de Pago: Dinero
 * en Mercado Pago" should land on the user's own Mercado Pago account, not on
 * whatever the review screen defaults to).
 *
 * Parents are included: the tree is organizational, postings may reference any
 * account, and excluding them hid real categories ("Comida" the moment it grew
 * a "Comida:Verduras" child).
 */
export async function loadAccounts(
  queryUserDb: (sql: string) => Promise<Array<Record<string, unknown>>>
): Promise<PostableAccount[]> {
  const rows = await queryUserDb('select id, name, parent_id, type from accounts');
  const byId = new Map<string, { id: string; name: string; parent_id: string | null; type: string }>();
  for (const r of rows) {
    const id = String(r.id);
    byId.set(id, {
      id,
      name: String(r.name),
      parent_id: r.parent_id == null ? null : String(r.parent_id),
      type: String(r.type),
    });
  }
  const pathOf = (id: string): string => {
    const acc = byId.get(id);
    if (!acc) return '';
    return acc.parent_id ? `${pathOf(acc.parent_id)}:${acc.name}` : acc.name;
  };
  const postable: PostableType[] = ['expense', 'income', 'asset', 'liability'];
  const out: PostableAccount[] = [];
  for (const acc of byId.values()) {
    if (!postable.includes(acc.type as PostableType)) continue;
    out.push({ id: acc.id, name: acc.name, path: pathOf(acc.id), type: acc.type as PostableType });
  }
  return out;
}

/**
 * Gemini structured-output schema. One entry is one payment: a single
 * movement of the user's own money, split across one or more categories.
 * A bank/card statement row is a payment with one split; a receipt with
 * distinct line items is a payment with one split per item. There's no
 * separate "receipt mode" — the split list is the only shape, and its length
 * happens to be 1 most of the time. Amounts are decimal strings, parsed here.
 */
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
          commodity: { type: 'STRING', description: "Currency code. 'ARS' unless the document explicitly shows another currency (e.g. USD)" },
          direction: {
            type: 'STRING',
            enum: ['expense', 'income', 'transfer'],
            description:
              'expense = money leaves the user, income = money comes in, transfer = money moves between two accounts the user owns (paying a credit card from a bank account, buying foreign currency, moving money to a wallet) so the user is no richer or poorer',
          },
          account: {
            type: 'STRING',
            nullable: true,
            description:
              "The user's own account the money moved through (payment method, wallet, bank or card), verbatim from the provided list, or null when the document does not say. For a transfer this is the account the money LEFT",
          },
          counterAmount: {
            type: 'STRING',
            nullable: true,
            description:
              "Only for a transfer whose two sides are in different currencies (buying or selling foreign currency, paying a USD card bill in pesos): the positive decimal amount that ARRIVED in the destination account, e.g. '730.00' for 'Compraste u$s 730,00 a $ 1.520,00'. Null when both sides are the same currency",
          },
          counterCommodity: {
            type: 'STRING',
            nullable: true,
            description: "Currency code of counterAmount (e.g. 'USD'). Null when both sides share a currency",
          },
          splits: {
            type: 'ARRAY',
            description:
              "One entry per category this payment's total is split across. Almost always one entry; use more only when the document itself breaks the payment into distinct, separately categorizable items (e.g. a supermarket receipt's line items). The split amounts must add up to the full payment.",
            items: {
              type: 'OBJECT',
              properties: {
                amount: { type: 'STRING', description: "Positive decimal amount for this split, '.' as decimal separator, e.g. '1234.56'" },
                category: {
                  type: 'STRING',
                  nullable: true,
                  description:
                    "For expense/income: best-matching category path from the provided list, verbatim, or null. For a transfer: the user's own account the money ARRIVED in, from the accounts list",
                },
              },
              required: ['amount'],
            },
          },
        },
        required: ['date', 'payee', 'commodity', 'direction', 'splits'],
      },
    },
  },
  required: ['transactions'],
} as const;

function prompt(accounts: PostableAccount[]): string {
  const expense = accounts.filter((c) => c.type === 'expense').map((c) => c.path);
  const income = accounts.filter((c) => c.type === 'income').map((c) => c.path);
  const own = accounts.filter((c) => c.type === 'asset' || c.type === 'liability').map((c) => c.path);
  return [
    'You extract financial transactions from a document (receipt, invoice, or bank/card statement, possibly multi-page).',
    'Return every distinct transaction you can see. For bank or card statements, emit one entry per statement row;',
    'ignore running balance columns, subtotals, opening/closing balances and summary rows.',
    'Use debit/credit columns or signs to decide direction: debits/charges are "expense", credits/deposits are "income",',
    'unless both sides of the movement are accounts the user owns, in which case it is a "transfer".',
    'Amounts are always positive decimals. Assume currency ARS unless the document explicitly states another currency for that amount.',
    'Dates: use the document\'s dates in YYYY-MM-DD. If the year is missing, infer it from context (statement period or today).',
    'Payee: a short, human-readable merchant/counterparty name (strip codes, reference numbers and legal suffixes).',
    'Each transaction is one payment: one movement of the user\'s own money, made up of one or more "splits".',
    'Use exactly one split for the common case — a single charge, a statement row, a simple receipt with one purpose.',
    'Use multiple splits only when the document itself itemizes the payment into parts that belong in different',
    'categories — a supermarket receipt listing groceries and a pharmacy item, an invoice separating a fee from a tax.',
    'Do not split a payment just because it lists many similar items (e.g. ten grocery items all under "Comida"); one',
    'split covering the whole amount is correct there. The split amounts must sum exactly to the payment\'s total.',
    'For bank/card statements, each row is its own transaction with a single split — never merge multiple rows into one.',
    '',
    'STATEMENTS — avoiding duplicates and noise:',
    'A statement prints the SAME movement several times: once in the main account-movements table and again in',
    'per-product detail sections ("Tarjeta de débito - Compras", "Pagos", "Pago anterior y devoluciones", "Detalle").',
    'Emit each economic event exactly ONCE. Prefer the row from the main movements table; skip a later section\'s row',
    'when it repeats the same date/amount/description. A credit-card "Consumos del mes" row is NOT a duplicate of the',
    'account\'s "Pago de tarjeta de credito" row: the purchases and the payment of the card bill are different events.',
    'Never emit anything from: spending-breakdown charts or donuts ("Así usaste tu dinero"), product summaries',
    '("Resumen de tus productos", limits, rates, points), installment projections ("Cuotas a vencer", "Próximas cuotas"),',
    'minimum-payment / financing plans, totals rows ("Total", "Saldo total", "Consumos totales", "Monto total"),',
    'opening/closing balances ("Saldo Inicial", "Saldo anterior") or legal/informational pages ("Legales", "Alicuotas").',
    'Tax and fee rows that really were charged ("Impuesto ley 25.413", "IVA", "IIBB percep", "Impuesto de sellos",',
    '"Pago interes por saldo", maintenance fees) ARE real transactions — keep them.',
    '',
    'STATEMENTS — which account a row belongs to:',
    'When the movements table has one amount column per account (e.g. "Cuenta sueldo en pesos" and "Cuenta Corriente',
    'en pesos"), the column carrying the amount names the account; the "Saldo en cuenta" / running-balance column is',
    'not an amount. A section heading also names the account for every row under it ("Movimientos en dólares",',
    '"Caja de Ahorro en dólares", "Consumos de ... | Tarjeta terminada en 1500"). Rows under a credit-card consumption',
    'section belong to that card (a liability), not to a bank account.',
    '',
    'TRANSFERS (direction "transfer"): both legs are accounts the user owns, so nothing was spent or earned.',
    'Typical statement cases: paying the credit card from the bank account ("Pago de tarjeta de credito",',
    '"Pago tarjeta de credito visa"), buying or selling foreign currency ("Debito por compra de dolares" paired with',
    '"Acreditacion compra de dolares" — emit ONE transfer, not two rows), and transfers between the user\'s own',
    'accounts ("Transf recibida cvu mismo titular", "mismo titular", the user\'s own name on both sides).',
    'For a transfer set "account" to the account the money left and the split\'s "category" to the account it arrived in.',
    'A currency exchange moves different amounts on each side: "amount" is what left (the pesos debited) and',
    '"counterAmount"/"counterCommodity" are what arrived (the dollars credited), taken from the row\'s own text',
    '("Compraste u$s 730,00 a $ 1.520,00" → amount 1109600.00 ARS, counterAmount 730.00 USD). Fill them whenever the',
    'two sides are in different currencies; leave both null otherwise.',
    'If one of the two sides is not in the accounts list, still use "transfer" and leave that side null.',
    'Only use "transfer" when the counterparty really is the user; a transfer to another person is an expense.',
    'The account holder is named in the statement header: a row whose counterparty is that same person ("mismo',
    'titular", "a nombre propio", the holder\'s own name after "A" or "De") is a transfer in BOTH directions, including',
    'money coming in. For an incoming transfer, "account" is still the account the money left (the other wallet or bank,',
    'null if it is not in the list) and the split\'s "category" is the statement account the money landed in.',
    '',
    'Dates are day/month/year in Argentine documents (31/07/26 is 2026-07-31), never month/day.',
    'Credit-card installment rows ("09 de 18", "cuota 3/6") print the date of the ORIGINAL purchase but charge only',
    'this period\'s installment: date them at the statement\'s closing date instead, so they land in the month they are',
    'actually being paid, and keep the installment marker in "note".',
    '',
    // Vocabulary, not policy: these rows are opaque enough that the model
    // guesses without help, but WHERE each one belongs still comes from the
    // user's own account list below. Rates and thresholds are deliberately
    // absent — they change every few months and the document already carries
    // the computed amount.
    'ARGENTINE BANK/CARD VOCABULARY (what a row IS; where it goes is still decided by the account lists below):',
    'Taxes: "impuesto ley 25.413" / "imp. al cheque" (debit-credit tax), "impuesto de sellos", "IIBB" or',
    '"ingresos brutos" (often as "percep-caba", "percep-iibb"), "IVA" ("iva rg 4240", "iva 21% reg de transfisc",',
    '"ley 27743"). An IVA or tax row attached to a fee is still a tax row of its own.',
    'Fees and interest (a bank charge, not a tax): "comision", "mantenimiento de cuenta", "cargo por renovacion",',
    '"interes por descubierto", "punitorios", "gastos administrativos", "seguro de vida sobre saldo deudor".',
    'Withholdings and prepaid taxes are not ordinary spending: the user may recover them later. A row is one of these',
    'whenever it says "percepcion", "percep", "retencion", or cites a withholding regime — "Db.rg 5617", "Cr.rg 5617",',
    '"iva rg 4240", "iibb percep-caba", "rg 3819". ALL of them, including the IIBB and IVA ones, belong together.',
    'If the account list has an account naming percepciones, retenciones or AFIP, put every such row there; otherwise',
    'use the closest tax account.',
    'A "Cr.rg ..." / "credito percepcion" / "devolucion percepcion" row REVERSES an earlier "Db.rg ..." percepcion.',
    'It is the same kind of thing with the opposite sign, so give it the SAME account as the percepcion rows (the',
    'direction already carries the sign) instead of treating it as ordinary income.',
    'A row labelled "Total", "Saldo anterior" or "Total a pagar" is never a transaction, in any section. In particular',
    'skip the whole "Pago anterior y devoluciones" block: its payment rows repeat the account\'s own rows and its',
    'amounts sit in a separate column block that is easy to misread — never emit a row from it.',
    'Other common wordings: "snp debito directo" / "deb. automatico" = a direct-debit bill payment (categorize by the',
    'named service, e.g. Metrogas = gas, Edenor = electricity); "tarj nro. NNNN" / "terminada en NNNN" identifies the',
    'card, not the merchant; "tc1520,000" is the exchange rate applied, not an amount; "CVU"/"CBU"/"alias" identify',
    'the counterparty account; "Merpago/<merchant>" is a purchase from <merchant> settled through Mercado Pago, so',
    'the payee is the merchant.',
    '',
    expense.length > 0
      ? `For "expense" splits, pick the best matching category from this list (verbatim path) or null: ${expense.join(' | ')}`
      : '',
    income.length > 0
      ? `For "income" splits, pick the best matching category from this list (verbatim path) or null: ${income.join(' | ')}`
      : '',
    own.length > 0
      ? [
          `"account" is the user's own account the money moved through. Pick it (verbatim path) from: ${own.join(' | ')}`,
          'Use the payment method, wallet, bank, or card the document names — e.g. a Mercado Pago receipt paid with',
          '"Dinero disponible en Mercado Pago" belongs to the user\'s Mercado Pago account, a statement header names the',
          'account for every row on it, and a card slip names the card. Match on the brand or bank name even when the',
          'wording differs. Use null only when the document gives no usable hint.',
        ].join(' ')
      : '',
    'If the document contains no transactions, return an empty list.',
  ]
    .filter(Boolean)
    .join('\n');
}

interface GeminiSplit {
  amount: string;
  category?: string | null;
}

interface GeminiCandidateTx {
  date: string;
  payee: string;
  note?: string | null;
  commodity: string;
  direction: 'expense' | 'income' | 'transfer';
  account?: string | null;
  counterAmount?: string | null;
  counterCommodity?: string | null;
  splits: GeminiSplit[];
}

/**
 * generateContent through the account's AI Gateway when it exists, falling
 * back to the Google API directly (same request shape, same key header).
 */
export async function geminiJson<T>(
  env: ImportEnv,
  parts: Array<Record<string, unknown>>,
  responseSchema: unknown
): Promise<T> {
  const body = JSON.stringify({
    contents: [{ parts }],
    generationConfig: {
      responseMimeType: 'application/json',
      responseSchema,
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
  return JSON.parse(text) as T;
}

async function callGemini(env: ImportEnv, mimeType: string, base64: string, promptText: string): Promise<GeminiCandidateTx[]> {
  const parsed = await geminiJson<{ transactions?: GeminiCandidateTx[] }>(
    env,
    [{ inline_data: { mime_type: mimeType, data: base64 } }, { text: promptText }],
    RESPONSE_SCHEMA
  );
  return parsed.transactions ?? [];
}

/** "1234.56" → 123456 minor units; null when unparseable. */
export function toMinor(amount: string): number | null {
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

  const [accounts] = await Promise.all([
    loadAccounts(queryUserDb).catch((e) => {
      console.error('loadAccounts failed (continuing without):', e);
      return [] as PostableAccount[];
    }),
    env.DOCS.put(key, bytes, { httpMetadata: { contentType: mimeType } }),
  ]);

  const raw = await callGemini(env, mimeType, base64Of(bytes), prompt(accounts));

  const byPath = new Map(accounts.map((c) => [c.path.toLowerCase(), c]));
  const transactions = raw.flatMap((t) => {
    const date = toEpochMs(t.date);
    if (date == null || !t.payee?.trim()) return [];
    // A transfer's "category" is the other side of the movement: one of the
    // user's own accounts, not an expense/income category.
    const categoryTypes: PostableType[] =
      t.direction === 'transfer' ? ['asset', 'liability'] : [t.direction];
    const splits = (t.splits ?? []).flatMap((s) => {
      const amountMinor = toMinor(s.amount);
      if (amountMinor == null || amountMinor === 0) return [];
      const match = s.category ? byPath.get(s.category.trim().toLowerCase()) : undefined;
      const category = match && categoryTypes.includes(match.type) ? match : undefined;
      return [{ amountMinor, categoryAccountId: category?.id ?? null, categoryPath: category?.path ?? null }];
    });
    if (splits.length === 0) return [];
    // The model may hand back a category path here; only the user's own
    // asset/liability accounts are valid payment methods.
    const ownMatch = t.account ? byPath.get(t.account.trim().toLowerCase()) : undefined;
    const own = ownMatch && (ownMatch.type === 'asset' || ownMatch.type === 'liability') ? ownMatch : undefined;
    // The far leg of a currency exchange: different amount, different
    // commodity. Only meaningful when it really is a second currency.
    const counterCommodity = t.counterCommodity?.trim().toUpperCase() || null;
    const counterMinor = t.counterAmount ? toMinor(t.counterAmount) : null;
    const commodity = (t.commodity || 'ARS').trim().toUpperCase();
    const hasCounter =
      t.direction === 'transfer' &&
      counterCommodity != null &&
      counterCommodity !== commodity &&
      counterMinor != null &&
      counterMinor !== 0;
    return [
      {
        date,
        payee: t.payee.trim(),
        note: t.note?.trim() || null,
        commodity,
        direction: t.direction === 'income' || t.direction === 'transfer' ? t.direction : 'expense',
        counterAmountMinor: hasCounter ? counterMinor : null,
        counterCommodity: hasCounter ? counterCommodity : null,
        accountId: own?.id ?? null,
        accountPath: own?.path ?? null,
        splits,
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
