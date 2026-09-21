/**
 * The category of a one-line chat message ("panadería 300" → `Comida`).
 *
 * The WhatsApp path reads the message itself with Gemini: the amount, the
 * direction, the payee and the accounts named come out of a prompt, because
 * reading "veinte mil pesos", "1.234,56" or "pasé 20k de galicia a mercado
 * pago" is a language problem and a regex that guesses at it is a heuristic
 * hiding in the plumbing.
 *
 * The category is the one part that is **not** a language problem: it is a
 * pick from the user's own tree, which is exactly the Choice the typing
 * screen already runs (suggest.ts). Asking it here buys two things a prompt
 * cannot: the answer is always an account that exists or an explicit
 * none-of-these, and the question is *the same object* the app uses while
 * typing — improve it once, both surfaces improve.
 *
 * It runs **in parallel** with the Gemini call, so it costs no latency at
 * all: it only needs the message text, not the direction, because both the
 * expense and the income question are asked and the caller keeps whichever
 * the direction turned out to be. Gemini's own category guess stays in the
 * prompt as the fallback for when this call fails.
 */
import { categoryQuestion, NO_MATCH, systemOne, type ChoiceAnswer, type SuggestEnv } from './suggest';

export interface ChatAccount {
  id: string;
  path: string;
  type: 'expense' | 'income' | 'asset' | 'liability';
  commodity?: string | null;
  label?: string;
}

const CONTEXT =
  'A one-line WhatsApp message an Argentine user sent to their own finance bot to record something they just spent or earned. It is Spanish, terse and informal ("super 12.500 con santander", "cobré 50000 sueldo", "panadería 300"). It is a finished sentence, not a half-typed field.';

/**
 * Both category questions, asked together and speculatively: the direction is
 * being decided by the other model at the same moment, so the one that turns
 * out not to apply is simply dropped. A transfer uses neither — its two legs
 * are accounts the user owns, and those come from the message reader.
 */
export function chatQuestions(accounts: ChatAccount[]): Record<string, unknown> {
  const paths = (type: ChatAccount['type']) =>
    accounts.filter((a) => a.type === type).map((a) => a.label || a.path);
  const asOptions = (list: string[]) => list.map((path) => ({ id: path, path }));
  const expense = paths('expense');
  const income = paths('income');

  const questions: Record<string, unknown> = {};
  if (expense.length > 0) {
    questions.category = categoryQuestion(
      { text: '', kind: 'expense', context: CONTEXT, options: [] },
      asOptions(expense)
    );
  }
  if (income.length > 0) {
    questions.source = categoryQuestion(
      { text: '', kind: 'income', context: CONTEXT, options: [] },
      asOptions(income)
    );
  }
  return questions;
}

export interface ChatCategories {
  /** Verbatim path (or label) to use when the message is an expense. */
  expense: string | null;
  /** Verbatim path (or label) to use when the message is income. */
  income: string | null;
}

/**
 * One System One call for both readings. Returns null when there is nothing
 * to ask (a tree with no categories) — callers keep whatever the message
 * reader came up with.
 */
export async function suggestCategories(
  env: SuggestEnv,
  accounts: ChatAccount[],
  text: string
): Promise<ChatCategories | null> {
  const questions = chatQuestions(accounts);
  if (Object.keys(questions).length === 0) return null;

  const answers = await systemOne(env, { message: text }, questions, env.AI, env.AI_GATEWAY);
  const pick = (key: string): string | null => {
    const choice = (answers[key] as ChoiceAnswer | undefined)?.choice;
    return !choice || choice === NO_MATCH ? null : choice;
  };
  return { expense: pick('category'), income: pick('source') };
}
