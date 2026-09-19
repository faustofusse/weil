import { createClient } from '@libsql/client/web';
import {
	RESPONSE_SCHEMA,
	authenticate,
	buildParts,
	callGemini,
	loadAccounts,
	loadPayeeHistory,
	modelOrderFor,
	normalizeCandidates,
	prompt,
	promptKind,
	sha256HexOf,
	type GeminiDebug,
	type ImportEnv,
	type PostableAccount
} from '../../../../../worker/src/import';
import type { RequestHandler } from './$types';

/**
 * The production import, replayed with the lid off.
 *
 * Same session cookie, same account list, same payee memory, same prompt,
 * same model order, same normalization — every function is imported from
 * `app/worker/src/import.ts`, not reimplemented — but nothing is stored: no
 * R2 put (this worker has no bucket binding at all) and no ledger write.
 * What it returns instead is everything `handleAnalyze` keeps to itself: the
 * built prompt, the context that shaped it, the literal request parts, the
 * model that answered, its token usage and its raw JSON.
 */

const ALLOWED = new Set([
	'application/pdf',
	'image/jpeg',
	'image/png',
	'image/webp',
	'image/heic',
	'image/heif',
	'text/csv'
]);

export const POST: RequestHandler = async ({ request, platform }) => {
	const env = platform?.env as unknown as ImportEnv;
	if (!env) return json({ error: 'no worker environment (run this on the deployed worker)' }, 500);

	const user = await authenticate(request, env);
	if (user instanceof Response) return user;

	const mimeType = ((request.headers.get('content-type') ?? '').split(';')[0] ?? '')
		.trim()
		.toLowerCase();
	if (!ALLOWED.has(mimeType)) return json({ error: `unsupported content type: ${mimeType}` }, 415);

	// The database token the browser already holds (session/refresh), forwarded
	// here instead of minting one with an org-wide Turso API token: the client
	// is signed in as this very user and the cookie check above proves it, so a
	// second, far more powerful credential on the worker buys nothing. The
	// production worker mints its own because it also runs without a browser
	// (email ingestion, WhatsApp).
	const jwt = request.headers.get('x-turso-token');
	if (!jwt) return json({ error: 'missing x-turso-token header' }, 400);

	const bytes = new Uint8Array(await request.arrayBuffer());
	if (bytes.length === 0) return json({ error: 'empty body' }, 400);

	const db = createClient({ url: `libsql://${user.turso_db_hostname}`, authToken: jwt });
	const queryUserDb = async (sql: string) => {
		const rs = await db.execute(sql);
		return rs.rows as unknown as Array<Record<string, unknown>>;
	};

	try {
		const accounts = await loadAccounts(queryUserDb).catch(() => [] as PostableAccount[]);
		const history = await loadPayeeHistory(queryUserDb, accounts).catch(() => []);
		const kind = promptKind(mimeType);
		const promptText = prompt(accounts, history, kind);

		const debug: GeminiDebug = {};
		const raw = await callGemini(modelOrderFor(env, mimeType), { mimeType, bytes }, promptText, debug);
		const transactions = normalizeCandidates(raw, accounts);

		// The file part verbatim for text, summarized for binary: a 4 MB PDF's
		// base64 helps nobody, its size and type do.
		const parts = buildParts({ mimeType, bytes }).map((part) =>
			'text' in part
				? { kind: 'text' as const, text: String(part.text) }
				: {
						kind: 'inline_data' as const,
						mimeType,
						bytes: bytes.length,
						base64Len: String(
							(part.inline_data as { data: string } | undefined)?.data.length ?? 0
						)
					}
		);

		return json({
			docSha256: await sha256HexOf(bytes),
			mimeType,
			bytes: bytes.length,
			kind,
			accounts,
			history,
			promptText,
			schema: RESPONSE_SCHEMA,
			request: { parts, generationConfig: { responseMimeType: 'application/json', temperature: 0.1 } },
			model: debug.model,
			viaGateway: debug.viaGateway,
			latencyMs: debug.latencyMs,
			usage: debug.usage,
			rawText: debug.rawText,
			rawTransactions: raw,
			transactions
		});
	} catch (e) {
		return json({ error: e instanceof Error ? e.message : 'analyze failed' }, 502);
	} finally {
		db.close();
	}
};

function json(payload: unknown, status = 200): Response {
	return new Response(JSON.stringify(payload), {
		status,
		headers: { 'content-type': 'application/json' }
	});
}
