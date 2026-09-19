# weil web — https://finance.fausto.ar

The browser companion to the phone app: sign in with the same passkey and look
at your own data. Today that means the captured notifications and emails, the
accounts tree, and the import pipeline with the lid off — drop the same
statement you would share into the phone and see **what the model receives**
(accounts, payee memory, the built prompt, the literal request parts, the
response schema) and **what the ledger says about the result** (reconciliation
outcomes: crear / asociar / omitir).

Nothing is written. There is no R2 binding on the worker, no ledger write path,
and every SQL statement the browser sends is checked to start with `select`
(`ReadOnlyDb` in `src/lib/db.ts`). Writes belong to the app on the phone, which
owns the transaction boundaries and the undo.

## How it avoids drifting from the app

- The `/api/analyze` endpoint imports `authenticate`, `loadAccounts`,
  `loadPayeeHistory`, `prompt`, `callGemini`, `modelOrderFor` and
  `normalizeCandidates` from `app/worker/src/import.ts` — the production
  functions, not copies. The only differences are the missing R2 `put` and the
  debug payload.
- The ingestion rules and the reconciliation matcher are the **Kotlin**
  `Ingest.kt` / `Reconcile.kt`, compiled to JS from `:app:sharedLogic` and
  exported through `WebBridge.kt` (JSON in, JSON out). The SQL for accounts,
  notifications, emails, ledger facts and known source refs is ported in
  `src/lib/db.ts` from the matching repositories.

## Auth and the shared hostname

Passkey login against the auth worker, app slug `finance`. This app is served
from the rp id itself (`finance.fausto.ar`), so the passkeys already on the
phone and the Mac work here. There is **no register fallback** — the web app
must never create a finance account.

A hostname belongs to exactly one Worker, and the auth worker still owns three
paths on this one: the Android `assetlinks.json`, the Apple app site
association (both paths) and `/desktop-pair`. `src/hooks.server.ts` proxies
them to `auth.fausto.ar` with `?rp=finance.fausto.ar`, which is how the auth
worker knows which app is being asked about when the request no longer arrives
on the rp id host. **If those stop resolving, native passkey sign-in breaks**,
so they are worth a curl after any deploy that touches routing.

## Develop

```bash
npm install
npm run dev     # runs the gradle JS build first, then vite
npm run check
npm run deploy  # npm run build && wrangler deploy
```

`npm run kotlin` alone rebuilds the bridge
(`:app:sharedLogic:jsBrowserProductionLibraryDistribution`); vite aliases
`$kotlin` at its output, which is gitignored.

Local `vite dev` cannot hold a `.fausto.ar` cookie or reach the worker
bindings, so the `/api/analyze` endpoint is exercised on the deployed domain.
The UI itself develops fine locally once you are signed in there.

One secret, once per worker: `wrangler secret put GEMINI_API_KEY` — the same
value the finance worker uses. There is deliberately no Turso API token here:
the browser forwards the database token its own session already carries
(`x-turso-token`), so the worker can read the accounts and the payee memory
without holding an org-wide credential.

## The CLI alternative

`app/worker/scripts/try-csv.ts` still exists for a prompt run with no session
and no context (`GEMINI_API_KEY=… npx tsx scripts/try-csv.ts file.csv`). This
app is the version with the user's real accounts and real ledger behind it.
