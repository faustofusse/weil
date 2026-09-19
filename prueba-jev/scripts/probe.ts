/**
 * ¿Estamos filtrando de más?
 *
 * El prefiltro del §2 exige un símbolo de moneda (`$`, `U$S`, `USD`, `ARS`).
 * Barato y precisisimo, pero ciego a "te transferimos 35000" o "1.250,00
 * pesos". Este script mide exactamente ese punto ciego: recorre lo que el
 * prefiltro descarta y lo vuelve a filtrar con redes progresivamente más
 * anchas, para poder decidir con números — y no por intuición — si hay que
 * ampliarlo.
 *
 *   bun scripts/probe.ts            # conteos + muestras
 *   bun scripts/probe.ts --sample 8 # cuántos ejemplos por red
 *   bun scripts/probe.ts --all      # imprime todos los de la red más angosta
 */
import { labClient, loadMessages } from '../lib/db';
import { canonicalText, hasAmount, type Message } from '../lib/text';

/** Un número escrito como plata: miles con punto y/o decimales con coma.
 * "1.250", "35.000,00", "19200,50". Un "2025" suelto no entra (no tiene
 * separador), y es justamente el ruido que queremos afuera. */
const GROUPED = /\b\d{1,3}(?:\.\d{3})+(?:,\d{1,2})?\b|\b\d+,\d{2}\b/;

/** Un número "gordo" sin separadores: 4 dígitos o más. Atrapa "35000" pero
 * también años, códigos de seguimiento y IDs. */
const BARE_BIG = /\b\d{4,}\b/;

/** La moneda dicha con palabras en vez de símbolo. */
const CURRENCY_WORD = /\b(pesos?|d[oó]lares?|ARS|USD|guaran[ií]es)\b/i;

/** Verbos y sustantivos de movimiento. El allowlist de `Ingest.kt` vive de
 * estos; acá sirven para separar "hay un número" de "hay una plata". */
const MOVEMENT_WORD = new RegExp(
  '\\b(pagaste|pagamos|pago|pagos|abonaste|abonado|cobro|cobramos|debitamos|d[eé]bito|' +
  'acredit\\w+|transferencia|transferiste|transfiri\\w+|enviaste|recibiste|ingresaste|' +
  'extracci[oó]n|extrajiste|retiro|compra|consumo|importe|monto|saldo|rendimiento|' +
  'cuota|factura|resumen|vencimiento|tarjeta|cr[eé]dito)\\b',
  'i',
);

/** Promoción: si el mensaje dice esto, el número casi seguro es un descuento
 * o un plazo, no un movimiento. Se usa solo para etiquetar las muestras. */
const PROMO = /\b(\d{1,2}\s*%|% ?OFF|descuento|cuotas sin inter[eé]s|promo\w*|gratis|sorteo|aprovech\w+)\b/i;

interface Net {
  name: string;
  /** Qué tiene que cumplir el texto para caer en esta red. */
  test: (t: string) => boolean;
}

/** De más angosta (casi seguro plata) a más ancha (casi seguro ruido). El
 * orden importa: cada mensaje se cuenta en la primera que lo atrapa, así los
 * totales suman y no se pisan. */
const NETS: Net[] = [
  {
    name: 'A · número con formato de plata + palabra de movimiento',
    test: (t) => GROUPED.test(t) && MOVEMENT_WORD.test(t),
  },
  {
    name: 'B · número + moneda escrita con palabras ("35000 pesos")',
    test: (t) => (GROUPED.test(t) || BARE_BIG.test(t)) && CURRENCY_WORD.test(t),
  },
  {
    name: 'C · número con formato de plata, sin palabra de movimiento',
    test: (t) => GROUPED.test(t),
  },
  {
    name: 'D · número gordo (≥4 dígitos) + palabra de movimiento',
    test: (t) => BARE_BIG.test(t) && MOVEMENT_WORD.test(t),
  },
  {
    name: 'E · sólo dígitos sueltos',
    test: () => true,
  },
];

function fmt(m: Message): string {
  const date = new Date(m.date).toISOString().slice(0, 16).replace('T', ' ');
  const flag = PROMO.test(canonicalText(m, 400)) ? ' [promo?]' : '';
  return `    ${date} ${m.kind === 'email' ? '✉' : '🔔'} ${m.originLabel}${flag}\n` +
    `      ${canonicalText(m, 220).replace(/\s+/g, ' ')}`;
}

async function main() {
  const args = process.argv.slice(2);
  const sample = Number(args[args.indexOf('--sample') + 1]) || 6;
  const all = args.includes('--all');

  const db = labClient();
  const messages = await loadMessages(db);
  const withCurrency = messages.filter((m) => hasAmount(canonicalText(m, 4000)));
  const discarded = messages.filter((m) => !hasAmount(canonicalText(m, 4000)));

  console.log(`mensajes totales:          ${messages.length}`);
  console.log(`pasan el prefiltro actual: ${withCurrency.length}  (símbolo de moneda)`);
  console.log(`descartados:               ${discarded.length}\n`);
  console.log('De lo descartado, cada mensaje cae en la PRIMERA red que lo atrapa:\n');

  const buckets = new Map<string, Message[]>(NETS.map((n) => [n.name, []]));
  for (const m of discarded) {
    const text = canonicalText(m, 4000);
    const net = NETS.find((n) => n.test(text))!;
    buckets.get(net.name)!.push(m);
  }

  for (const net of NETS) {
    const rows = buckets.get(net.name)!;
    console.log(`${net.name}: ${rows.length}`);
    if (net.name.startsWith('E')) continue; // el pozo de ruido, no se muestrea
    const shown = all && net.name.startsWith('A') ? rows : rows.slice(0, sample);
    const byOrigin = new Map<string, number>();
    for (const m of rows) byOrigin.set(m.originLabel, (byOrigin.get(m.originLabel) ?? 0) + 1);
    const top = [...byOrigin.entries()].sort((a, b) => b[1] - a[1]).slice(0, 6);
    if (top.length) console.log(`    orígenes: ${top.map(([o, n]) => `${o} (${n})`).join(', ')}`);
    for (const m of shown) console.log(fmt(m));
    console.log();
  }

  db.close();
}

main();
