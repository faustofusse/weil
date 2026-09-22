/**
 * Embedding models compared on the only corpus that matters: yours.
 *
 *   bun scripts/embeddings.ts [--only gemma,qwen] [--k 5]
 *
 * The question is not which model scores best on somebody's leaderboard, it
 * is which one puts your movements near your movements. So the measurement
 * runs over the messages Jev already judged in `data/lab.db` — the ones that
 * carry money words, which are the hard ones, since a promotion with a price
 * in it looks exactly like a purchase to anything shallower than meaning.
 *
 * Three numbers per model:
 *
 *   - **separa**: leave-one-out kNN over the whole corpus. For each message,
 *     ask its k nearest neighbours whether it is a movement and take the
 *     majority. This is retrieval doing the job it does in the app, not a
 *     classifier trained for it.
 *   - **P@1 / P@3**: for a movement, is the nearest neighbour another
 *     movement? That is the precedent the suggestion path goes looking for,
 *     and a promotion coming back first is the failure that matters.
 *   - **ms**: one text at a time, which is what the product does. Batch
 *     throughput is reported too because backfilling every old row is the
 *     other thing this model will be asked to do.
 *
 * Money: `USD/1k` is a thousand single embeds at ~20 tokens each, at the
 * prices the model pages carry. It is small for every model here; it is
 * printed so nobody has to guess whether it is small.
 */
import { createClient } from '@libsql/client';
import { decodeMimeHeader, emailPlainText } from '../lib/text';

const ACCOUNT = 'f12da7851e4dd1d107a80417a1d4cbbd';
const GATEWAY = process.env.CLOUDFLARE_AI_GATEWAY ?? 'finance';
const K = Number(arg('k', '5'));
const ONLY = (arg('only', '') || '').split(',').filter(Boolean);
const TOKENS_PER_TEXT = 20;

function arg(name: string, fallback: string): string {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 ? (process.argv[i + 1] ?? fallback) : fallback;
}

interface Model {
  label: string;
  /** Per million input tokens, from the model page. */
  usdPerMillion: number;
  embed(texts: string[]): Promise<number[][]>;
}

function need(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`missing ${name}`);
  return value;
}

/** Truncating a Matryoshka vector denormalizes it, and cosine cares. */
function normalize(v: number[]): number[] {
  const norm = Math.sqrt(v.reduce((a, x) => a + x * x, 0));
  return norm === 0 ? v : v.map((x) => x / norm);
}

/** What runs in production today: Google directly, truncated to 512. */
const gemini: Model = {
  label: 'gemini-embedding-001/512',
  usdPerMillion: 0.15,
  async embed(texts) {
    const res = await fetch(
      'https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:batchEmbedContents',
      {
        method: 'POST',
        headers: { 'x-goog-api-key': need('GEMINI_API_KEY'), 'content-type': 'application/json' },
        body: JSON.stringify({
          requests: texts.map((text) => ({
            model: 'models/gemini-embedding-001',
            content: { parts: [{ text }] },
            outputDimensionality: 512,
          })),
        }),
      }
    );
    if (!res.ok) throw new Error(`gemini ${res.status}: ${(await res.text()).slice(0, 200)}`);
    const json = (await res.json()) as { embeddings: Array<{ values: number[] }> };
    return json.embeddings.map((e) => normalize(e.values));
  },
};

function cloudflare(label: string, model: string, usdPerMillion: number): Model {
  return {
    label,
    usdPerMillion,
    async embed(texts) {
      const res = await fetch(
        `https://api.cloudflare.com/client/v4/accounts/${ACCOUNT}/ai/v1/embeddings`,
        {
          method: 'POST',
          headers: {
            authorization: `Bearer ${need('CLOUDFLARE_API_TOKEN')}`,
            'content-type': 'application/json',
            'cf-aig-gateway-id': GATEWAY,
          },
          body: JSON.stringify({ model, input: texts }),
        }
      );
      if (!res.ok) throw new Error(`${label} ${res.status}: ${(await res.text()).slice(0, 200)}`);
      const json = (await res.json()) as { data: Array<{ index: number; embedding: number[] }> };
      const out: number[][] = new Array(texts.length);
      // The response does not promise order; `index` does.
      for (const d of json.data) out[d.index] = normalize(d.embedding);
      return out;
    },
  };
}

/**
 * Gemma is Matryoshka-trained: the first 512 numbers of its 768 are a usable
 * vector on their own. Worth knowing, because staying at 512 means the
 * `F32_BLOB(512)` column, and with it SCHEMA_VERSION, never moves.
 */
function truncated(model: Model, dims: number): Model {
  return {
    label: `${model.label}/${dims}`,
    usdPerMillion: model.usdPerMillion,
    embed: async (texts) => (await model.embed(texts)).map((v) => normalize(v.slice(0, dims))),
  };
}

const MODELS: Record<string, Model> = {
  gemini,
  gemma: cloudflare('embeddinggemma-300m', '@cf/google/embeddinggemma-300m', 0.012),
  qwen: cloudflare('qwen3-embedding-0.6b', '@cf/qwen/qwen3-embedding-0.6b', 0.0118),
  'bge-m3': cloudflare('bge-m3', '@cf/baai/bge-m3', 0.0118),
  'gemma-512': truncated(cloudflare('embeddinggemma-300m', '@cf/google/embeddinggemma-300m', 0.012), 512),
  'gemma-256': truncated(cloudflare('embeddinggemma-300m', '@cf/google/embeddinggemma-300m', 0.012), 256),
};

interface Case {
  text: string;
  movement: boolean;
  origin: string;
  /** Hand-written in `data/merchants.json`; `?` where the text names none. */
  merchant?: string;
}

const MERCHANTS: Record<string, string> = JSON.parse(
  await Bun.file(new URL('../data/merchants.json', import.meta.url).pathname).text()
);

const merchantOf = (id: string) => MERCHANTS[id.slice(0, 6)];

/**
 * The corpus: every message Jev scored, which is every message that carried a
 * money word. Promotions outnumber movements here roughly two to one, which
 * is the balance the app actually sees.
 */
async function corpus(): Promise<Case[]> {
  const db = createClient({ url: `file:${new URL('../data/lab.db', import.meta.url).pathname}` });
  const out: Case[] = [];
  const notifications = await db.execute(
    'select id, package_name, title, text, is_movement from notifications where jev_score is not null'
  );
  for (const r of notifications.rows) {
    out.push({
      origin: String(r.package_name),
      text: `${r.title}\n${String(r.text).slice(0, 600)}`.trim(),
      movement: Number(r.is_movement ?? 0) === 1,
      merchant: merchantOf(String(r.id)),
    });
  }
  const emails = await db.execute(
    'select id, from_email, subject, body_text, body_html, is_movement from emails where jev_score is not null'
  );
  for (const r of emails.rows) {
    // The stored text part is raw MIME truncated at 10 kB with the receipt
    // past the cut, so the HTML is the one that carries the merchant — the
    // same reason `Ingest.kt` reads `body_html` first.
    const body = emailPlainText(String(r.body_html || r.body_text || ''));
    out.push({
      origin: String(r.from_email),
      text: `${decodeMimeHeader(String(r.subject ?? ''))}\n${body.slice(0, 600)}`.trim(),
      movement: Number(r.is_movement ?? 0) === 1,
      merchant: merchantOf(String(r.id)),
    });
  }
  return out.filter((c) => c.text.length > 10);
}

const dot = (a: number[], b: number[]) => a.reduce((sum, x, i) => sum + x * b[i]!, 0);

function percentile(values: number[], p: number): number {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.floor((sorted.length * p) / 100))] ?? 0;
}

async function main() {
  const cases = await corpus();
  const movements = cases.filter((c) => c.movement).length;
  console.log(
    `${cases.length} mensajes (${movements} movimientos, ${cases.length - movements} promociones), k=${K}\n`
  );

  const rows: string[] = [];
  // Only merchants seen more than once can be retrieved at all: a single
  // example has nothing of its own kind to find.
  const counts = new Map<string, number>();
  for (const c of cases) {
    if (c.merchant && c.merchant !== '?') counts.set(c.merchant, (counts.get(c.merchant) ?? 0) + 1);
  }
  const queries = cases.filter((c) => c.merchant && c.merchant !== '?' && counts.get(c.merchant)! > 1);
  console.log(
    `${queries.length} consultas de comercio sobre ${counts.size} comercios etiquetados a mano\n`
  );

  rows.push(
    '| modelo | dims | separa | mov@1 | comercio@1 | comercio@3 | 1 texto p50 | lote de 64 | USD/1k |'
  );
  rows.push('| --- | --- | --- | --- | --- | --- | --- | --- | --- |');

  for (const [key, model] of Object.entries(MODELS)) {
    if (ONLY.length > 0 && !ONLY.includes(key)) continue;
    process.stdout.write(`${model.label}… `);
    try {
      // Batch the corpus in chunks small enough that one failure is cheap.
      const vectors: number[][] = [];
      const batchStarted = Date.now();
      for (let i = 0; i < cases.length; i += 64) {
        vectors.push(...(await model.embed(cases.slice(i, i + 64).map((c) => c.text))));
      }
      const batchMs = Math.round((Date.now() - batchStarted) / Math.ceil(cases.length / 64));

      // Single-text latency, measured separately because that is the shape the
      // app uses: one notification, one vector, user waiting.
      const singles: number[] = [];
      for (const c of cases.slice(0, 8)) {
        const started = Date.now();
        await model.embed([c.text]);
        singles.push(Date.now() - started);
      }

      let correct = 0;
      let atOne = 0;
      let sameMerchantOne = 0;
      let sameMerchantThree = 0;
      let asked = 0;
      const misses: string[] = [];
      for (let i = 0; i < cases.length; i++) {
        const ranked = cases
          .map((c, j) => ({ c, score: j === i ? -Infinity : dot(vectors[i]!, vectors[j]!) }))
          .sort((a, b) => b.score - a.score)
          .slice(0, Math.max(K, 3));
        const votes = ranked.slice(0, K).filter((r) => r.c.movement).length;
        if (votes * 2 > K === cases[i]!.movement) correct++;
        if (cases[i]!.movement && ranked[0]!.c.movement) atOne++;
        // The merchant question, asked against the whole corpus: a promotion
        // ranked first counts as a miss, which is the failure that matters.
        const merchant = cases[i]!.merchant;
        if (merchant && merchant !== '?' && counts.get(merchant)! > 1) {
          asked++;
          if (ranked[0]!.c.merchant === merchant) sameMerchantOne++;
          else if (process.argv.includes('--misses')) {
            misses.push(`${merchant} → ${ranked[0]!.c.merchant ?? 'promo'}: ${cases[i]!.text.split('\n')[0]!.slice(0, 48)}`);
          }
          if (ranked.slice(0, 3).some((r) => r.c.merchant === merchant)) sameMerchantThree++;
        }
      }

      const pct = (n: number, total: number) => `${Math.round((n / total) * 100)}%`;
      rows.push(
        `| ${model.label} | ${vectors[0]!.length} | ${pct(correct, cases.length)} | ` +
          `${pct(atOne, movements)} | ${pct(sameMerchantOne, asked)} | ${pct(sameMerchantThree, asked)} | ` +
          `${percentile(singles, 50)} ms | ${batchMs} ms | ` +
          `$${((model.usdPerMillion * TOKENS_PER_TEXT * 1000) / 1_000_000).toFixed(4)} |`
      );
      console.log('ok');
      for (const m of misses) console.log(`    ${m}`);
    } catch (e) {
      rows.push(`| ${model.label} | — | — | — | — | — | — | — | — |`);
      console.log(`falló: ${e instanceof Error ? e.message : String(e)}`);
    }
  }

  console.log(`\n${rows.join('\n')}`);
}

void main();
