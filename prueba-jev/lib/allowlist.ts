/**
 * The ground truth the Jev threshold gets calibrated against: a compact port
 * of the rules in `Ingest.kt` (sender + headline; the body regexes are left
 * out because here we only need "did the allowlist claim this row", not the
 * parsed amount).
 *
 * It is deliberately a *proxy*, not a second implementation: if these two ever
 * disagree, the answer is the Kotlin one. What it buys is a number — how many
 * of the movements the app already recognizes does Jev recover, and at what
 * threshold — instead of eyeballing a list.
 */
import { MONEY, type Message } from './text';

const M = MONEY.source;

const NOTIFICATION_RULES: Array<{ sender: RegExp; title: RegExp }> = [
  { sender: /^com\.mercadopago\./, title: /^Pagaste a (.+)$/i },
  { sender: /^com\.mercadopago\./, title: new RegExp(`^Pagaste\\s+${M}\\s+a (.+)$`, 'i') },
  { sender: /^com\.mercadopago\./, title: /^Tu pago fue aprobado$/i },
  { sender: /^com\.mercadopago\./, title: new RegExp(`^Recibiste\\s+${M}$`, 'i') },
  { sender: /^com\.mercadopago\./, title: /^Tu dinero ya está disponible$/i },
  { sender: /santander/i, title: /^Transferiste con éxito$/i },
  { sender: /santander/i, title: /(consumo|pagaste|compra|débito|debito)/i },
];

const EMAIL_RULES: Array<{ sender: RegExp; title: RegExp; body: RegExp }> = [
  {
    sender: /santander/i,
    title: /(consumo|pagaste|compra|débito|debito)/i,
    body: new RegExp(`Monto\\s+${M}.*?Comercio\\s+(.+?)\\s+Fecha`, 'is'),
  },
];

/** Would `Ingest.kt` claim this message as a movement? */
export function allowlisted(m: Message): boolean {
  if (m.kind === 'notification') {
    return NOTIFICATION_RULES.some((r) => r.sender.test(m.origin) && r.title.test(m.title.trim()));
  }
  return EMAIL_RULES.some(
    (r) => r.sender.test(m.origin) && r.title.test(m.title) && r.body.test(m.body),
  );
}
