/**
 * Grouping captured notifications by template.
 *
 * Bank notifications are templates with holes in them: "Compraste $8.000 en
 * COTO" and "Compraste $15.230,50 en YPF" are the same message as far as
 * writing a rule in `Ingest.kt` goes. So instead of comparing texts, we
 * compare their *signature* — the text with every hole masked — which turns
 * "find similar" into "find the ones printed from the same template".
 *
 * No embeddings and no model call: the answer has to be stable and instant,
 * and the model comes later, fed with the JSON of the group this produces.
 */

const ACCENTS = /[\u0300-\u036f]/g;

/** Money, dates, times, card tails, long codes: everything that varies. */
export function signature(text: string): string {
	return text
		.normalize('NFD')
		.replace(ACCENTS, '')
		.toLowerCase()
		.replace(/\b[0-9]{1,2}[/-][0-9]{1,2}([/-][0-9]{2,4})?\b/g, '#/#')
		.replace(/\b[0-9]{1,2}:[0-9]{2}(:[0-9]{2})?\s*(hs?|am|pm)?\b/g, '#:#')
		.replace(/(u\$s|us\$|ars|\$)\s*[0-9][0-9.,]*/g, '$#')
		.replace(/\b[0-9][0-9.,]*\b/g, '#')
		// Mixed letter+digit runs (auth codes, ids) collapse to one token.
		.replace(/\b(?=[a-z0-9]*[0-9])(?=[a-z0-9]*[a-z])[a-z0-9]{4,}\b/g, 'x')
		.replace(/\s+/g, ' ')
		.trim();
}

function tokens(sig: string): Set<string> {
	return new Set(sig.split(/[^a-z0-9#$/:]+/).filter((t) => t.length > 1));
}

/** Sørensen–Dice over signature tokens: 1 is the same template. */
export function similarity(a: string, b: string): number {
	if (a === b) return 1;
	const ta = tokens(a);
	const tb = tokens(b);
	if (ta.size === 0 || tb.size === 0) return 0;
	let shared = 0;
	for (const t of ta) if (tb.has(t)) shared++;
	return (2 * shared) / (ta.size + tb.size);
}

export type Scored<T> = { item: T; score: number; sameTemplate: boolean };

/**
 * Rank candidates against one message. `sameTemplate` is the exact-signature
 * case, which is the one worth acting on: those rows are guaranteed to parse
 * identically, so one rule covers all of them.
 */
export function rankSimilar<T extends { id: string; text: string }>(
	target: { id: string; text: string },
	candidates: T[],
	min = 0.45
): Array<Scored<T>> {
	const targetSig = signature(target.text);
	return candidates
		.filter((c) => c.id !== target.id)
		.map((item) => {
			const sig = signature(item.text);
			return { item, score: similarity(targetSig, sig), sameTemplate: sig === targetSig };
		})
		.filter((s) => s.score >= min)
		.sort((a, b) => Number(b.sameTemplate) - Number(a.sameTemplate) || b.score - a.score);
}
