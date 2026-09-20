/**
 * Measures the category pick of the WhatsApp bot against the real API, using
 * **the worker's own questions** (imported from `app/worker/src/chat.ts`, so
 * the harness cannot drift from what ships).
 *
 *   bun scripts/chat.ts                        # the whole table
 *   bun scripts/chat.ts "super 12.500 con santander"
 *
 * Only the category is measured here because only the category is TypeSafe's:
 * the amount, the direction, the payee and the accounts are Gemini's job on
 * this path. What matters is that this runs *beside* that call — so the
 * numbers below are the latency it has to stay under to remain free, not the
 * latency of the whole reply.
 */
import { chatQuestions, type ChatAccount } from '../../app/worker/src/chat';
import { NO_MATCH } from '../../app/worker/src/suggest';

const KEY = process.env.TYPESAFE_API_KEY;
if (!KEY) throw new Error('TYPESAFE_API_KEY missing (.env)');

const ACCOUNTS: ChatAccount[] = [
  ...[
    'Gastos:Comida:Supermercado',
    'Gastos:Comida:Delivery',
    'Gastos:Comida:Panadería',
    'Gastos:Transporte:SUBE',
    'Gastos:Transporte:Taxi',
    'Gastos:Transporte:Nafta',
    'Gastos:Servicios:Luz',
    'Gastos:Suscripciones',
    'Gastos:Salud:Farmacia',
    'Gastos:Ocio:Salidas',
    'Gastos:Ropa',
  ].map((path, i) => ({ id: `e${i}`, path, type: 'expense' as const })),
  ...['Ingresos:Sueldo', 'Ingresos:Freelance', 'Ingresos:Intereses'].map((path, i) => ({
    id: `i${i}`,
    path,
    type: 'income' as const,
  })),
  ...['Activos:Santander', 'Activos:Galicia', 'Activos:Mercado Pago'].map((path, i) => ({
    id: `a${i}`,
    path,
    type: 'asset' as const,
    commodity: 'ARS',
  })),
];

/** message → [dirección que va a devolver Gemini, categoría esperada]. */
const CASES: Array<[string, 'expense' | 'income', string | null]> = [
  ['panaderia 300', 'expense', 'Gastos:Comida:Panadería'],
  ['super 12.500 con santander', 'expense', 'Gastos:Comida:Supermercado'],
  ['uber 4300', 'expense', 'Gastos:Transporte:Taxi'],
  ['carga sube 2000', 'expense', 'Gastos:Transporte:SUBE'],
  ['ypf 45.000 con la visa', 'expense', 'Gastos:Transporte:Nafta'],
  ['netflix 7999,99', 'expense', 'Gastos:Suscripciones'],
  ['rappi 8500', 'expense', 'Gastos:Comida:Delivery'],
  ['farmacity 3.200', 'expense', 'Gastos:Salud:Farmacia'],
  ['zapatillas 120k', 'expense', 'Gastos:Ropa'],
  ['entradas boca 18.000 con mercado pago', 'expense', 'Gastos:Ocio:Salidas'],
  ['edesur 32500', 'expense', 'Gastos:Servicios:Luz'],
  ['veinte mil de nafta', 'expense', 'Gastos:Transporte:Nafta'],
  ['cobré 50000 sueldo', 'income', 'Ingresos:Sueldo'],
  ['me pagaron 200 usd de freelance', 'income', 'Ingresos:Freelance'],
  ['2 palos de sueldo', 'income', 'Ingresos:Sueldo'],
  ['plazo fijo 15300', 'income', 'Ingresos:Intereses'],
  // nada del árbol aplica: la escapatoria explícita es la respuesta correcta
  ['expensas 180000', 'expense', null],
];

const QUESTIONS = chatQuestions(ACCOUNTS);

async function ask(text: string) {
  const started = Date.now();
  const res = await fetch('https://api.typesafe.ai/v1/systemone', {
    method: 'POST',
    headers: { authorization: `Bearer ${KEY}`, 'content-type': 'application/json' },
    body: JSON.stringify({ model: 'jev-latest', state: { message: text }, questions: QUESTIONS }),
  });
  if (!res.ok) throw new Error(`${res.status} ${(await res.text()).slice(0, 200)}`);
  const data = (await res.json()) as {
    answers: Record<string, { choice?: string; confidence?: number }>;
  };
  return { answers: data.answers, ms: Date.now() - started };
}

const clean = (v: string | undefined) => (!v || v === NO_MATCH ? null : v);

const one = process.argv[2];
const cases: Array<[string, 'expense' | 'income', string | null]> = one
  ? [[one, 'expense', null]]
  : CASES;

let ok = 0;
const times: number[] = [];

for (const [text, direction, expected] of cases) {
  const { answers, ms } = await ask(text);
  times.push(ms);
  const key = direction === 'income' ? 'source' : 'category';
  const picked = clean(answers[key]?.choice);
  const confidence = answers[key]?.confidence ?? 0;
  const hit = one ? true : picked === expected;
  if (hit) ok++;
  console.log(
    `${hit ? '  ' : '✗ '}${text.padEnd(40)} → ${(picked ?? '(ninguna)').padEnd(28)} ` +
      `conf ${confidence.toFixed(2)}  ${ms} ms` +
      (hit ? '' : `   esperado: ${expected ?? '(ninguna)'}`),
  );
}

times.sort((a, b) => a - b);
console.log(
  `\n${ok}/${cases.length} · latencia p50 ${times[Math.floor(times.length / 2)]} ms · ` +
    `p90 ${times[Math.floor(times.length * 0.9)]} ms · max ${times[times.length - 1]} ms`,
);
