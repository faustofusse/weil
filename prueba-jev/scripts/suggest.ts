/**
 * Measures the live category guess of the quick-entry screen against the real
 * API, using **the worker's own question** (imported, not copied, so this
 * cannot drift from what ships).
 *
 *   bun scripts/suggest.ts                 # the whole table
 *   bun scripts/suggest.ts "milanesa coto" # one description
 *
 * Two things are being measured, and the second is the one that decides
 * whether this belongs in a keystroke debounce:
 *   - is the pick right, and is the confidence low when it shouldn't be sure;
 *   - how long the round trip takes.
 */
import { categoryQuestion, NO_MATCH, type SuggestOption } from '../../app/worker/src/suggest';

const KEY = process.env.TYPESAFE_API_KEY;
if (!KEY) throw new Error('TYPESAFE_API_KEY missing (.env)');

/** A plausible tree for this user: paths as the app's `disambiguatedPaths`. */
const OPTIONS: SuggestOption[] = [
  'Gastos:Comida:Supermercado',
  'Gastos:Comida:Delivery',
  'Gastos:Comida:Restaurantes',
  'Gastos:Comida:Verduras',
  'Gastos:Transporte:SUBE',
  'Gastos:Transporte:Taxi',
  'Gastos:Transporte:Nafta',
  'Gastos:Servicios:Luz',
  'Gastos:Servicios:Internet',
  'Gastos:Servicios:Celular',
  'Gastos:Suscripciones',
  'Gastos:Salud:Farmacia',
  'Gastos:Salud:Obra social',
  'Gastos:Ocio:Salidas',
  'Gastos:Ocio:Libros',
  'Gastos:Ropa',
  'Gastos:Alquiler',
  'Gastos:Regalos',
  'Gastos:Impuestos',
  'Gastos:External',
].map((path, i) => ({ id: `acc-${i}`, path }));

/** description → the category a human would expect, or null for "no idea". */
const CASES: Array<[string, string | null]> = [
  ['milanesas', 'Gastos:Comida:Supermercado'],
  ['coto', 'Gastos:Comida:Supermercado'],
  ['rappi sushi', 'Gastos:Comida:Delivery'],
  ['cena con juli', 'Gastos:Comida:Restaurantes'],
  ['verduleria de la esquina', 'Gastos:Comida:Verduras'],
  ['carga sube', 'Gastos:Transporte:SUBE'],
  ['uber al aeropuerto', 'Gastos:Transporte:Taxi'],
  ['ypf', 'Gastos:Transporte:Nafta'],
  ['edesur', 'Gastos:Servicios:Luz'],
  ['abono fibertel', 'Gastos:Servicios:Internet'],
  ['netflix', 'Gastos:Suscripciones'],
  ['farmacity ibuprofeno', 'Gastos:Salud:Farmacia'],
  ['entradas boca', 'Gastos:Ocio:Salidas'],
  ['zapatillas', 'Gastos:Ropa'],
  ['expensas', null],
  ['regalo cumple mama', 'Gastos:Regalos'],
  // half-typed and vague: the interesting part, since this runs while typing
  ['mila', null],
  ['pago', null],
  ['con mercado pago', null],
  ['a', null],
];

async function ask(text: string) {
  const started = Date.now();
  const res = await fetch('https://api.typesafe.ai/v1/systemone', {
    method: 'POST',
    headers: { authorization: `Bearer ${KEY}`, 'content-type': 'application/json' },
    body: JSON.stringify({
      model: 'jev-latest',
      state: { description: text },
      questions: { category: categoryQuestion({ text, options: OPTIONS }, OPTIONS) },
    }),
  });
  if (!res.ok) throw new Error(`${res.status} ${(await res.text()).slice(0, 200)}`);
  const data = (await res.json()) as {
    answers: { category: { choice: string; confidence: number } };
  };
  return { ...data.answers.category, ms: Date.now() - started };
}

const one = process.argv[2];
const cases: Array<[string, string | null]> = one ? [[one, null]] : CASES;

let ok = 0;
const times: number[] = [];
for (const [text, expected] of cases) {
  const a = await ask(text);
  times.push(a.ms);
  const picked = a.choice === NO_MATCH ? null : a.choice;
  // The app only moves the picker above this; below it the default stands.
  const applied = a.confidence >= 0.35 ? picked : null;
  const hit = one ? true : applied === expected;
  if (hit) ok++;
  console.log(
    `${hit ? '  ' : '✗ '}${text.padEnd(26)} → ${(applied ?? '(no mueve el picker)').padEnd(30)} ` +
      `conf ${a.confidence.toFixed(2)}  ${a.ms} ms` +
      (hit || one ? '' : `   esperado: ${expected ?? '(nada)'}`),
  );
}

times.sort((a, b) => a - b);
console.log(
  `\n${ok}/${cases.length} · latencia p50 ${times[Math.floor(times.length / 2)]} ms · ` +
    `p90 ${times[Math.floor(times.length * 0.9)]} ms · max ${times[times.length - 1]} ms`,
);
