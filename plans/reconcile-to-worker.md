# Move reconciliation to the worker

## Context

`Reconcile.kt` (465 lines, sharedLogic commonMain) decides whether an incoming
event is already in the ledger. It runs on the device, called from exactly one
place: `ImportReviewScreen` (`app/sharedUI/.../ImportReviewScreen.kt:347-358`),
for both of its sources — the AI document import and the notification/email
inbox.

`docs/reconciliation.md` justifies the location with "the notification listener
is offline-capable and matches rows that have not synced yet". That is no
longer what the code does: `WeilNotificationListener` only *records*; matching
happens in the review screen, which already calls `ledger.syncNow()` first
precisely so that the local replica is not stale. Every path that matches is
online, and after `syncNow()` the server state is a superset of the local one —
so the worker can answer the same question, from the same data.

Moving it there buys: one place to iterate on scoring (no app release to change
a heuristic), the dry-run inspector shows the *real* decision instead of a
faithful re-run, and the WhatsApp path can finally use the real matcher instead
of the ad-hoc dedup it has now.

### The bug this uncovers

`app/worker/src/whatsapp.ts:381` already carries a hand-rolled `normalizePayee`
that claims to mirror Kotlin's "closely enough to share fingerprints". It does
not: it lacks `NOISE_TOKENS`, the reference-code rule and the empty-result
fallback. `"MERPAGO*COTO 4821"` fingerprints as `coto` in Kotlin and
`merpago coto` in TypeScript, so a WhatsApp-entered purchase and the bank's push
for the same purchase **do not dedup** whenever a channel prefix is present.
This work replaces that copy with the real thing.

### What cannot move

`eventKey` is computed **at write time**, after the user has edited the row
(`CandidateDraft.eventKey`, `ImportReviewScreen.kt:226`) — the amount, the
payee and the fallback account are all editable, and the fingerprint has to
describe what was actually saved. Writes are local (one SQL transaction, sync
engine, Snackbar undo), so the fingerprint stays in Kotlin too.

That makes the split: **scoring and tiering move; the fingerprint is
deliberately duplicated** and pinned by shared test vectors, because two
implementations of a string that must compare equal is exactly the kind of
drift that already bit whatsapp.ts.

## Approach

`POST /reconcile/match` on the finance worker: takes candidate events, loads the
fact window from the user's Turso DB, runs the ported matcher, returns one
outcome per event. `/import/analyze` calls it inline so a document import stays
one round trip; the inbox path calls it directly.

The app keeps: the value types, `eventKey`/`normalizePayee`/`dayIndex`, the
review UI, and every write. It loses: `matchAll`/`matchEvent`/`score`/
`payeeAffinity`, `MatchPolicy` tuning, and `TransactionsRepository.reconcileFacts`.

### Wire shape

```jsonc
// POST /reconcile/match
{
  "events": [
    { "source": "document", "sourceRef": "<sha256>", "ownAccountId": "…|null",
      "amountMinor": -123456, "commodity": "ARS", "date": 1750000000000,
      "rawPayee": "MERPAGO*COTO", "direction": "expense" }
  ],
  "policy": { "dateWindowDays": 3 }            // optional, defaults server-side
}
// →
{ "outcomes": [
    { "kind": "none" | "confident" | "ambiguous",
      "eventKey": "acc|20254|-123456|ARS|coto",
      "matches": [ { "transactionId": "…", "payee": "…", "date": 1750…,
                     "relation": "Duplicate|Mirror|AlreadyImported",
                     "score": 92, "reasons": ["SameAmount","SameDay"],
                     "retargetPostingId": "…|null" } ] } ] }
```

`/import/analyze`'s response gains a sibling `outcomes` array, aligned by index
with `transactions`, plus the events it built. Old clients ignore both fields,
so the worker can ship before the app does.

### Offline behaviour (decided: don't care)

Reviewing — documents *and* inbox — requires connectivity from now on. No local
fallback matcher: that is the one thing that would force two implementations of
the scoring, which is the whole point of the move.

What stays offline is capture: `WeilNotificationListener` keeps recording every
notification with no network, and those rows sync later. Only the review step,
which already syncs before matching, needs to be online.

The requirement this creates: when `syncNow()` or `/reconcile/match` fails, the
review screen must **fail loudly** — an error with a retry, never an empty match
set. Silently reporting "no matches" would let the user import a whole statement
a second time, which is the exact failure the matcher exists to prevent.

## Files to modify

- `app/worker/src/reconcile.ts` — **new**: port of `Reconcile.kt`'s matcher +
  `loadFacts` (the SQL from `TransactionsRepository.reconcileFacts:201-262`) and
  `knownSourceRefs`.
- `app/worker/src/import.ts` — `handleAnalyze` builds events from the normalized
  candidates and attaches outcomes.
- `app/worker/src/index.ts` — route `POST /reconcile/match`.
- `app/worker/src/whatsapp.ts` — delete the local `normalizePayee`/`eventKeyOf`,
  import them from `reconcile.ts`; optionally run the real matcher before
  writing (a WhatsApp message that duplicates a push would then be reported
  instead of silently written).
- `app/sharedLogic/.../Reconcile.kt` — keep the value types, `eventKey`,
  `normalizePayee`, `dayIndex`; delete `matchEvent`, `matchAll`, `score`,
  `tolerance`, `payeeAffinity`, `demote`, `MatchPolicy`, `MatchAuthority`
  (unused today), `LedgerFact`/`FactLeg` only if nothing else reads them.
- `app/sharedLogic/.../TransactionsRepository.kt` — delete `reconcileFacts`;
  keep `associate`/`revertAssociations` (they consume `retargetPostingId`,
  which now arrives from the worker).
- `app/sharedLogic/.../ImportRepository.kt` — parse `outcomes`; add
  `ReconcileRepository.match(events)` for the inbox path (same cookie auth,
  same `HttpTimeout` treatment).
- `app/sharedUI/.../ImportReviewScreen.kt` — replace the `reconcileFacts` +
  `matchAll` block (`:343-358`) with the worker's outcomes; the `when (outcome)`
  mapping below it stays as is.
- `app/sharedLogic/src/jsMain/.../DryRunBridge.kt` — drop `matchAllJson`;
  keep the ingest exports.
- `app/dryrun` — the reconcile panels call the worker endpoint; add a
  **parity panel** that also runs the Kotlin matcher (kept during the
  transition) and shows any row where the two disagree.
- `app/sharedLogic/src/commonTest/.../ReconcileTest.kt` → split: fingerprint
  cases read the shared vectors; matcher cases move to the worker's suite.
- `docs/reconciliation.md` — rewrite the "why it lives in Kotlin" section; it is
  the reason this move looked forbidden.

## Reuse

- `Reconcile.kt:197-400` — the algorithm being ported verbatim (scores 50/25/20,
  linear decay, 6-per-day date penalty, `demote` in the batch pass).
- `TransactionsRepository.reconcileFacts` — the fact-window SQL, already written.
- `app/worker/src/import.ts` — `authenticate`, and `index.ts`'s `platformClient`
  for the user's DB.
- `app/dryrun/src/lib/reconcile.ts` — the same window arithmetic, client-side.
- `ReconcileTest.kt` (302 lines) — the behaviour contract to port.

## Steps

- [ ] Freeze the contract: shared fixture `docs/fixtures/fingerprints.json`
      (payee → normalized, event → eventKey), read by both a new Kotlin test and
      a new worker test. Fix `whatsapp.ts` to pass it.
- [ ] Add a test runner to `app/worker` (vitest + `@cloudflare/vitest-pool-workers`
      or plain vitest for the pure modules) — it has none today.
- [ ] Port `Reconcile.kt`'s matcher to `app/worker/src/reconcile.ts`, porting
      `ReconcileTest.kt`'s cases alongside it; keep names identical to the Kotlin
      so the two read as the same file.
- [ ] `loadFacts` + `knownSourceRefs` in the worker against the user's Turso DB.
- [ ] `POST /reconcile/match`; `/import/analyze` attaches `outcomes`.
- [ ] Verify against production data with `app/dryrun`'s parity panel: run the
      same statement through both matchers, expect zero disagreements.
- [ ] App side: `ReconcileRepository`, `ImportRepository` parsing,
      `ImportReviewScreen` consuming worker outcomes, offline error path.
- [ ] Delete the Kotlin matcher + `reconcileFacts` + the dry-run bridge's
      `matchAllJson`; keep the fingerprint and its test.
- [ ] WhatsApp: use the real matcher before writing (duplicate → reply saying so
      rather than writing a second row).
- [ ] Update `docs/reconciliation.md` and `app/dryrun/README.md`.

## Verification

- `cd app/worker && npm run typecheck && npm test` (ported `ReconcileTest` cases
  green, fingerprint vectors identical to Kotlin's).
- `./gradlew :app:sharedLogic:jvmTest` — fingerprint tests still green against
  the same fixture; matcher tests gone.
- `./gradlew :app:androidApp:assembleDebug` and
  `:app:sharedUI:compileKotlinIosSimulatorArm64`.
- Parity, the real check: for three saved statements (one already imported, one
  half-imported, one with a transfer pair), the dry-run parity panel reports the
  same outcome, relation, score and reason list from both implementations —
  **before** the Kotlin one is deleted.
- End to end on device: import a statement already in the ledger → every row
  defaults to *omitir*; import one with a transfer → the mirror row offers
  *asociar* and repoints the dangling leg.
- Airplane mode, both sources: an error with a retry on screen and **no drafts
  rendered**. Specifically check the inbox path, which today would happily show
  rows — landing there with an empty match set is what writes duplicates.

## Risks

- **Fingerprint duplication is permanent** (writes are local, rows are
  editable). Mitigated by the shared vectors, not by hoping.
- **Server state, not local.** The review screen must keep `syncNow()` before
  matching, and must fail loudly when it cannot — see the offline section; this
  is the only place where the move can cause data damage rather than an
  inconvenience.
- **One more round trip** on the inbox path; the document path is unchanged (the
  match rides on the analyze response).
- `MatchAuthority` is dead code today; port it only if something is going to
  use it, otherwise delete rather than translate.
