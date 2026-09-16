/**
 * finance — ingests emails routed by Cloudflare Email Routing (destination
 * is the contact email stored on the auth worker's users table) into the
 * user's Turso database, then keeps the Gmail copy flowing via forward().
 */
import { createClient, type Client } from '@libsql/client';
import { parseEmail } from './email';
import { authenticate, handleAnalyze, handleDocument, json } from './import';

interface Env {
  AUTH_DB: D1Database;
  DOCS: R2Bucket;
  TURSO_ORG: string;
  APP_SLUG: string;
  FORWARD_TO: string;
  TURSO_API_TOKEN: string;
  ACCOUNT_ID: string;
  AI_GATEWAY: string;
  GEMINI_MODELS: string;
  GEMINI_API_KEY: string;
}


let cachedToken: { dbName: string; jwt: string } | null = null;

async function platformToken(env: Env, dbName: string): Promise<string> {
  if (cachedToken?.dbName === dbName) return cachedToken.jwt;
  const res = await fetch(
    `https://api.turso.tech/v1/organizations/${env.TURSO_ORG}/databases/${dbName}/auth/tokens`,
    {
      method: 'POST',
      headers: { authorization: `Bearer ${env.TURSO_API_TOKEN}`, 'content-type': 'application/json' },
      body: JSON.stringify({ permissions: { read_attach: { databases: [] } } }),
    }
  );
  if (!res.ok) throw new Error(`turso token: ${res.status} ${await res.text()}`);
  const data = (await res.json()) as { jwt: string };
  cachedToken = { dbName, jwt: data.jwt };
  return data.jwt;
}

/** @libsql/client with the org (platform) token acts on any org database. */
async function platformClient(env: Env, dbName: string, hostname: string): Promise<Client> {
  return createClient({
    url: `libsql://${hostname}`,
    authToken: await platformToken(env, dbName),
  });
}

/**
 * Adds `emails.body_html` when the user's database predates it. The app's own
 * migration (Schema.kt, user_version 3) does the same, but ingestion cannot
 * wait for a device to open the app first — the insert would fail and the mail
 * would be dropped. `alter table` is not idempotent, hence the pragma check.
 */
async function ensureEmailColumns(db: Client): Promise<void> {
  const info = await db.execute('pragma table_info(emails)');
  const columns = new Set(info.rows.map((r) => String((r as unknown as Record<string, unknown>).name)));
  if (!columns.has('body_html')) {
    await db.execute('alter table emails add column body_html text');
  }
}

function sha256Hex(input: string): Promise<string> {
  return crypto.subtle.digest('SHA-256', new TextEncoder().encode(input)).then((buf) =>
    Array.from(new Uint8Array(buf), (b) => b.toString(16).padStart(2, '0')).join('')
  );
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);

    // ---- document import (cookie-authed, same session as the auth worker) ----
    if (url.pathname.startsWith('/import/')) {
      const user = await authenticate(request, env);
      if (user instanceof Response) return user;

      if (request.method === 'POST' && url.pathname === '/import/analyze') {
        const queryUserDb = async (sql: string) => {
          const db = await platformClient(env, user.turso_db_name, user.turso_db_hostname);
          try {
            const rs = await db.execute(sql);
            return rs.rows as unknown as Array<Record<string, unknown>>;
          } finally {
            db.close();
          }
        };
        try {
          return await handleAnalyze(request, env, user, queryUserDb);
        } catch (e) {
          console.error('import analyze failed:', e);
          return json({ error: e instanceof Error ? e.message : 'analyze failed' }, 502);
        }
      }

      const doc = /^\/import\/document\/([0-9a-f]+)$/.exec(url.pathname);
      if (request.method === 'GET' && doc?.[1]) {
        return handleDocument(env, user, doc[1]);
      }

      return json({ error: 'not found' }, 404);
    }

    if (request.method === 'POST' && url.pathname === '/mp/webhook') {
      const signature = request.headers.get('x-signature');
      const body = await request.text();
      console.log('MP webhook received', { signature, body });
      return new Response('ok', { status: 200 });
    }
    return new Response('not found', { status: 404 });
  },

  async email(message: ForwardableEmailMessage, env: Env, ctx: ExecutionContext): Promise<void> {
    try {
      const to = message.to.trim().toLowerCase();

      // recipient -> user's Turso DB (the auth worker owns the mapping)
      const user = await env.AUTH_DB.prepare(
        `SELECT id, turso_db_name, turso_db_hostname FROM users WHERE app_slug = ? AND email = ?`
      ).bind(env.APP_SLUG, to).first<{ id: string; turso_db_name: string; turso_db_hostname: string }>();
      if (!user) {
        console.log(`no ${env.APP_SLUG} user for ${to}; skipping ingest`);
        return;
      }

      // The stream is single-use and both the parser and the R2 archive need it.
      const raw = await new Response(message.raw).arrayBuffer();
      const parsed = await parseEmail(raw);

      // The id stays keyed on the *raw* subject header: decoding it would
      // change the hash for the same message and let a reprocessed mail in twice.
      const rawSubject = message.headers.get('subject') ?? '';
      const date = message.headers.get('date')?.trim() ?? new Date().toISOString();
      const id = await sha256Hex(`${message.from}|${to}|${date}|${rawSubject}`);
      const receivedAt = Date.now();

      ctx.waitUntil((async () => {
        // Archived so a future parser improvement can reprocess the message;
        // nothing else reads this key today.
        await env.DOCS.put(`${user.id}/email/${id}`, raw, {
          httpMetadata: { contentType: 'message/rfc822' },
        }).catch((e) => console.error(`email ${id} archive failed:`, e));

        const db = await platformClient(env, user.turso_db_name, user.turso_db_hostname);
        try {
          await ensureEmailColumns(db);
          await db.execute({
            sql: 'insert or ignore into emails(id, from_email, to_email, subject, body_text, body_html, received_at) values (?, ?, ?, ?, ?, ?, ?)',
            args: [id, message.from, to, parsed.subject, parsed.text, parsed.html, receivedAt],
          });
          console.log(`email ${id} inserted for ${user.turso_db_name} (html: ${parsed.html !== null})`);
        } finally {
          db.close();
        }
      })());
    } catch (e) {
      console.error('email ingest failed:', e);
    } finally {
      // the Gmail copy always goes through, ingest failures never block it
      await message.forward(env.FORWARD_TO);
    }
  },
} satisfies ExportedHandler<Env>;
