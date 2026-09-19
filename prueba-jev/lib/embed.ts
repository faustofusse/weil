/**
 * Embedding providers, behind one function.
 *
 * `openai` is the intended one. `gemini` exists because the worker already
 * holds a Google key (`GEMINI_API_KEY`) and the import path already speaks to
 * that API, so switching costs one env var rather than an account. `hash` is a
 * deterministic local bag-of-words vector: it is **not** semantic and must
 * never reach the real database — it is there so the SQL path (`vector32`,
 * `vector_distance_cos`, the search script) can be exercised end to end
 * without spending a cent or waiting on a key.
 */
import { EMBED_DIMS, EMBED_MODEL } from './db';

export type Provider = 'openai' | 'gemini' | 'hash';

export function providerFrom(argv: string[]): Provider {
  const i = argv.indexOf('--provider');
  const value = i > 0 ? argv[i + 1] : process.env.EMBED_PROVIDER;
  if (value === 'gemini' || value === 'hash' || value === 'openai') return value;
  return 'openai';
}

/** What goes in `embedding_model`, so rows from different providers are never
 * compared against each other by accident. */
export function tagFor(provider: Provider): string {
  const model = provider === 'openai' ? EMBED_MODEL
    : provider === 'gemini' ? 'gemini-embedding-001'
      : 'hash-bow';
  return `${model}/${EMBED_DIMS}`;
}

export async function embedAll(texts: string[], provider: Provider): Promise<number[][]> {
  if (texts.length === 0) return [];
  if (provider === 'hash') return texts.map(hashEmbed);
  if (provider === 'gemini') return geminiEmbed(texts);
  return openaiEmbed(texts);
}

export async function embedOne(text: string, provider: Provider): Promise<number[]> {
  return (await embedAll([text], provider))[0]!;
}

async function openaiEmbed(texts: string[]): Promise<number[][]> {
  const key = need('OPENAI_API_KEY');
  const res = await fetch('https://api.openai.com/v1/embeddings', {
    method: 'POST',
    headers: { authorization: `Bearer ${key}`, 'content-type': 'application/json' },
    body: JSON.stringify({ model: EMBED_MODEL, dimensions: EMBED_DIMS, input: texts }),
  });
  if (!res.ok) throw new Error(`openai ${res.status}: ${(await res.text()).slice(0, 300)}`);
  const json = (await res.json()) as { data: Array<{ index: number; embedding: number[] }> };
  // The API does not promise response order; `index` does.
  const out: number[][] = new Array(texts.length);
  for (const d of json.data) out[d.index] = d.embedding;
  return out;
}

async function geminiEmbed(texts: string[]): Promise<number[][]> {
  const key = need('GEMINI_API_KEY');
  const res = await fetch(
    'https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:batchEmbedContents',
    {
      method: 'POST',
      headers: { 'x-goog-api-key': key, 'content-type': 'application/json' },
      body: JSON.stringify({
        requests: texts.map((text) => ({
          model: 'models/gemini-embedding-001',
          content: { parts: [{ text }] },
          outputDimensionality: EMBED_DIMS,
        })),
      }),
    },
  );
  if (!res.ok) throw new Error(`gemini ${res.status}: ${(await res.text()).slice(0, 300)}`);
  const json = (await res.json()) as { embeddings: Array<{ values: number[] }> };
  // Truncating to a smaller dimensionality denormalizes the vector, and Google
  // says to renormalize; cosine distance cares.
  return json.embeddings.map((e) => normalize(e.values));
}

/** Deterministic, local, non-semantic: hashed token counts, L2-normalized. */
function hashEmbed(text: string): number[] {
  const vec = new Array(EMBED_DIMS).fill(0);
  for (const token of text.toLowerCase().split(/[^\p{L}\p{N}$]+/u)) {
    if (!token) continue;
    let h = 2166136261;
    for (let i = 0; i < token.length; i++) {
      h = Math.imul(h ^ token.charCodeAt(i), 16777619) >>> 0;
    }
    vec[h % EMBED_DIMS]! += 1;
  }
  return normalize(vec);
}

function normalize(v: number[]): number[] {
  const norm = Math.hypot(...v) || 1;
  return v.map((x) => x / norm);
}

function need(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`missing ${name} in prueba-jev/.env`);
  return value;
}
