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
        "Merchant, person or institution on the other side, cleaned up and capitalised ('COTO CICSA 4821' \u2192 'Coto'). Never the amount, never the bank sending the alert unless the bank itself charged the fee. Return an EMPTY STRING when the message names no counterparty \u2014 never a placeholder like 'Desconocido', which would be written into the ledger as if it were a merchant.",
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

/**
 * The same job on Workers AI, for comparison.
 *
 * Reading a two-line alert is the smallest language task in the app and it is
 * the slowest step of the suggestion (~2-3 s), so it is worth knowing what a
 * model one hop away — same datacenter as the Worker, no public-internet
 * round trip — does with it. Run from the bench beside Gemini, never instead
 * of it: the candidate is still built from the Gemini reading until the
 * numbers say otherwise.
 */
export const GLM_MODEL = '@cf/zai-org/glm-4.7-flash';

/** JSON Schema (not Gemini's OpenAPI dialect) for the Workers AI reader. */
const JSON_SCHEMA = {
  type: 'object',
  properties: {
    isMovement: { type: 'boolean' },
    direction: { type: 'string', enum: ['expense', 'income', 'transfer'] },
    payee: { type: 'string' },
    amount: { type: 'string' },
    commodity: { type: 'string' },
    account: { type: ['string', 'null'] },
    note: { type: ['string', 'null'] },
    normalized: { type: 'string' },
  },
  required: ['isMovement', 'direction', 'payee', 'amount', 'commodity', 'normalized'],
} as const;

export interface AltReading {
  model: string;
  latencyMs: number;
  reading?: ReadMessage;
  raw?: string;
  error?: string;
}

/**
 * A reasoning model answers with prose around the JSON often enough that
 * fishing the object out is cheaper than fighting it: take the outermost
 * braces. Returns null when there is nothing parseable.
 */
function extractJson(text: string): ReadMessage | null {
  const start = text.indexOf('{');
  const end = text.lastIndexOf('}');
  if (start < 0 || end <= start) return null;
  try {
    return JSON.parse(text.slice(start, end + 1)) as ReadMessage;
  } catch {
    return null;
  }
}

/**
 * The model's actual text, whatever envelope Workers AI wrapped it in.
 *
 * Some models answer `{ response }` and others an OpenAI chat completion
 * (`choices[0].message.content`). Stringifying the envelope and fishing for
 * braces finds the *envelope's* braces, which parses fine and yields an
 * object with none of the asked-for keys — indistinguishable, downstream,
 * from a model that answered nothing. That is what every empty comparison
 * here has been.
 */
function contentOf(result: unknown): string {
  if (typeof result === 'string') return result;
  const envelope = result as {
    response?: unknown;
    choices?: Array<{ message?: { content?: unknown }; text?: unknown }>;
  };
  if (typeof envelope?.response === 'string') return envelope.response;
  const choice = envelope?.choices?.[0];
  if (typeof choice?.message?.content === 'string') return choice.message.content;
  if (typeof choice?.text === 'string') return choice.text;
  return JSON.stringify(result ?? null);
}

/**
 * Routed **through the AI Gateway**, not straight at the binding.
 *
 * On the free Workers plan the frontier models answer "upgrade to Workers
 * Paid" — unless the request carries a gateway id, in which case it is billed
 * against the account's prepaid AI Gateway credits (Unified Billing) and the
 * same models are available. The gateway's "Workers AI Billing" setting has to
 * be on **Unified billing** for this to take effect.
 */
export async function readMessageWithGlm(
  ai: Ai,
  body: MessageBody,
  model: string = GLM_MODEL,
  schema = true,
  gateway?: string
): Promise<AltReading> {
  const started = Date.now();
  const prompt = `${messagePrompt(body)}\n\nAnswer with one JSON object and nothing else, with the keys: isMovement (boolean), direction, payee, amount, commodity, account, note, normalized.`;
  // Through the gateway when there is one, straight at the binding when the
  // gateway answers that there is not. The id is a var, and a var can name
  // something that was never created — which is exactly what it did here, and
  // what a 401 from the gateway had been hiding.
  const attempt = async (viaGateway: boolean) =>
    (await ai.run(model as never, {
      messages: [{ role: 'user', content: prompt }],
      ...(schema ? { response_format: { type: 'json_schema', json_schema: JSON_SCHEMA } } : {}),
      // A reasoning model spends tokens thinking before it writes anything,
      // and a budget sized for the answer alone buys a well-formed object
      // with every field empty: it ran out before the content arrived.
      max_tokens: 1200,
    } as never,
    // skipCache off: two runs of the same notification *should* hit the
    // cache, and on a bench that is a feature, not a distortion — the
    // latency to compare is the one in the trace's first run.
    viaGateway && gateway ? ({ gateway: { id: gateway } } as never) : undefined)) as {
      response?: unknown;
    };

  try {
    let result: { response?: unknown };
    try {
      result = await attempt(true);
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      // Without the gateway the frontier models are Workers Paid only, so
      // this second try usually fails too — on purpose: its error names the
      // real problem instead of the gateway's 401.
      if (!gateway) throw e;
      console.log(`workers ai: gateway '${gateway}' unusable (${message}), trying direct`);
      result = await attempt(false);
    }
    const raw = contentOf(result);
    return {
      // The model that actually answered, not the default: ?compare=<model>
      // picks it, and a trace labelled with the wrong one is worse than an
      // unlabelled one.
      model,
      latencyMs: Date.now() - started,
      reading: extractJson(raw) ?? undefined,
      raw,
    };
  } catch (e) {
    return {
      model,
      latencyMs: Date.now() - started,
      error: e instanceof Error ? e.message : String(e),
    };
  }
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
export async function handleReadMessage(
  request: Request,
  env: ImportEnv,
  ai?: Ai
): Promise<Response> {
  const params = new URL(request.url).searchParams;
  const wantsDebug = params.get('debug') === '1';
  const compare = params.get('compare');
  const wantsAlt = compare != null && compare !== '0' && ai != null;
  // `?compare=@cf/zai-org/glm-5.3-flash` tries another model without a
  // deploy; `?compare=1` uses the default. `&schema=0` drops the structured
  // output, which is worth trying on a model that reasons before answering.
  const altModel = compare && compare !== '1' ? compare : GLM_MODEL;
  const body = (await request.json()) as MessageBody;
  if (!body?.title && !body?.text) return json({ error: 'empty message' }, 400);

  const debug: GeminiDebug | undefined = wantsDebug ? {} : undefined;
  const started = Date.now();
  // Side by side, so the comparison costs one wall clock instead of two and
  // both models see the exact same prompt.
  const [read, alt] = await Promise.all([
    readMessage(env, body, debug),
    wantsAlt
      ? readMessageWithGlm(ai, body, altModel, params.get('schema') !== '0', env.AI_GATEWAY)
      : Promise.resolve(undefined),
  ]);
  return json({
    ...read,
    // The reader's own latency, separate from the request's: the two models
    // run in parallel, so the wall clock the device sees is the slower one
    // and says nothing about either.
    readerMs: debug?.latencyMs ?? Date.now() - started,
    ...(alt ? { alt } : {}),
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
