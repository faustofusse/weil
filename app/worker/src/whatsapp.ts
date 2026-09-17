/**
 * WhatsApp entry point: "panadería 300" becomes a transaction.
 *
 * The bridge (app/wa-bridge, the only non-Cloudflare piece) holds the WhatsApp
 * socket and POSTs each incoming text here, signed with a shared secret. This
 * module owns everything that matters: who the number belongs to, what the
 * message means, and the write to the user's Turso database.
 *
 * Unlike /import/analyze — which only proposes candidates for the review
 * screen — this path writes straight to the ledger. A chat has no review UI,
 * so the transaction is created immediately, echoed back in full, and can be
 * dropped with "deshacer". Every row is tagged in `transaction_sources` with
 * kind `whatsapp` and the WhatsApp message id, which makes the write
 * idempotent under bridge retries and lets Reconcile.kt recognise the same
 * movement when the bank's push notification arrives hours later.
 */
import type { Client } from '@libsql/client';
import { geminiJson, json, loadAccounts, toMinor, type AuthedUser, type ImportEnv } from './import';

export interface WhatsappEnv extends ImportEnv {
  /** Shared with the bridge; signs both directions. */
  BRIDGE_SECRET: string;
  /** Base URL of the bridge, for messages we start ourselves. */
  BRIDGE_URL?: string;
  /** Bot number in international format, digits only — used to build wa.me links. */
  WHATSAPP_NUMBER?: string;
}

const LINK_CODE_TTL_MS = 10 * 60 * 1000;
const UNDO_WINDOW_MS = 24 * 60 * 60 * 1000;
/** Ambiguous characters (0/O, 1/I) are out: the code is typed by hand on a phone. */
const CODE_ALPHABET = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';

/** Seeded by migrateSchema() with these exact ids on every device. */
const EXTERNAL_EXPENSE_ID = 'seed-external-expense';
const EXTERNAL_INCOME_ID = 'seed-external-income';

/**
 * The link tables live next to the auth worker's `users` in the shared D1, so
 * a number resolves to a user in one query before any Turso connection is
 * opened. Created on demand (like ensureEmailColumns) to keep the auth repo's
 * migrations the single owner of *its* schema.
 */
async function ensureTables(db: D1Database): Promise<void> {
  await db.batch([
    db.prepare(
      `create table if not exists whatsapp_links(
         app_slug text not null,
         jid text not null,
         user_id text not null,
         created_at integer not null,
         primary key (app_slug, jid))`
    ),
    db.prepare(
      `create table if not exists whatsapp_link_codes(
         code text primary key,
         app_slug text not null,
         user_id text not null,
         expires_at integer not null)`
    ),
  ]);
}

// ------------------------------------------------------------------ signature

async function hmacHex(secret: string, body: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );
  const mac = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(body));
  return Array.from(new Uint8Array(mac), (b) => b.toString(16).padStart(2, '0')).join('');
}

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

// ---------------------------------------------------------------- app-side API

/**
 * POST /whatsapp/link — the app asks for a pairing code and shows it as a
 * wa.me deep link/QR. The user proves ownership of the number by *sending*
 * the code from it, so we never have to verify a number the app typed in.
 */
export async function handleLink(env: WhatsappEnv, user: AuthedUser): Promise<Response> {
  await ensureTables(env.AUTH_DB);
  const bytes = crypto.getRandomValues(new Uint8Array(6));
  const code = Array.from(bytes, (b) => CODE_ALPHABET[b % CODE_ALPHABET.length]).join('');
  const expiresAt = Date.now() + LINK_CODE_TTL_MS;
  await env.AUTH_DB.batch([
    // One pending code per user: asking again invalidates the previous one.
    env.AUTH_DB.prepare('delete from whatsapp_link_codes where user_id = ? or expires_at < ?')
      .bind(user.id, Date.now()),
    env.AUTH_DB.prepare(
      'insert into whatsapp_link_codes(code, app_slug, user_id, expires_at) values (?, ?, ?, ?)'
    ).bind(code, env.APP_SLUG, user.id, expiresAt),
  ]);
  const text = `vincular ${code}`;
  return json({
    code,
    expiresAt,
    number: env.WHATSAPP_NUMBER ?? null,
    link: env.WHATSAPP_NUMBER
      ? `https://wa.me/${env.WHATSAPP_NUMBER}?text=${encodeURIComponent(text)}`
      : null,
  });
}

/** GET /whatsapp/link — which numbers are linked to this account. */
export async function handleLinkStatus(env: WhatsappEnv, user: AuthedUser): Promise<Response> {
  await ensureTables(env.AUTH_DB);
  const rows = await env.AUTH_DB.prepare(
    'select jid, created_at from whatsapp_links where app_slug = ? and user_id = ?'
  )
    .bind(env.APP_SLUG, user.id)
    .all<{ jid: string; created_at: number }>();
  return json({ numbers: rows.results.map((r) => ({ number: r.jid, linkedAt: r.created_at })) });
}

/** DELETE /whatsapp/link — unlink one number, or all of them. */
export async function handleUnlink(request: Request, env: WhatsappEnv, user: AuthedUser): Promise<Response> {
  await ensureTables(env.AUTH_DB);
  const number = new URL(request.url).searchParams.get('number');
  const statement = number
    ? env.AUTH_DB.prepare('delete from whatsapp_links where app_slug = ? and user_id = ? and jid = ?')
        .bind(env.APP_SLUG, user.id, number)
    : env.AUTH_DB.prepare('delete from whatsapp_links where app_slug = ? and user_id = ?')
        .bind(env.APP_SLUG, user.id);
  await statement.run();
  return json({ ok: true });
}

// -------------------------------------------------------------------- inbound

interface InboundPayload {
  message_id: string;
  from: string;
  push_name?: string;
  text: string;
  timestamp: number;
}

type OpenDb = (user: AuthedUser) => Promise<Client>;

/**
 * POST /whatsapp/inbound — one message from the bridge. The response body's
 * `reply` is sent back to the chat; an empty reply means "stay silent", which
 * is the right answer for a number we do not know.
 */
export async function handleInbound(request: Request, env: WhatsappEnv, openDb: OpenDb): Promise<Response> {
  const raw = await request.text();
  const signature = (request.headers.get('x-bridge-signature') ?? '').replace(/^sha256=/, '');
  if (!env.BRIDGE_SECRET || !timingSafeEqual(signature, await hmacHex(env.BRIDGE_SECRET, raw))) {
    return json({ error: 'bad signature' }, 401);
  }
  const payload = JSON.parse(raw) as InboundPayload;
  const text = (payload.text ?? '').trim();
  if (!text) return json({ reply: '' });

  await ensureTables(env.AUTH_DB);
  const link = await env.AUTH_DB.prepare(
    `select u.id, u.turso_db_name, u.turso_db_hostname
       from whatsapp_links l join users u on u.id = l.user_id
      where l.app_slug = ? and l.jid = ?`
  )
    .bind(env.APP_SLUG, payload.from)
    .first<{ id: string; turso_db_name: string; turso_db_hostname: string }>();

  if (!link) return json({ reply: await claimCode(env, payload, text) });

  const user: AuthedUser = {
    id: link.id,
    turso_db_name: link.turso_db_name,
    turso_db_hostname: link.turso_db_hostname,
  };
  const db = await openDb(user);
  try {
    return json({ reply: await respond(env, db, payload, text) });
  } catch (e) {
    console.error('whatsapp inbound failed:', e);
    return json({ reply: 'Algo salió mal guardando el movimiento. Probá de nuevo.' });
  } finally {
    db.close();
  }
}

/**
 * An unknown number gets exactly one affordance: redeeming a link code. Any
 * other text is met with silence — answering strangers turns the bot into an
 * oracle for "is this number registered".
 */
async function claimCode(env: WhatsappEnv, payload: InboundPayload, text: string): Promise<string> {
  const m = /^(?:vincular|link|vincular:)\s*([a-z0-9]{6})$/i.exec(text);
  if (!m) return '';
  const code = m[1]!.toUpperCase();
  const row = await env.AUTH_DB.prepare(
    'select user_id, expires_at from whatsapp_link_codes where code = ? and app_slug = ?'
  )
    .bind(code, env.APP_SLUG)
    .first<{ user_id: string; expires_at: number }>();
  if (!row || row.expires_at < Date.now()) return 'Ese código no existe o ya venció. Generá uno nuevo desde la app.';
  await env.AUTH_DB.batch([
    env.AUTH_DB.prepare(
      'insert or replace into whatsapp_links(app_slug, jid, user_id, created_at) values (?, ?, ?, ?)'
    ).bind(env.APP_SLUG, payload.from, row.user_id, Date.now()),
    env.AUTH_DB.prepare('delete from whatsapp_link_codes where code = ?').bind(code),
  ]);
  return [
    '✅ Número vinculado.',
    '',
    'Mandame un gasto y lo cargo: «panadería 300».',
    'También entiendo «cobré 50000 sueldo» y «pasé 20000 de galicia a mercado pago».',
    'Escribí «deshacer» para borrar el último.',
  ].join('\n');
}

async function respond(env: WhatsappEnv, db: Client, payload: InboundPayload, text: string): Promise<string> {
  const lower = text.toLowerCase();
  if (/^(deshacer|borrar|undo)$/.test(lower)) return undoLast(db);
  if (/^(ayuda|help|\?)$/.test(lower)) {
    return [
      'Mandame el movimiento en una línea:',
      '• «panadería 300» → gasto',
      '• «cobré 50000 sueldo» → ingreso',
      '• «pasé 20000 de galicia a mercado pago» → traspaso',
      'Podés nombrar el medio de pago: «super 12500 con santander».',
      '«deshacer» borra el último que cargué.',
    ].join('\n');
  }

  // Bridge retries (and WhatsApp's own redeliveries) must not double-charge.
  const seen = await db.execute({
    sql: 'select transaction_id from transaction_sources where kind = ? and ref = ? limit 1',
    args: ['whatsapp', payload.message_id],
  });
  if (seen.rows.length > 0) return '';

  const accounts = await loadAccounts(async (sql) => {
    const rs = await db.execute(sql);
    return rs.rows as unknown as Array<Record<string, unknown>>;
  });
  const parsed = await interpret(env, accounts, text, payload.timestamp);
  if (!parsed) {
    return 'No entendí el movimiento. Probá con algo como «panadería 300».';
  }
  return createTransaction(db, accounts, parsed, payload);
}

// ----------------------------------------------------------------- the model

interface PostableAccountLike {
  id: string;
  path: string;
  type: 'expense' | 'income' | 'asset' | 'liability';
}

interface ParsedMessage {
  understood: boolean;
  direction: 'expense' | 'income' | 'transfer';
  payee: string;
  amount: string;
  commodity: string;
  category: string | null;
  account: string | null;
  note: string | null;
}

const MESSAGE_SCHEMA = {
  type: 'OBJECT',
  properties: {
    understood: {
      type: 'BOOLEAN',
      description: 'False when the message is not a financial movement (a greeting, a question, spam)',
    },
    direction: { type: 'STRING', enum: ['expense', 'income', 'transfer'] },
    payee: { type: 'STRING', description: 'Merchant or counterparty, capitalised; for a transfer, a short description' },
    amount: { type: 'STRING', description: "Positive decimal, '.' as decimal separator, e.g. '1234.56'" },
    commodity: { type: 'STRING', description: "Currency code, 'ARS' unless the message says otherwise" },
    category: {
      type: 'STRING',
      nullable: true,
      description:
        "For expense/income: best matching category path from the list, verbatim, or null. For a transfer: the user's own account the money ARRIVED in",
    },
    account: {
      type: 'STRING',
      nullable: true,
      description:
        "The user's own account the money moved through (the one it LEFT, for a transfer), verbatim from the list, or null when the message does not name one",
    },
    note: { type: 'STRING', nullable: true, description: 'Extra detail worth keeping, else null' },
  },
  required: ['understood', 'direction', 'payee', 'amount', 'commodity'],
} as const;

async function interpret(
  env: WhatsappEnv,
  accounts: PostableAccountLike[],
  text: string,
  timestamp: number
): Promise<ParsedMessage | null> {
  const paths = (type: PostableAccountLike['type']) =>
    accounts.filter((a) => a.type === type).map((a) => a.path);
  const expense = paths('expense');
  const income = paths('income');
  const own = [...paths('asset'), ...paths('liability')];
  const today = new Date(timestamp || Date.now()).toISOString().slice(0, 10);

  const promptText = [
    'You turn a one-line WhatsApp message from an Argentine user into a single transaction.',
    'The messages are terse and informal: "panaderia 300", "super 12.500 con santander", "cobré 50000 sueldo",',
    '"pasé 20k de galicia a mercado pago", "uber 4300".',
    `Today is ${today}.`,
    'Amounts use Argentine conventions: "12.500" is twelve thousand five hundred, "1.234,56" has a decimal comma,',
    '"20k" and "20 mil" are 20000. Always return a positive decimal with "." as the decimal separator.',
    'Direction: default to "expense" — most messages are things the user bought. Use "income" only with an explicit',
    'earning verb ("cobré", "me pagaron", "entró", "vendí"), and "transfer" only when BOTH sides are accounts the',
    'user owns ("pasé X de A a B", "pagué la tarjeta con el banco").',
    'Payee: the merchant, cleaned up and capitalised ("panaderia" → "Panadería"). Never put the amount in the payee.',
    'Set understood=false when the message is not a movement at all (a greeting, a question, a link).',
    expense.length > 0
      ? `Expense categories (verbatim path, or null): ${expense.join(' | ')}`
      : '',
    income.length > 0 ? `Income categories (verbatim path, or null): ${income.join(' | ')}` : '',
    own.length > 0
      ? `The user's own accounts/payment methods (verbatim path, or null when the message names none): ${own.join(' | ')}`
      : '',
    '',
    `Message: ${text}`,
  ]
    .filter(Boolean)
    .join('\n');

  // A one-line message is a far smaller problem than a 12-page statement, and
  // the user is staring at a chat: the lite model goes first here (measured
  // ~55 s vs a couple of seconds on the same message), with the heavier ones
  // still behind it as fallbacks for the 503s.
  const models = env.GEMINI_MODELS.split(',').map((m) => m.trim()).filter(Boolean);
  const chatEnv: WhatsappEnv = {
    ...env,
    GEMINI_MODELS: [...models.filter((m) => m.includes('lite')), ...models.filter((m) => !m.includes('lite'))].join(','),
  };
  const parsed = await geminiJson<ParsedMessage>(chatEnv, [{ text: promptText }], MESSAGE_SCHEMA);
  if (!parsed?.understood) return null;
  return parsed;
}

// ------------------------------------------------------------------- writing

/**
 * The default payment method: the asset account the user has actually been
 * posting to lately. A chat message rarely names one, and asking every time
 * would defeat the point of the channel.
 */
async function defaultAssetAccount(db: Client, accounts: PostableAccountLike[]): Promise<PostableAccountLike | null> {
  const assets = accounts.filter((a) => a.type === 'asset');
  if (assets.length === 0) return null;
  const rs = await db.execute({
    sql: `select p.account_id as id, count(*) as n
            from postings p join ledger_transactions t on t.id = p.transaction_id
           where t.date > ?
           group by p.account_id
           order by n desc`,
    args: [Date.now() - 90 * 24 * 60 * 60 * 1000],
  });
  for (const row of rs.rows as unknown as Array<{ id: string }>) {
    const hit = assets.find((a) => a.id === String(row.id));
    if (hit) return hit;
  }
  return assets[0]!;
}

/** Mirrors Reconcile.kt's normalizePayee closely enough to share fingerprints. */
function normalizePayee(raw: string): string {
  return raw
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim()
    .split(' ')
    .filter((t) => t.length > 0 && !/^\d+$/.test(t))
    .join(' ');
}

/** Same shape as CandidateEvent.eventKey: account | day | signed minor | commodity | payee. */
function eventKeyOf(accountId: string | null, date: number, amountMinor: number, commodity: string, payee: string): string {
  const day = Math.floor(date / 86_400_000);
  return [accountId ?? '?', String(day), String(amountMinor), commodity, normalizePayee(payee)].join('|');
}

function formatAmount(minor: number, commodity: string): string {
  const value = (minor / 100).toLocaleString('es-AR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return commodity === 'ARS' ? `$${value}` : `${value} ${commodity}`;
}

async function createTransaction(
  db: Client,
  accounts: PostableAccountLike[],
  parsed: ParsedMessage,
  payload: InboundPayload
): Promise<string> {
  const amountMinor = toMinor(parsed.amount);
  if (amountMinor == null || amountMinor === 0) {
    return 'No pude leer el monto. Probá con «panadería 300».';
  }
  const commodity = (parsed.commodity || 'ARS').trim().toUpperCase();
  const byPath = new Map(accounts.map((a) => [a.path.toLowerCase(), a]));
  const pick = (path: string | null | undefined, types: PostableAccountLike['type'][]) => {
    const hit = path ? byPath.get(path.trim().toLowerCase()) : undefined;
    return hit && types.includes(hit.type) ? hit : null;
  };

  const own = pick(parsed.account, ['asset', 'liability']) ?? (await defaultAssetAccount(db, accounts));
  if (!own) return 'No tenés ninguna cuenta de activo todavía. Creá una en la app y volvé a mandarlo.';

  // Sign convention is the app's own (see TransactionQuickScreen drafts):
  // the user's account moves first, the category balances it.
  let counter: PostableAccountLike | null;
  let ownDelta: number;
  if (parsed.direction === 'income') {
    counter = pick(parsed.category, ['income']) ?? accounts.find((a) => a.id === EXTERNAL_INCOME_ID) ?? null;
    ownDelta = amountMinor;
  } else if (parsed.direction === 'transfer') {
    counter = pick(parsed.category, ['asset', 'liability']);
    ownDelta = -amountMinor;
    if (!counter) return 'No encontré la cuenta de destino. Nombrala como en la app, por ejemplo «pasé 20000 de galicia a mercado pago».';
  } else {
    counter = pick(parsed.category, ['expense']) ?? accounts.find((a) => a.id === EXTERNAL_EXPENSE_ID) ?? null;
    ownDelta = -amountMinor;
  }
  if (!counter) return 'No encontré una categoría para eso. Creala en la app y repetí el mensaje.';

  const txId = crypto.randomUUID();
  const now = Date.now();
  const date = payload.timestamp || now;
  const payee = parsed.payee.trim() || 'Sin nombre';

  await db.batch(
    [
      {
        sql: 'insert into ledger_transactions(id, date, payee, note, created_at) values (?, ?, ?, ?, ?)',
        args: [txId, date, payee, parsed.note?.trim() || null, now],
      },
      {
        sql: 'insert into postings(id, transaction_id, account_id, amount_minor, commodity) values (?, ?, ?, ?, ?)',
        args: [crypto.randomUUID(), txId, own.id, ownDelta, commodity],
      },
      {
        sql: 'insert into postings(id, transaction_id, account_id, amount_minor, commodity) values (?, ?, ?, ?, ?)',
        args: [crypto.randomUUID(), txId, counter.id, -ownDelta, commodity],
      },
      {
        sql: 'insert or ignore into transaction_sources(transaction_id, kind, ref, event_key, created_at) values (?, ?, ?, ?, ?)',
        args: [
          txId,
          'whatsapp',
          payload.message_id,
          eventKeyOf(own.id, date, ownDelta, commodity, payee),
          now,
        ],
      },
    ],
    'write'
  );

  const arrow = parsed.direction === 'income' ? `${counter.path} → ${own.path}` : `${own.path} → ${counter.path}`;
  return [
    `✅ ${payee} ${formatAmount(amountMinor, commodity)}`,
    arrow,
    '',
    '«deshacer» si me equivoqué.',
  ].join('\n');
}

/** Deletes the most recent transaction this channel created, within a day. */
async function undoLast(db: Client): Promise<string> {
  const rs = await db.execute({
    sql: `select t.id, t.payee, p.amount_minor, p.commodity
            from transaction_sources s
            join ledger_transactions t on t.id = s.transaction_id
            left join postings p on p.transaction_id = t.id and p.amount_minor < 0
           where s.kind = 'whatsapp' and s.created_at > ?
           order by s.created_at desc
           limit 1`,
    args: [Date.now() - UNDO_WINDOW_MS],
  });
  const row = rs.rows[0] as unknown as
    | { id: string; payee: string; amount_minor: number; commodity: string }
    | undefined;
  if (!row) return 'No hay nada reciente para deshacer.';
  await db.batch(
    [
      { sql: 'delete from postings where transaction_id = ?', args: [row.id] },
      { sql: 'delete from transaction_sources where transaction_id = ?', args: [row.id] },
      { sql: 'delete from ledger_transactions where id = ?', args: [row.id] },
    ],
    'write'
  );
  return `🗑️ Borré «${row.payee}» ${formatAmount(Math.abs(Number(row.amount_minor ?? 0)), String(row.commodity ?? 'ARS'))}.`;
}
