import { ReadOnlyDb, loadFacts, type LedgerFact } from './db';
import { defaultPolicy, matchAll, type CandidateEvent, type Outcome } from './kotlin';

/**
 * The blocking step `ImportReviewScreen` runs before offering crear / asociar
 * / omitir: one window query around the candidates' dates, then the batch
 * matcher over the whole set (batching is what stops two identical rows from
 * claiming the same stored transaction).
 */
export async function reconcile(
	db: ReadOnlyDb,
	events: CandidateEvent[]
): Promise<{ facts: LedgerFact[]; outcomes: Outcome[]; window: { from: number; to: number } | null }> {
	if (events.length === 0) return { facts: [], outcomes: [], window: null };
	const policy = defaultPolicy();
	const windowMs = policy.dateWindowDays * 86_400_000;
	const dates = events.map((e) => e.date);
	const from = Math.min(...dates) - windowMs;
	const to = Math.max(...dates) + windowMs;
	const facts = await loadFacts(db, from, to);
	return { facts, outcomes: matchAll(events, facts, policy), window: { from, to } };
}

/** How the app would label the row, given the outcome. */
export function action(outcome: Outcome): 'crear' | 'asociar' | 'omitir' | 'revisar' {
	if (outcome.kind === 'none') return 'crear';
	if (outcome.kind === 'ambiguous') return 'revisar';
	return outcome.matches[0]?.relation === 'AlreadyImported' ? 'omitir' : 'asociar';
}
