import { createClient, type Client } from '@libsql/client/web';
import type { Session } from './auth.svelte';

/**
 * Read-only access to the user's own Turso database, straight from the
 * browser with the session JWT (the same token the app uses).
 *
 * The queries here mirror the app's repositories one for one — see
 * `IngestRepository.inbox`, `TransactionsRepository.reconcileFacts` and
 * `knownSourceRefs` in sharedLogic — because the point of the inspector is to
 * feed the real Kotlin rules the real rows.
 *
 * The JWT is read/write, so the guard below is what keeps a dry run dry:
 * every statement must be a `select`, and anything else throws before it
 * reaches the network.
 */
export class ReadOnlyDb {
	private client: Client;

	constructor(session: Session) {
		this.client = createClient({ url: session.db_url, authToken: session.jwt });
	}

	async select(sql: string, args: unknown[] = []): Promise<Array<Record<string, unknown>>> {
		const verb = sql.trimStart().slice(0, 6).toLowerCase();
		if (verb !== 'select') throw new Error(`dry run is read-only, refused: ${sql.slice(0, 40)}…`);
		const rs = await this.client.execute({ sql, args: args as never });
		return rs.rows as unknown as Array<Record<string, unknown>>;
	}

	close() {
		this.client.close();
	}
}

export type AccountRow = {
	id: string;
	name: string;
	parentId: string | null;
	/** Serialized the way the Kotlin `AccountType` enum names its entries. */
	type: 'Asset' | 'Liability' | 'Income' | 'Expense' | 'Equity';
	inNetWorth: boolean;
};

const TYPES: Record<string, AccountRow['type']> = {
	asset: 'Asset',
	liability: 'Liability',
	income: 'Income',
	expense: 'Expense',
	equity: 'Equity'
};

/** Every account, in the shape the Kotlin bridge deserializes. */
export async function loadAccountRows(db: ReadOnlyDb): Promise<AccountRow[]> {
	const rows = await db.select('select id, name, parent_id, type, in_net_worth from accounts');
	return rows.flatMap((r) => {
		const type = TYPES[String(r.type ?? '').toLowerCase()];
		if (!type) return [];
		return [
			{
				id: String(r.id),
				name: String(r.name),
				parentId: r.parent_id == null ? null : String(r.parent_id),
				type,
				inNetWorth: Number(r.in_net_worth ?? 1) !== 0
			}
		];
	});
}

export type NotificationRow = {
	id: string;
	packageName: string;
	title: string;
	text: string;
	postTime: number;
};

export type EmailRow = {
	id: string;
	fromEmail: string;
	subject: string | null;
	bodyText: string | null;
	bodyHtml: string | null;
	receivedAt: number;
};

/** Same 30-day window `IngestRepository.inbox` scans. */
export async function loadNotifications(
	db: ReadOnlyDb,
	since: number,
	limit = 400
): Promise<NotificationRow[]> {
	const rows = await db.select(
		`select id, package_name, title, text, post_time from notifications
		 where post_time >= ? order by post_time desc limit ?`,
		[since, limit]
	);
	return rows.map((r) => ({
		id: String(r.id),
		packageName: String(r.package_name),
		title: String(r.title ?? ''),
		text: String(r.text ?? ''),
		postTime: Number(r.post_time)
	}));
}

export async function loadEmails(db: ReadOnlyDb, since: number, limit = 400): Promise<EmailRow[]> {
	const rows = await db.select(
		`select id, from_email, subject, body_text, body_html, received_at from emails
		 where received_at >= ? order by received_at desc limit ?`,
		[since, limit]
	);
	return rows.map((r) => ({
		id: String(r.id),
		fromEmail: String(r.from_email),
		subject: r.subject == null ? null : String(r.subject),
		bodyText: r.body_text == null ? null : String(r.body_text),
		bodyHtml: r.body_html == null ? null : String(r.body_html),
		receivedAt: Number(r.received_at)
	}));
}

/**
 * One row of the message browser. Notifications and emails are different
 * tables with different columns, but the thing being looked at is the same —
 * "what did this device capture, and when" — so they share a shape and the
 * page can show them side by side.
 */
export type Message = {
	kind: 'notification' | 'email';
	id: string;
	/** Package name or sender address: what the rules scope themselves by. */
	origin: string;
	/** App label when known (the `applications` table), else the raw origin. */
	originLabel: string;
	title: string;
	/**
	 * First 200 characters only. A mail's `body_html` runs to tens of kB and a
	 * page of 50 of them is megabytes over the wire for text nobody reads until
	 * a row is expanded — which is what made this page slow. The full body
	 * comes from [loadBody] on demand.
	 */
	snippet: string;
	/** Sizes of the stored parts, so truncation is visible without loading them. */
	textLength: number;
	htmlLength: number;
	date: number;
	category: string | null;
};

/** Full payload of one row, fetched when the user opens it. */
export type MessageBody = { text: string; html: string | null };

export type Facet = { origin: string; label: string; count: number };

export type BrowseQuery = {
	since: number;
	search: string;
	origins: string[];
	limit: number;
	/**
	 * Keyset cursor: the date of the last row already shown. Paging by
	 * `where date < cursor` instead of `offset` keeps every page the same cost
	 * on a table with tens of thousands of rows, and both tables are already
	 * indexed on the date descending.
	 */
	before?: number;
};

export type Page = { rows: Message[]; more: boolean };

/** `applications.id` is the package name; the label is what the user reads. */
export async function loadAppLabels(db: ReadOnlyDb): Promise<Map<string, string>> {
	const rows = await db.select('select id, name from applications');
	return new Map(rows.map((r) => [String(r.id), String(r.name || r.id)]));
}

function like(search: string): string {
	return `%${search.trim().toLowerCase()}%`;
}

export async function browseNotifications(
	db: ReadOnlyDb,
	q: BrowseQuery,
	labels: Map<string, string>
): Promise<Page> {
	const where = ['post_time >= ?'];
	const args: unknown[] = [q.since];
	if (q.before != null) {
		where.push('post_time < ?');
		args.push(q.before);
	}
	if (q.origins.length) {
		where.push(`package_name in (${q.origins.map(() => '?').join(',')})`);
		args.push(...q.origins);
	}
	if (q.search.trim()) {
		where.push('(lower(title) like ? or lower(text) like ? or lower(package_name) like ?)');
		args.push(like(q.search), like(q.search), like(q.search));
	}
	// One row over the page size is how the button knows whether to appear,
	// without a second count(*) over the same scan.
	const rows = await db.select(
		`select id, package_name, title, substr(text, 1, 200) as snippet, length(text) as text_len,
		        category, post_time from notifications
		 where ${where.join(' and ')} order by post_time desc limit ?`,
		[...args, q.limit + 1]
	);
	const more = rows.length > q.limit;
	return {
		more,
		rows: rows.slice(0, q.limit).map((r) => {
			const origin = String(r.package_name);
			return {
				kind: 'notification' as const,
				id: String(r.id),
				origin,
				originLabel: labels.get(origin) ?? origin,
				title: String(r.title ?? ''),
				snippet: String(r.snippet ?? ''),
				textLength: Number(r.text_len ?? 0),
				htmlLength: 0,
				date: Number(r.post_time),
				category: r.category == null ? null : String(r.category)
			};
		})
	};
}

export async function browseEmails(db: ReadOnlyDb, q: BrowseQuery): Promise<Page> {
	const where = ['received_at >= ?'];
	const args: unknown[] = [q.since];
	if (q.before != null) {
		where.push('received_at < ?');
		args.push(q.before);
	}
	if (q.origins.length) {
		where.push(`from_email in (${q.origins.map(() => '?').join(',')})`);
		args.push(...q.origins);
	}
	if (q.search.trim()) {
		where.push('(lower(subject) like ? or lower(body_text) like ? or lower(from_email) like ?)');
		args.push(like(q.search), like(q.search), like(q.search));
	}
	const rows = await db.select(
		`select id, from_email, subject, substr(body_text, 1, 200) as snippet,
		        length(body_text) as text_len, length(body_html) as html_len, received_at from emails
		 where ${where.join(' and ')} order by received_at desc limit ?`,
		[...args, q.limit + 1]
	);
	const more = rows.length > q.limit;
	return {
		more,
		rows: rows.slice(0, q.limit).map((r) => ({
			kind: 'email' as const,
			id: String(r.id),
			origin: String(r.from_email),
			originLabel: String(r.from_email),
			title: String(r.subject ?? '(sin asunto)'),
			snippet: String(r.snippet ?? ''),
			textLength: Number(r.text_len ?? 0),
			htmlLength: Number(r.html_len ?? 0),
			date: Number(r.received_at),
			category: null
		}))
	};
}

export type SimilarCandidate = {
	id: string;
	title: string;
	text: string;
	date: number;
};

/**
 * Candidate pool for "find similar": every message from the same app, over
 * the whole history rather than the browsing window, because the point is to
 * see how often a template repeats. Bodies are capped — a template is
 * recognizable well before 400 characters, and this pulls hundreds of rows.
 */
export async function loadSimilarPool(
	db: ReadOnlyDb,
	packageName: string,
	limit = 800
): Promise<SimilarCandidate[]> {
	const rows = await db.select(
		`select id, title, substr(text, 1, 400) as text, post_time from notifications
		 where package_name = ? order by post_time desc limit ?`,
		[packageName, limit]
	);
	return rows.map((r) => ({
		id: String(r.id),
		title: String(r.title ?? ''),
		text: String(r.text ?? ''),
		date: Number(r.post_time)
	}));
}

/** The parts the list deliberately left behind, for one row. */
export async function loadBody(
	db: ReadOnlyDb,
	kind: Message['kind'],
	id: string
): Promise<MessageBody> {
	if (kind === 'notification') {
		const rows = await db.select('select text from notifications where id = ?', [id]);
		return { text: String(rows[0]?.text ?? ''), html: null };
	}
	const rows = await db.select('select body_text, body_html from emails where id = ?', [id]);
	const row = rows[0];
	return {
		text: String(row?.body_text ?? ''),
		html: row?.body_html == null ? null : String(row.body_html)
	};
}

/** Who sends the most, within the window: the filter list, ordered by volume. */
export async function notificationFacets(
	db: ReadOnlyDb,
	since: number,
	labels: Map<string, string>
): Promise<Facet[]> {
	const rows = await db.select(
		`select package_name as origin, count(*) as n from notifications
		 where post_time >= ? group by package_name order by n desc`,
		[since]
	);
	return rows.map((r) => ({
		origin: String(r.origin),
		label: labels.get(String(r.origin)) ?? String(r.origin),
		count: Number(r.n)
	}));
}

export async function emailFacets(db: ReadOnlyDb, since: number): Promise<Facet[]> {
	const rows = await db.select(
		`select from_email as origin, count(*) as n from emails
		 where received_at >= ? group by from_email order by n desc`,
		[since]
	);
	return rows.map((r) => ({
		origin: String(r.origin),
		label: String(r.origin),
		count: Number(r.n)
	}));
}

export type FactLeg = {
	postingId: string;
	accountId: string;
	type: AccountRow['type'] | null;
	amountMinor: number;
	commodity: string;
};

export type LedgerFact = {
	transactionId: string;
	date: number;
	payee: string;
	legs: FactLeg[];
	eventKeys: string[];
	sourceRefs: string[];
};

/** Port of `TransactionsRepository.reconcileFacts(from, to)`. */
export async function loadFacts(db: ReadOnlyDb, from: number, to: number): Promise<LedgerFact[]> {
	const legs = await db.select(
		`select t.id as tx, t.date as date, t.payee as payee, p.id as posting,
		        p.account_id as account, a.type as type, p.amount_minor as amount, p.commodity as commodity
		 from ledger_transactions t
		 join postings p on p.transaction_id = t.id
		 left join accounts a on a.id = p.account_id
		 where t.date >= ? and t.date <= ?`,
		[from, to]
	);
	const byTx = new Map<string, LedgerFact>();
	for (const r of legs) {
		const txId = String(r.tx);
		const fact = byTx.get(txId) ?? {
			transactionId: txId,
			date: Number(r.date),
			payee: String(r.payee ?? ''),
			legs: [],
			eventKeys: [],
			sourceRefs: []
		};
		fact.legs.push({
			postingId: String(r.posting),
			accountId: String(r.account),
			type: TYPES[String(r.type ?? '').toLowerCase()] ?? null,
			amountMinor: Number(r.amount),
			commodity: String(r.commodity)
		});
		byTx.set(txId, fact);
	}
	if (byTx.size === 0) return [];

	const ids = [...byTx.keys()];
	const sources = await db.select(
		`select transaction_id, ref, event_key from transaction_sources
		 where transaction_id in (${ids.map(() => '?').join(',')})`,
		ids
	);
	for (const r of sources) {
		const fact = byTx.get(String(r.transaction_id));
		if (!fact) continue;
		if (r.ref != null) fact.sourceRefs.push(String(r.ref));
		if (r.event_key != null) fact.eventKeys.push(String(r.event_key));
	}
	return [...byTx.values()];
}

/** Port of `TransactionsRepository.knownSourceRefs(refs)`. */
export async function knownSourceRefs(db: ReadOnlyDb, refs: string[]): Promise<string[]> {
	if (refs.length === 0) return [];
	const rows = await db.select(
		`select ref from transaction_sources where ref in (${refs.map(() => '?').join(',')})`,
		refs
	);
	return rows.map((r) => String(r.ref));
}
