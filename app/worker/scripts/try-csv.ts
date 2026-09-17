/**
 * Dry-runs the import prompt against a local file, without the worker, the
 * app, R2 or a session: prompt quality is what usually needs iterating, and
 * a full round trip through the device to see it is slow.
 *
 *   GEMINI_API_KEY=... npx tsx scripts/try-csv.ts ~/Desktop/julio.csv [accounts.json]
 *
 * accounts.json, when given, is [{ id, name, path, type }] — the same shape
 * loadAccounts returns. Without it the model gets no category list and every
 * candidate comes back uncategorized, which still exercises extraction.
 */
import { readFileSync } from 'fs';
import { callGemini, prompt, type ImportEnv } from '../src/import';

const [file, accountsFile] = process.argv.slice(2);
if (!file) {
  console.error('usage: GEMINI_API_KEY=... npx tsx scripts/try-csv.ts <file> [accounts.json]');
  process.exit(1);
}
const apiKey = process.env.GEMINI_API_KEY;
if (!apiKey) {
  console.error('GEMINI_API_KEY is not set');
  process.exit(1);
}

const bytes = new Uint8Array(readFileSync(file));
const mimeType = file.endsWith('.csv')
  ? 'text/csv'
  : file.endsWith('.pdf')
    ? 'application/pdf'
    : 'image/jpeg';
const accounts = accountsFile ? JSON.parse(readFileSync(accountsFile, 'utf8')) : [];

// No gateway: this runs outside the account, so go straight to Google.
const env = {
  GEMINI_API_KEY: apiKey,
  GEMINI_MODELS: process.env.GEMINI_MODELS ?? 'gemini-3.5-flash,gemini-3.6-flash',
  ACCOUNT_ID: '',
  AI_GATEWAY: '',
} as unknown as ImportEnv;

const started = Date.now();
const rows = await callGemini(
  env,
  { mimeType, bytes },
  prompt(accounts, [], mimeType.startsWith('text/') ? 'table' : 'document')
);
console.error(`${rows.length} candidates in ${((Date.now() - started) / 1000).toFixed(1)}s\n`);
for (const r of rows) {
  const total = (r.splits ?? []).reduce((sum, s) => sum + Number(s.amount ?? 0), 0);
  const category = (r.splits ?? []).map((s) => s.category ?? '-').join(' + ');
  console.log(
    [
      r.date,
      r.direction.padEnd(8),
      total.toFixed(2).padStart(12),
      r.commodity,
      (r.payee ?? '').slice(0, 38).padEnd(38),
      `[${r.account ?? '-'}]`,
      category,
    ].join('  ')
  );
}
