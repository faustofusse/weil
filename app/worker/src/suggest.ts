/**
 * Live category guess for the quick-entry screen: the user types "milanesas
 * del sábado" and the category picker moves to `Comida` before they reach the
 * record button.
 *
 * This is a **Choice** over the user's own account paths (TypeSafe / Jev), not
 * a generation: the model can only answer with a category that already exists
 * in the tree, plus an explicit none-of-these. So there is nothing to validate
 * on the way back beyond looking the path up in the list the app sent.
 *
 * The app sends the candidate list rather than the worker reading Turso: this
 * runs on a keystroke debounce, the device already holds the tree, and a
 * per-call database read would double the latency for nothing. The endpoint is
 * still session-authed — it spends the TypeSafe key.
 */

export interface SuggestEnv {
  TYPESAFE_API_KEY: string;
}

/** `jev-latest` is the flagship System One model. */
const MODEL = 'jev-latest';
const ENDPOINT = 'https://api.typesafe.ai/v1/systemone';

/** A Choice accepts up to 255 options; a real tree is far below this. */
const MAX_OPTIONS = 200;
/** Anything longer is not a description, it is a paste. */
const MAX_TEXT = 300;

/**
 * The none-of-these option. Deliberately not "Otros": the user may well have
 * an account called exactly that, and then the sentinel and a real category
 * would be the same key. Spelled as a sentence so the model reads it as an
 * escape hatch and not as a category named "__none__".
 */
export const NO_MATCH = 'Ninguna de estas categorías';

export interface SuggestOption {
  id: string;
  path: string;
}

export interface SuggestBody {
  /** What the user has typed so far (the description field). */
  text: string;
  /** 'expense' | 'income' — decides how the question is phrased. */
  kind?: string;
  /** Formatted amount ("ARS 3.500"), when the user already typed one. */
  amount?: string;
  /**
   * Where the text came from, when it is not the quick-entry field. The chat
   * reader (chat.ts) reuses this question over a WhatsApp line, which is the
   * same judgement over prose of a different shape.
   */
  context?: string;
  options: SuggestOption[];
}

export interface ChoiceAnswer {
  type: 'choice';
  choice: string;
  probabilities: Record<string, number>;
  confidence: number;
}

export interface NoulAnswer {
  type: 'noul';
  noul: number;
}

export type SystemOneAnswer = ChoiceAnswer | NoulAnswer;

/**
 * One System One call. Every question in `questions` is answered in the same
 * round trip and cannot see the others' answers, which is the point: asking
 * six narrow questions together costs roughly one call, so speculative ones
 * ("if this were a transfer, where did the money land?") are nearly free and
 * the caller simply ignores the branches that did not apply.
 *
 * Throws on any non-2xx: callers decide whether that means "no suggestion"
 * (the typing path) or "fall back to the other model" (the chat path).
 */
export async function systemOne(
  env: SuggestEnv,
  state: Record<string, unknown>,
  questions: Record<string, unknown>
): Promise<Record<string, SystemOneAnswer>> {
  const res = await fetch(ENDPOINT, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${env.TYPESAFE_API_KEY}`,
      'content-type': 'application/json',
    },
    body: JSON.stringify({ model: MODEL, state, questions }),
  });
  if (!res.ok) {
    throw new Error(`typesafe: ${res.status} ${(await res.text()).slice(0, 200)}`);
  }
  const data = (await res.json()) as { answers: Record<string, SystemOneAnswer> };
  return data.answers;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

/**
 * The question. Names and descriptions of the options are both sent to the
 * model, so the paths carry their own meaning ("Gastos:Comida:Verduras") and
 * need no rubric; what does need spelling out is that the text is *being
 * typed* — half a word is normal here and is not a reason to guess wildly.
 */
export function categoryQuestion(body: SuggestBody, options: SuggestOption[]) {
  const income = body.kind === 'income';
  const criteria: Record<string, string | null> = {};
  for (const option of options) criteria[option.path] = null;
  criteria[NO_MATCH] = income
    ? 'The text names no recognizable source of money, or none of the accounts above plausibly covers it. Also the right answer while the text is still too short to mean anything.'
    : 'The text names no recognizable good, service or merchant, or none of the categories above plausibly covers it. Also the right answer while the text is still too short to mean anything.';

  return {
    type: 'choice' as const,
    instructions: {
      question: income
        ? "Which of the user's income accounts is this money coming from?"
        : "Which of the user's expense categories does this purchase belong to?",
      context:
        body.context ??
        'A short free-form description the user is typing into a personal finance app in Argentina. It is in Spanish, informal, often two or three words, and frequently unfinished because they are still typing.',
      focus: income
        ? 'who or what paid: an employer, a client, a refund, interest, a gift.'
        : 'the good or service bought, or the merchant named. An Argentine merchant name implies its trade (Coto and Dia are supermarkets, "el super" is any supermarket, Rappi is delivery, YPF is fuel, Farmacity is a pharmacy, Edesur and Metrogas and Aysa are utilities).',
      not_for:
        'Do not read the payment method as the category: "con mercado pago" says how it was paid, not what was bought.',
    },
    criteria,
  };
}

/**
 * One Choice, one call. No fallback model and no retry: this runs while the
 * user types, and a suggestion that arrives late is worse than none — the app
 * treats any failure as "no suggestion" and keeps whatever is in the picker.
 */
export async function handleSuggestCategory(request: Request, env: SuggestEnv): Promise<Response> {
  if (!env.TYPESAFE_API_KEY) return json({ error: 'suggestions not configured' }, 501);

  const body = (await request.json()) as SuggestBody;
  const text = (body.text ?? '').trim().slice(0, MAX_TEXT);
  const options = (body.options ?? []).filter((o) => o?.id && o?.path).slice(0, MAX_OPTIONS);
  if (!text || options.length === 0) return json({ accountId: null, confidence: 0 });

  let answers: Record<string, SystemOneAnswer>;
  try {
    answers = await systemOne(
      env,
      { description: text, ...(body.amount ? { amount: body.amount } : {}) },
      { category: categoryQuestion(body, options) }
    );
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : 'typesafe failed' }, 502);
  }
  const answer = answers.category as ChoiceAnswer;
  const picked = options.find((o) => o.path === answer.choice);

  // Probabilities go back too: the picked one is what the UI acts on, but the
  // runner-up is what makes a "¿o …?" affordance possible later without a
  // second call. Trimmed to the options that actually carry weight.
  const ranked = Object.entries(answer.probabilities ?? {})
    .filter(([, p]) => p >= 0.05)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 4)
    .map(([path, p]) => ({
      accountId: options.find((o) => o.path === path)?.id ?? null,
      path,
      probability: p,
    }));

  return json({
    // null when the model picked the none-of-these option: the caller must be
    // able to tell "no category fits" from "the call failed".
    accountId: picked?.id ?? null,
    path: picked?.path ?? null,
    confidence: answer.confidence ?? 0,
    ranked,
  });
}
