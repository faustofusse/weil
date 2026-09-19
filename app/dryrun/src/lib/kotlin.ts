import { DryRun } from '$kotlin';
import type { AccountRow, EmailRow, LedgerFact, NotificationRow } from './db';

/**
 * Typed façade over the compiled Kotlin/JS bridge
 * (`app/sharedLogic/src/jsMain/kotlin/ar/fausto/weil/DryRunBridge.kt`).
 *
 * Everything crosses as JSON, so this file is only parse/stringify plus the
 * types. The logic it calls is literally the code the phone runs.
 */
const kt = DryRun.getInstance();

export type ImportSplit = {
	amountMinor: number;
	categoryAccountId?: string | null;
	categoryPath?: string | null;
};

export type ImportCandidate = {
	date: number;
	day?: string | null;
	payee: string;
	note?: string | null;
	commodity: string;
	direction: 'Expense' | 'Income' | 'Transfer';
	accountId?: string | null;
	accountPath?: string | null;
	counterAmountMinor?: number | null;
	counterCommodity?: string | null;
	splits: ImportSplit[];
};

export type IngestedMovement = {
	ruleId: string;
	source: 'Notification' | 'Email' | 'Document' | 'WhatsApp' | 'Manual';
	sourceRef: string;
	date: number;
	amountMinor: number;
	commodity: string;
	payee: string;
	direction: 'Expense' | 'Income' | 'Transfer';
	accountHints: string[];
};

export type InboxCandidate = {
	candidate: ImportCandidate;
	kind: 'Notification' | 'Email';
	ref: string;
	ruleId: string;
	title: string;
};

export type ScannedRow = {
	source: 'Notification' | 'Email';
	ref: string;
	title: string;
	body: string;
	movement?: IngestedMovement | null;
	known?: boolean;
};

export type CandidateEvent = {
	source: string;
	sourceRef?: string | null;
	ownAccountId?: string | null;
	amountMinor: number;
	commodity: string;
	date: number;
	rawPayee: string;
	direction: 'Expense' | 'Income' | 'Transfer';
};

export type MatchReason =
	| 'AlreadyImported'
	| 'SameAmount'
	| 'CloseAmount'
	| 'SameDay'
	| 'NearDay'
	| 'SamePayee'
	| 'SimilarPayee'
	| 'SameAccount'
	| 'OppositeAccount';

export type Match = {
	transactionId: string;
	payee: string;
	date: number;
	relation: 'AlreadyImported' | 'Duplicate' | 'Mirror';
	score: number;
	reasons: MatchReason[];
	retargetPostingId?: string | null;
};

export type Outcome = {
	kind: 'none' | 'confident' | 'ambiguous';
	eventKey: string;
	matches: Match[];
};

export type MatchPolicy = {
	dateWindowDays: number;
	amountToleranceMinor: number;
	amountToleranceBps: number;
	autoScore: number;
	reviewScore: number;
	decisiveMargin: number;
	authority: 'Incoming' | 'Stored';
};

export function defaultPolicy(): MatchPolicy {
	return JSON.parse(kt.defaultPolicyJson()) as MatchPolicy;
}

export function parseNotification(row: NotificationRow): IngestedMovement | null {
	const out = kt.parseNotificationJson(JSON.stringify(row));
	return out ? (JSON.parse(out) as IngestedMovement) : null;
}

export function parseEmail(row: EmailRow): IngestedMovement | null {
	const out = kt.parseEmailJson(JSON.stringify(row));
	return out ? (JSON.parse(out) as IngestedMovement) : null;
}

/** The body the rules actually read (quoted-printable decoded, tags stripped). */
export function emailPlainText(raw: string): string {
	return kt.emailPlainTextJs(raw);
}

export function decodeMimeHeader(raw: string): string {
	return kt.decodeMimeHeaderJs(raw);
}

export function parseMoney(raw: string): { amountMinor: number; commodity: string } | null {
	const out = kt.parseMoneyJson(raw);
	return out ? JSON.parse(out) : null;
}

export function accountPaths(accounts: AccountRow[]): Record<string, string> {
	return JSON.parse(kt.accountPathsJson(JSON.stringify(accounts)));
}

export function resolveAccountHint(
	hints: string[],
	commodity: string,
	accounts: AccountRow[]
): string | null {
	return kt.resolveAccountHintJson(JSON.stringify(hints), commodity, JSON.stringify(accounts)) ?? null;
}

export function buildInbox(
	notifications: NotificationRow[],
	emails: EmailRow[],
	accounts: AccountRow[],
	knownRefs: string[]
): { scanned: ScannedRow[]; inbox: InboxCandidate[] } {
	return JSON.parse(
		kt.buildInboxJson(
			JSON.stringify(notifications),
			JSON.stringify(emails),
			JSON.stringify(accounts),
			JSON.stringify(knownRefs)
		)
	);
}

export function candidateToEvent(
	candidate: ImportCandidate,
	sourceRef: string | null,
	fallbackOwnAccountId: string | null
): CandidateEvent {
	return JSON.parse(
		kt.candidateToEventJson(JSON.stringify(candidate), sourceRef, fallbackOwnAccountId)
	);
}

export function movementToEvent(
	movement: IngestedMovement,
	ownAccountId: string | null
): CandidateEvent {
	return JSON.parse(kt.movementToEventJson(JSON.stringify(movement), ownAccountId));
}

/** The batch matcher, exactly as `ImportReviewScreen` runs it. */
export function matchAll(
	events: CandidateEvent[],
	facts: LedgerFact[],
	policy?: MatchPolicy
): Outcome[] {
	return JSON.parse(
		kt.matchAllJson(
			JSON.stringify(events),
			JSON.stringify(facts),
			policy ? JSON.stringify(policy) : null
		)
	);
}
