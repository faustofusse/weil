/**
 * Text → vector, for the app's "parecidos a este" search.
 *
 * The app cannot hold the Google key, so the embedding round trip goes through
 * here; everything else stays on the device. In particular this endpoint
 * **does not write** to the user's Turso database — it answers with vectors
 * and the app stores them, so the device remains the only writer of those
 * tables (which is what keeps its undo and transaction boundaries honest).
 */

export interface EmbedEnv {
  ACCOUNT_ID: string;
  AI_GATEWAY: string;
  GEMINI_API_KEY: string;
}

/** Must match `EMBEDDING_MODEL` / `EMBEDDING_DIMS` in EmbeddingsRepository.kt. */
export const EMBED_MODEL = 'gemini-embedding-001';
export const EMBED_DIMS = 512;

/** One request's worth of texts; the app already batches to ~100. */
const MAX_TEXTS = 128;
/** Past this a receipt is boilerplate, and the per-request budget is real. */
const MAX_CHARS = 4000;

export interface EmbedResponse {
  model: string;
  dims: number;
  vectors: number[][];
}

/**
 * Truncating to a smaller `outputDimensionality` denormalizes the vector and
 * Google says to renormalize; cosine distance notices (the app compares raw
 * `vector_distance_cos`, which assumes unit length to behave like 1 - cos).
 */
function normalize(v: number[]): number[] {
  let sum = 0;
  for (const x of v) sum += x * x;
  const norm = Math.sqrt(sum) || 1;
  return v.map((x) => x / norm);
}

/**
 * batchEmbedContents through the account's AI Gateway when one exists, falling
 * back to Google directly on the gateway's own errors — same shape as
 * `geminiJson` in import.ts, minus the model fallback list (there is exactly
 * one embedding model, and a different one would invalidate every stored
 * vector).
 */
export async function embedTexts(env: EmbedEnv, texts: string[]): Promise<number[][]> {
  if (texts.length === 0) return [];
  const path = `v1beta/models/${EMBED_MODEL}:batchEmbedContents`;
  const headers = { 'x-goog-api-key': env.GEMINI_API_KEY, 'content-type': 'application/json' };
  const body = JSON.stringify({
    requests: texts.map((text) => ({
      model: `models/${EMBED_MODEL}`,
      content: { parts: [{ text: text.slice(0, MAX_CHARS) }] },
      outputDimensionality: EMBED_DIMS,
    })),
  });

  const direct = () =>
    fetch(`https://generativelanguage.googleapis.com/${path}`, { method: 'POST', headers, body });

  let res: Response;
  if (env.AI_GATEWAY && env.ACCOUNT_ID) {
    res = await fetch(
      `https://gateway.ai.cloudflare.com/v1/${env.ACCOUNT_ID}/${env.AI_GATEWAY}/google-ai-studio/${path}`,
      { method: 'POST', headers, body }
    );
    if (!res.ok && (res.status === 524 || (await res.clone().text()).includes('AiGatewayError'))) {
      res = await direct();
    }
  } else {
    res = await direct();
  }
  if (!res.ok) throw new Error(`embed: ${res.status} ${(await res.text()).slice(0, 300)}`);

  const data = (await res.json()) as { embeddings?: Array<{ values: number[] }> };
  const embeddings = data.embeddings ?? [];
  if (embeddings.length !== texts.length) {
    throw new Error(`embed: asked ${texts.length} texts, got ${embeddings.length} vectors`);
  }
  return embeddings.map((e) => normalize(e.values));
}

/** `POST /embed` — `{ texts: string[] }` → `{ model, dims, vectors }`. */
export async function handleEmbed(request: Request, env: EmbedEnv): Promise<Response> {
  let payload: { texts?: unknown };
  try {
    payload = (await request.json()) as { texts?: unknown };
  } catch {
    return jsonResponse({ error: 'invalid json body' }, 400);
  }
  const texts = payload.texts;
  if (!Array.isArray(texts) || texts.some((t) => typeof t !== 'string')) {
    return jsonResponse({ error: 'texts must be an array of strings' }, 400);
  }
  if (texts.length > MAX_TEXTS) {
    return jsonResponse({ error: `at most ${MAX_TEXTS} texts per request` }, 400);
  }
  const vectors = await embedTexts(env, texts as string[]);
  return jsonResponse({ model: `${EMBED_MODEL}/${EMBED_DIMS}`, dims: EMBED_DIMS, vectors });
}

function jsonResponse(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}
