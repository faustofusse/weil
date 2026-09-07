/**
 * finance — ingests emails routed by Cloudflare Email Routing (destination
 * is the contact email stored on the auth worker's users table) into the
 * user's Turso database, then keeps the Gmail copy flowing via forward().
 */
import { createClient, type Client } from '@libsql/client';

interface Env {
  AUTH_DB: D1Database;
  TURSO_ORG: string;
  APP_SLUG: string;
  FORWARD_TO: string;
  TURSO_API_TOKEN: string;
}

const BODY_LIMIT = 10_000;

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

function sha256Hex(input: string): Promise<string> {
  return crypto.subtle.digest('SHA-256', new TextEncoder().encode(input)).then((buf) =>
    Array.from(new Uint8Array(buf), (b) => b.toString(16).padStart(2, '0')).join('')
  );
}

export default {
  async email(message: ForwardableEmailMessage, env: Env, ctx: ExecutionContext): Promise<void> {
    try {
      const to = message.to.trim().toLowerCase();

      // recipient -> user's Turso DB (the auth worker owns the mapping)
      const user = await env.AUTH_DB.prepare(
        `SELECT turso_db_name, turso_db_hostname FROM users WHERE app_slug = ? AND email = ?`
      ).bind(env.APP_SLUG, to).first<{ turso_db_name: string; turso_db_hostname: string }>();
      if (!user) {
        console.log(`no ${env.APP_SLUG} user for ${to}; skipping ingest`);
        return;
      }

      // parse the raw email minimally: headers + first body segment
      const raw = await new Response(message.raw).text();
      const sep = raw.indexOf('\r\n\r\n');
      const headers = sep >= 0 ? raw.slice(0, sep) : raw;
      const bodyText = sep >= 0 ? raw.slice(sep + 4, sep + 4 + BODY_LIMIT) : null;
      const date = /^date:\s*(.*)$/im.exec(headers)?.[1]?.trim() ?? new Date().toISOString();

      const id = await sha256Hex(`${message.from}|${to}|${date}|${message.headers.get('subject') ?? ''}`);
      const receivedAt = Math.floor(Date.now() / 1000);

      ctx.waitUntil((async () => {
        const db = await platformClient(env, user.turso_db_name, user.turso_db_hostname);
        try {
          await db.execute({
            sql: 'insert or ignore into emails(id, from_email, to_email, subject, body_text, received_at) values (?, ?, ?, ?, ?, ?)',
            args: [id, message.from, to, message.headers.get('subject'), bodyText, receivedAt],
          });
          console.log(`email ${id} inserted for ${user.turso_db_name}`);
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
