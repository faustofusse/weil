/**
 * Reading a captured message (a bank push notification, tomorrow a receipt
 * email) as a movement: amount, direction, counterparty, which account it
 * moved through.
 *
 * This is the **language** half of the notification→transaction path, and it
 * is Gemini's for the same reason the WhatsApp reader's is (see chat.ts):
 * "$ 12.400", "12.400 ARS", "U$S100,00" and "se debitaron doce mil quinientos"
 * are prose, and a regex that decides whether 12.500 is twelve thousand or
 * twelve-fifty is a heuristic hiding in the plumbing. The **decision** half —
 * which of the user's accounts and categories this belongs to — is a Choice
 * over closed lists and lives in suggest.ts.
 *
 * Nothing here reads or writes the user's database: the device sends its own
 * account list, exactly like /suggest/category.
 */
import { geminiJson, json, type GeminiDebug, type ImportEnv } from './import';

/** One account the reader may name, verbatim, in `account`. */
export interface MessageAccount {
  id: string;
  path: string;
  type: 'expense' | 'income' | 'asset' | 'liability';
  commodity?: string | null;
  label?: string;
}

export interface MessageBody {
  /** Where it came from: the app that posted it, or the sender of the mail. */
  origin?: string;
  title?: string;
  text?: string;
  /** Epoch ms of the message itself; dates in the text are relative to it. */
  when?: number;
  accounts?: MessageAccount[];
}

export interface ReadMessage {
  /** False when the message is not a movement: a promo, a reminder, a login alert. */
  isMovement: boolean;
  direction: 'expense' | 'income' | 'transfer';
  payee: string;
  /** Positive decimal, '.' as the decimal separator. Empty when not stated. */
  amount: string;
  commodity: string;
  /** The user's own account, verbatim from the list, or null. */
  account: string | null;
  note: string | null;
  /**
   * The same movement said plainly, without the bank's template: "compra en
   * Coto con Mercado Pago". This is what gets embedded to look for
   * precedents, so that the search compares *purchases* and not the
   * boilerplate every alert from that bank shares.
   */
  normalized: string;
}

const SCHEMA = {
  type: 'OBJECT',
  properties: {
    isMovement: {
      type: 'BOOLEAN',
      description:
        "True only when money already moved in or out of the recipient's own accounts. False for promotions, discounts, offers, reminders, due-date warnings, balance updates, login or security alerts, and amounts someone else moved.",
    },
    direction: { type: 'STRING', enum: ['expense', 'income', 'transfer'] },
    payee: {
      type: 'STRING',
      description:
        "Merchant, person or institution on the other side, cleaned up and capitalised ('COTO CICSA 4821' → 'Coto'). Never the amount, never the bank sending the alert unless the bank itself charged the fee.",
    },
    amount: {
      type: 'STRING',
      description: "Positive decimal with '.' as the decimal separator, e.g. '12400.00'. Empty string when the message states no amount.",
    },
    commodity: { type: 'STRING', description: "Currency code: 'ARS' unless the message says otherwise ('U$S', 'USD', 'dólares' → USD)." },
    account: {
      type: 'STRING',
      nullable: true,
      description:
        "The user's own account or payment method the money moved through, verbatim from the list given. Infer it: the sender names the bank or wallet and the currency picks between that sender's accounts. Null only when even that leaves it open.",
    },
    note: { type: 'STRING', nullable: true, description: 'Extra detail worth keeping (instalments, card last digits), else null.' },
    normalized: {
      type: 'STRING',
      description:
        "One short Spanish sentence describing the movement without the bank's boilerplate: 'compra en Coto con Mercado Pago', 'transferencia recibida de Juan Pérez'. No amounts, no dates.",
    },
  },
  required: ['isMovement', 'direction', 'payee', 'amount', 'commodity', 'normalized'],
} as const;

export function messagePrompt(body: MessageBody): string {
  const accounts = body.accounts ?? [];
  const paths = (type: MessageAccount['type']) =>
    accounts.filter((a) => a.type === type).map((a) => a.label || a.path);
  const own = [...paths('asset'), ...paths('liability')];
  const when = new Date(body.when || Date.now()).toISOString().slice(0, 16).replace('T', ' ');

  return [
    'You read one message an Argentine user received and decide whether it reports a movement of their own money.',
    'It is typically a bank or wallet push notification, in Spanish, written from a template.',
    `The message arrived at ${when} (UTC).`,
    'Amounts use Argentine conventions: "12.500" is twelve thousand five hundred, "1.234,56" has a decimal comma.',
    'Always return a positive decimal with "." as the decimal separator; the direction carries the sign.',
    'Direction is from the recipient point of view: "expense" when they paid or were charged, "income" when they',
    'received money, "transfer" only when BOTH sides are accounts they own (topping up a wallet, paying their own',
    'card, buying dollars).',
    'Be strict with isMovement: most messages carrying a "$" are promotions ("¡15% OFF! Compra mínima: $15.000").',
    'A movement says money already left or entered an account: "Pagaste", "Se debitó", "Recibiste", "Compra aprobada".',
    own.length > 0
      ? [
          `The user's own accounts / payment methods, verbatim: ${own.join(' | ')}`,
          'These alerts almost never name the account, and they do not have to: the sender names the bank or wallet,',
          "and the currency picks between that sender's accounts. Answer null only when even that leaves it open.",
        ].join('\n')
      : '',
    '',
    `App / sender: ${body.origin ?? 'unknown'}`,
    `Title: ${body.title ?? ''}`,
    `Text: ${(body.text ?? '').slice(0, 2000)}`,
  ]
    .filter(Boolean)
    .join('\n');
}

/** Lite first: two lines of text do not need the heavy model (measured on
 *  the WhatsApp path, ~55 s vs a couple of seconds on the same message). */
function liteFirst(env: ImportEnv): ImportEnv {
  const models = env.GEMINI_MODELS.split(',').map((m) => m.trim()).filter(Boolean);
  return {
    ...env,
    GEMINI_MODELS: [
      ...models.filter((m) => m.includes('lite')),
      ...models.filter((m) => !m.includes('lite')),
    ].join(','),
  };
}

export async function readMessage(
  env: ImportEnv,
  body: MessageBody,
  debug?: GeminiDebug
): Promise<ReadMessage> {
  const promptText = messagePrompt(body);
  return geminiJson<ReadMessage>(liteFirst(env), [{ text: promptText }], SCHEMA, debug);
}

/**
 * `POST /suggest/message`. With `?debug=1` the prompt, the model that
 * answered and the raw JSON come back too — that is what the app's test
 * screen shows, and it is the production call, not a copy of it.
 */
export async function handleReadMessage(request: Request, env: ImportEnv): Promise<Response> {
  const wantsDebug = new URL(request.url).searchParams.get('debug') === '1';
  const body = (await request.json()) as MessageBody;
  if (!body?.title && !body?.text) return json({ error: 'empty message' }, 400);

  const debug: GeminiDebug | undefined = wantsDebug ? {} : undefined;
  const started = Date.now();
  const read = await readMessage(env, body, debug);
  return json({
    ...read,
    ...(debug
      ? {
          debug: {
            prompt: messagePrompt(body),
            model: debug.model,
            viaGateway: debug.viaGateway,
            rawText: debug.rawText,
            usage: debug.usage,
            latencyMs: debug.latencyMs ?? Date.now() - started,
          },
        }
      : {}),
  });
}
