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
  options: SuggestOption[];
}

interface ChoiceAnswer {
  type: 'choice';
  choice: string;
  probabilities: Record<string, number>;
  confidence: number;
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
        'A short free-form description the user is typing into a personal finance app in Argentina. It is in Spanish, informal, often two or three words, and frequently unfinished because they are still typing.',
      focus: income
        ? 'who or what paid: an employer, a client, a refund, interest, a gift.'
        : 'the good or service bought, or the merchant named. An Argentine merchant name implies its trade (Coto and Dia are supermarkets, Rappi is delivery, YPF is fuel, Farmacity is a pharmacy).',
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

  const res = await fetch(ENDPOINT, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${env.TYPESAFE_API_KEY}`,
      'content-type': 'application/json',
    },
    body: JSON.stringify({
      model: MODEL,
      state: {
        description: text,
        ...(body.amount ? { amount: body.amount } : {}),
      },
      questions: { category: categoryQuestion(body, options) },
    }),
  });

  if (!res.ok) {
    return json({ error: `typesafe: ${res.status} ${(await res.text()).slice(0, 200)}` }, 502);
  }

  const data = (await res.json()) as { answers: { category: ChoiceAnswer } };
  const answer = data.answers.category;
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
