# Import dry-run SPA (`app/dryrun`)

## Context

Iterating on the import prompt today means either a full round trip through the phone
(`ImportReviewScreen` → `POST api.finance.fausto.ar/import/analyze`), which shows only the
*normalized candidates* and hides the prompt, the account list and the payee memory — or
`app/worker/scripts/try-csv.ts`, which shows the prompt but runs with **no real context**
(no accounts, no payee history, no session).

Goal: a Svelte SPA where I drop a statement (CSV / PDF / image) exactly like in the app and
see **everything that goes to the model and everything that comes back**: built prompt,
postable accounts, payee-history block, response schema, the literal request parts, raw model
JSON, usage/latency/model, the normalized candidates — and then the two Kotlin-side stages the
app runs afterwards: **ingest** (notification/email rules) and **reconciliation** (matchAll).
Nothing is written: no R2, no ledger, read-only SQL.

Decisions taken (from review):
- separate worker at **`https://dry.finance.fausto.ar`** (option b)
- editing + deploying `/Users/fausto/sw/auth` is allowed
- **inspector**, not an editable playground
- SvelteKit + adapter-cloudflare + tailwind, like `/Users/fausto/sw/gim/web`, living in `app/dryrun`
- ingest + reconciliation included, via a Kotlin/JS bridge (see below)

## Approach

### 1. Auth (passkey)

`rp_id` for the `finance` app is `finance.fausto.ar`, so **`dry.finance.fausto.ar` is a valid
origin for the passkeys that already exist** on the phone/Mac (a registrable-suffix subdomain);
`localhost` never would be. One change in `/Users/fausto/sw/auth/wrangler.jsonc`: add
`https://dry.finance.fausto.ar` to the `finance` entry's `origins` (that array feeds both the
CORS allowlist in `src/index.ts` and `expectedOrigin` in `src/routes/auth.ts`), then redeploy auth.

The SPA's auth client is a trimmed copy of `/Users/fausto/sw/gim/web/src/lib/auth.svelte.ts`:
`login/start` → `startAuthentication` → `login/finish`, `session/refresh` on boot,
`credentials: 'include'` everywhere. **No register fallback** — a dry-run tool must never create
a new finance user; if login fails it says so.

The session gives two things:
- the `auth_finance` cookie on `.fausto.ar`, which the dry worker's own endpoints verify with
  the existing `authenticate()` from `app/worker/src/import.ts`;
- `token.jwt` + `token.db_url`, used in the browser with `@libsql/client/web` for **read-only**
  `select`s against the user's Turso DB (accounts, notifications, emails, ledger facts).

### 2. The analyze endpoint (`POST /api/analyze` on the dry worker)

Mirrors `handleAnalyze` minus the R2 write, and returns the debug payload instead of just
candidates. It reuses the prod worker's code by importing `../../worker/src/import.ts`
directly, so the prompt shown is *the* prompt.

Small, behavior-preserving refactor in `app/worker/src/import.ts`:
- export `RESPONSE_SCHEMA`;
- extract `buildParts(document)` (currently inline in `callGemini`) and
  `normalizeCandidates(raw, accounts)` (currently the tail of `handleAnalyze`);
- give `geminiJson` an optional `debug` sink that records the request body, the model finally
  used, whether the gateway was bypassed, latency and `usageMetadata` (today `usageMetadata`
  is discarded and the raw text is parsed and thrown away);
- `handleAnalyze` keeps calling the same helpers → prod output byte-identical.

Response shape:

```
{ docSha256, kind: 'document'|'table', mimeType, bytes,
  accounts[], history[], promptText, schema,
  request: { parts: [{ kind:'text', text } | { kind:'inline_data', mimeType, bytes, base64Len }] },
  model, viaGateway, latencyMs, usage,
  rawText, transactions[] /* normalized, exactly what the app receives */ }
```

Bindings: `AUTH_DB` (D1, shared), secrets `GEMINI_API_KEY` + `TURSO_API_TOKEN`, vars
`APP_SLUG`, `ACCOUNT_ID`, `AI_GATEWAY`, `GEMINI_MODELS`, `TURSO_ORG`. No R2 binding at all —
that is the structural guarantee that a dry run stores nothing.

### 3. Ingest + reconciliation: a Kotlin/JS bridge, not a TS port

`Ingest.kt` and `Reconcile.kt` are pure, dependency-free Kotlin (no imports at all), and
`app/sharedLogic` **already has a JS target wired up** (`js { browser(); binaries.library();
generateTypeScriptDefinitions() }`, with `jsMain` actuals for `epochMillis`, `platformHttpClient`,
`notificationAccess`, `getPlatform`). So the real matcher runs in the browser — no second
implementation to keep in sync.

New `app/sharedLogic/src/jsMain/kotlin/ar/fausto/weil/DryRunBridge.kt`: `@JsExport` functions
taking and returning **JSON strings** (kotlinx.serialization), which sidesteps every Kotlin/JS
interop limitation around `List`, enums and sealed classes — and happens to be exactly what an
inspector wants to display. Exposed:
`parseNotificationJson`, `parseEmailJson`, `decodeMimeHeaderJs`, `emailPlainTextJs`,
`resolveAccountHintJson`, `buildInboxJson`, `toEventJson`, `matchAllJson`.

Two small commonMain changes:
- `@Serializable` on the value types crossing the bridge (`ImportCandidate`, `ImportSplit`,
  `ImportDirection`, `IngestedMovement`, `CandidateEvent`, `FactLeg`, `LedgerFact`,
  `EventSource`, `MatchReason`, `MatchRelation`, `AccountType`, `MatchPolicy`);
  `MatchOutcome` is sealed → flat output DTO in `jsMain` instead of polymorphic serialization.
- extract the pure core of `IngestRepository.inbox()` (the rows→`InboxCandidate` mapping) into a
  commonMain function `buildInbox(notifications, emails, accounts, knownRefs, now)` that both
  `IngestRepository` and the bridge call, so the SPA's inbox is not a re-implementation.

Build: `./gradlew :app:sharedLogic:jsBrowserProductionLibraryDistribution` → ESM + `.d.ts` under
`app/sharedLogic/build/dist/js/productionLibrary`; a vite alias (`$kotlin`) points at it and an
npm `pretask` runs the gradle task. The distribution stays gitignored.

### 4. The SPA

`app/dryrun`, SvelteKit + `adapter-cloudflare` + tailwind, `wrangler.jsonc` name `finance-dry`,
custom domain route `dry.finance.fausto.ar`.

- **`/` Import inspector** — file drop (same MIME allowlist as `IMPORTABLE_MIME_TYPES`), then
  collapsible panels: *Context* (accounts with type + colon path; payee history with counts —
  each copyable), *Prompt* (full text, monospace, char count, copy), *Schema*, *Request parts*,
  *Response* (model, gateway, latency, token usage, raw JSON), *Candidates* (the normalized
  table: date/day, direction, total, commodity, payee, detected account, splits + resolved
  category ids), and *Reconcile*: the browser loads `LedgerFact`s over libsql with the same SQL
  and the same `MatchPolicy.windowMs` window `ImportReviewScreen` uses, runs `toEvent` +
  `matchAll` through the bridge, and shows per row the outcome (Confident / Ambiguous / None),
  relation, score and `MatchReason`s — i.e. the crear / asociar / omitir the app would offer,
  plus the `eventKey` each row produced.
- **`/inbox` Ingest inspector** — 30-day window of `notifications` + `emails` from the user's DB
  (same queries/pre-filter as `IngestRepository`), each row shown as *recognized* (ruleId, parsed
  movement, resolved account hint) or *ignored*, plus the decoded email body the rules actually
  saw (`decodeMimeHeader` / `emailPlainText`, `body_html` first), refs already in
  `transaction_sources`, and the same reconcile pass on top.
  A "paste a raw notification / email" box runs the rules on hand-typed text.
- **`/devices`-less, no writes**: every SQL statement in the SPA is a `select`, asserted by a
  tiny `readOnly()` wrapper around the libsql client that rejects anything else.

## Files to modify / create

- `/Users/fausto/sw/auth/wrangler.jsonc` — add `https://dry.finance.fausto.ar` to `finance.origins` (then `wrangler deploy`).
- `app/worker/src/import.ts` — export `RESPONSE_SCHEMA`; extract `buildParts` + `normalizeCandidates`; optional `debug` sink in `geminiJson`.
- `app/sharedLogic/src/commonMain/.../{ImportRepository,Ingest,Reconcile}.kt` — `@Serializable` annotations; `buildInbox` extracted from `IngestRepository.kt`.
- `app/sharedLogic/src/jsMain/kotlin/ar/fausto/weil/DryRunBridge.kt` — new.
- `app/dryrun/**` — new SvelteKit app (`src/lib/auth.svelte.ts`, `src/lib/db.ts`, `src/lib/kotlin.ts`, `src/routes/+page.svelte`, `src/routes/inbox/+page.svelte`, `src/routes/api/analyze/+server.ts`, `wrangler.jsonc`, `vite.config.ts`).
- `.gitignore` — dryrun build output.

## Reuse

- `app/worker/src/import.ts`: `authenticate`, `loadAccounts`, `loadPayeeHistory`, `prompt`, `callGemini`/`geminiJson`, `decodeText`, `toMinor`, `sha256HexOf`.
- `app/worker/src/index.ts`: `platformToken` / `platformClient` pattern for the user's Turso DB.
- `/Users/fausto/sw/gim/web/src/lib/auth.svelte.ts` — passkey client to copy; `/Users/fausto/sw/auth/docs/web.md` — the contract.
- `Ingest.kt` / `Reconcile.kt` / `IngestRepository.kt` / `TransactionsRepository.reconcileFacts`+`knownSourceRefs` (SQL mirrored read-only in the SPA).
- `app/worker/scripts/try-csv.ts` stays as the no-auth CLI path.

## Steps

- [x] Add the dry origin to the auth worker's `APPS` and deploy auth; confirm a passkey login works from a throwaway page on the domain.
- [x] Refactor `app/worker/src/import.ts` (exports + `buildParts` + `normalizeCandidates` + debug sink); `npm run typecheck` in `app/worker`.
- [x] Verify the sharedLogic JS target actually builds today: `./gradlew :app:sharedLogic:jsBrowserProductionLibraryDistribution` (fix missing js actuals if the scaffold rotted).
- [x] `@Serializable` on the bridge value types; extract `buildInbox` from `IngestRepository`; keep `:app:sharedLogic:jvmTest` (Ingest/Reconcile tests) green.
- [x] Write `DryRunBridge.kt` and re-run the JS distribution task; check the generated `.d.ts`.
- [x] Scaffold `app/dryrun` (SvelteKit, adapter-cloudflare, tailwind, `finance-dry` + `dry.finance.fausto.ar`), wire the vite alias and the gradle pre-step.
- [x] Auth client + read-only libsql client (`select`-only guard).
- [x] `POST /api/analyze` on the dry worker; secrets `GEMINI_API_KEY`, `TURSO_API_TOKEN`.
- [x] `/` import inspector (context / prompt / schema / request / response / candidates panels).
- [x] Reconcile panel (facts window + `matchAll` through the bridge).
- [x] `/inbox` ingest inspector + raw-paste box.
- [x] Deploy; document usage in `app/dryrun/README.md` and a line in `docs/`.

## Verification

- `cd app/worker && npm run typecheck`; `cd app/dryrun && npm run check`.
- `./gradlew :app:sharedLogic:jvmTest` (existing `IngestTest`/`ReconcileTest` unchanged) and
  `:app:sharedLogic:jsBrowserProductionLibraryDistribution`.
- Prod parity: run the same CSV/PDF through the app's real import and through the SPA; candidate
  count, amounts, payees and detected accounts must match (the SPA calls the same functions).
- Bridge parity: the reconcile outcomes shown for that file must match what the app's review
  screen offers (crear / asociar / omitir) for the same ledger state.
- Read-only: after a full session (analyze + inbox + reconcile), `select count(*)` on
  `ledger_transactions` / `transaction_sources` is unchanged and no object appears in R2.
- Auth: passkey login from Mac Safari/Chrome (hybrid QR to the phone's passkey works, same rpId);
  `session/refresh` restores the session after a reload; logout clears it.

## Risks / notes

- Adding a web origin to the `finance` app makes `dry.finance.fausto.ar` a **real login surface**
  for the account — same rpId, same passkeys. Acceptable (it is my own domain and worker), but it
  is a genuine widening of the auth surface, and the no-register rule keeps it from ever creating users.
- The Turso JWT handed to the browser is read/write; only the `select`-only wrapper keeps the SPA
  honest. Analysis costs real Gemini tokens on every run.
- `dry.finance.fausto.ar` must not be confused with `api.finance.fausto.ar`: the prod worker is
  untouched at runtime; only its source module is imported.
- `wrangler dev` locally can't hold a `.fausto.ar` cookie, so the endpoint is exercised on the
  deployed domain (or with a manually pasted cookie in a dev-only header).
