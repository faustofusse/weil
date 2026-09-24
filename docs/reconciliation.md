# Reconciliation: one event, many sources

## The problem

The same economic event reaches the app through several doors:

- a **push notification** ("Pagaste $12.400 con tu Visa"), seconds after it happens
- an **email** receipt, minutes later
- a **statement** (PDF/image import), weeks later — and it contains *every* event
  the notifications already captured
- a **manual** entry, whenever

Before this feature each door created its own transaction, so importing a
Mercado Pago statement and a Santander statement produced two halves of the
same transfer: `Mercado Pago › Santander 200.000` from one document and
`Fausto Fusse Mercado Pago › idk 200.000` from the other. Neither knew about
the other, and both counted.

Three separate gaps caused it:

1. **The model only sees one document.** Nothing inside the Mercado Pago
   statement proves the counterparty is you, so the row cannot be classified as
   a transfer from that document alone.
2. **`/import/analyze` is stateless w.r.t. the ledger.** The worker reads the
   user's `accounts`, never their transactions, so it cannot know the event is
   already recorded.
3. **A transaction could only be created, never completed.** There was no way
   for a later source to attach to an existing transaction.

## Shape of the solution

Every door produces the same value — a **candidate event** — and every door
asks the same two questions about it:

1. *Does this already exist in the ledger?* → matching (this document)
2. *How should it be categorized?* → learned priors (later, see Roadmap)

```
document ──► worker: Gemini extraction ──► ImportCandidate ──┐
notification ──► per-package parser ─────────────────────────┤
email ──► parser ────────────────────────────────────────────┼─► CandidateEvent
manual ─────────────────────────────────────────────────────┘        │
                                                                     ▼
                                          LedgerFact window ──► matchEvent()
                                                                     │
                                       None / Confident / Ambiguous ─┘
                                                     │
                          create  ◄──────────────────┼──────────────────► associate
                                                (review UI)
```

### Layering rules (the reason it generalizes)

These four rules are what let the notification path reuse the engine instead of
reimplementing it. They are deliberate, not incidental:

1. **The matcher lives in Kotlin `commonMain`, not the worker.**
   `WeilNotificationListener` runs in a background service, frequently offline,
   against rows that have not synced yet (sync is throttled to one call per
   30 s and failures are swallowed). It cannot ask a worker "does this already
   exist?". The worker keeps what only it can do: Gemini vision extraction.
   This also dodges the worker's blind spot — it queries *server* Turso state,
   so the freshest local rows, exactly the ones worth matching, are invisible
   to it.
2. **Source-agnostic in, tier value out.** The matcher consumes
   `CandidateEvent` (no `splits`, no `docId`) and returns `MatchOutcome`.
   It never learns which producer it is serving; consumers branch on the tier:
   the import screen sends ambiguity to the user, the notification path will
   simply create a pending row and defer.
3. **AI adjudication is optional.** Deciding "is `MERPAGO*COTO 4821` the same
   as `Coto`?" is a good job for a model, but it costs 15 s and a network call.
   The document path can afford it; a push notification cannot. So the
   deterministic tiering stands alone and a second pass can only ever *narrow
   the ambiguous set*, never be required for the flow to work.
4. **Authority on conflict is a parameter, not a constant.** With a statement,
   the document is authoritative and its amount should win. With a
   notification, arrival order is inverted — it is the first record and the
   statement corrects it later. `MatchPolicy` carries this.

## Data model

`transaction_sources` replaces the three `transactions.source_*`
columns' one-transaction-one-origin assumption. A single purchase legitimately
has a push notification *and* an email receipt *and* a statement row.

```sql
create table transaction_sources(
  transaction_id text not null,
  kind text not null,          -- document | notification | email | manual
  ref text not null,           -- R2 doc hash, notification id, email id
  event_key text,              -- dedup fingerprint, see below
  created_at integer not null,
  primary key(transaction_id, kind, ref));
```

The legacy columns are still written (nothing reads them but they are cheap
provenance) and are backfilled into the table idempotently on every migration,
so devices converge.

**`event_key`** is the fingerprint of the *event*, deliberately independent of
which door it came through:

```
{ownAccountId}|{dayIndex}|{signedAmountMinor}|{normalizedPayee}
```

Two analyses of the same statement produce the same key, so re-importing an
overlapping period is detected without any scoring. Day granularity, not
millisecond: document dates are noon UTC, notifications are real timestamps.

## Matching

`Reconcile.kt`, all pure functions, all unit-tested.

**Blocking** — the repository loads a narrow window of `LedgerFact`s
(`TransactionsRepository.reconcileFacts(from, to)`): transactions in
`[minCandidateDate - window, maxCandidateDate + window]` with their postings,
each posting's account type, and the source rows already attached. One query
pair, no per-candidate round trips.

**Scoring** — for each fact leg on the user's own accounts (asset/liability),
same commodity:

| signal | weight |
| --- | --- |
| amount equal to the cent | 50, decaying to 0 across the tolerance |
| same day | 25, −6 per day of distance |
| normalized payee equal / contains / shares a token | 20 / 12 / 6 |
| the event's own account is the one involved | 10 |

**Relations** (what kind of coincidence this is):

- `AlreadyImported` — the fact already carries this `event_key`. Dominant, no
  scoring needed.
- `Duplicate` — same own account, same sign: the same movement, already there.
  Associating adds a source row and nothing else.
- `Mirror` — *different* own account, opposite sign: the two halves of a
  transfer, each seen from one side. Associating also retargets the existing
  transaction's dangling expense/income leg to this event's own account, which
  turns two half-wrong rows into one correct transfer.

*Same* own account with the *opposite* sign is none of these, and `matchEvent`
drops it. The auto-recorder asks about it separately (`findReversal`): the
exact opposite amount, within an hour, against an expense/income row with the
same counterparty. That is what one cash withdrawal looked like as two bank
mails («solicitud de envío de efectivo» read as −300.000 and «tenés un envío
para vos», minutes later, read as +300.000): the second row does not duplicate
the first, it cancels it, and the balance looks untouched. The auto-recorder
skips such a run (`AutoRecordSkip.PossibleReversal`). The review screens
still offer it, because there a human decides.

**Tiers** — `MatchOutcome`:

- `Confident(match)` — score ≥ `autoScore` **and** at least `decisiveMargin`
  ahead of the runner-up. The margin matters: two identical charges at the same
  merchant on the same day must not silently collapse into one.
- `Ambiguous(matches)` — anything above `reviewScore`. Surfaced to the user
  with its reasons; never auto-applied.
- `None` — create.

**One-to-one assignment** — `matchAll` runs the batch and then enforces that a
stored transaction is claimed by at most one event. Without it, a statement
printing the same USD charge four times (verified on the real August Santander
statement: four identical `Anomaly -21,23 USD` rows against one stored row)
auto-associated all four to it, quietly collapsing four movements into one. The
best-scoring claimant keeps its confident match; the rest are demoted to
suggestions, so they default to *crear* while still showing what they might
duplicate.

Reasons are a `MatchReason` enum, not prose: `sharedLogic` stays untranslated
and the UI maps them to `strings.xml` (project rule). The review row shows
*why* — "mismo importe · mismo día · cuenta opuesta" — so a merge is
approvable at a glance instead of being an opaque mutation.

## Import flow today

1. the screen syncs first (so the local replica sees other devices' rows), then
   uploads the document
2. the worker extracts candidates (unchanged)
3. each candidate becomes a `CandidateEvent`; `matchEvent` tiers it against the
   fact window
4. defaults: `Confident` → *asociar*; `AlreadyImported` → *omitir*, labelled;
   everything else → *crear*
5. every row is a three-state choice the user can override: **crear /
   asociar / omitir**
6. one write: `addAll` for creations (with their source rows), `associate` for
   associations, both undoable from the Snackbar

## Roadmap

Ordered so each step is a thin consumer of what already exists.

1. ~~`transaction_sources`, payee normalizer, `CandidateEvent`, matcher~~ done
2. ~~document import consumes the matcher (crear/asociar/omitir)~~ done
3. **retroactive `conciliar` tool** — same matcher run over the existing ledger,
   listing suspected mirror pairs for one-tap merge. The only thing that cleans
   up data imported before this feature existed.
4. **pass 2 adjudication** — extraction prompt drops its dedup rules and emits
   generously; a cheap model then decides the ambiguous set (ledger matches
   *and* intra-document repeats, which is the same comparison) returning a
   decision plus a rationale rendered in the review row.
5. **notification → transaction** — per-package regex parser fills
   `CandidateEvent`; `Confident` associates, `None` creates a *pending*
   transaction, `Ambiguous` creates pending and flags. No UI, no model, no
   waiting on the listener thread.
6. **learned category priors** — `normalizedPayee → account` frequency with
   recency and dominance thresholds, applied post-parse (not in the prompt).
   Key hierarchy: `(package, payee, direction)` → `(payee, direction)` →
   `(package, amount)` → package default → nothing. Below threshold leave the
   category null (never `Otros` — indistinguishable from a real choice). Mark
   inferred categories as such until the user touches them, and record
   *proposed vs saved* so a correction weighs more than an untouched
   acceptance.
7. **learned counterparty aliases** — harvest from every merge: "counterparty
   `Fausto Fusse` on Mercado Pago pairs with Santander". Feeds back into the
   extraction prompt so the transfer is detected from one document and never
   needs pairing again.

## Known gaps

- **Amount drift.** A pre-auth ($12.400) settles at another number (tip, FX,
  fuel hold). `MatchPolicy` has the tolerance knobs, but associating does not
  yet rewrite the ledger amount to the authoritative side — needed before
  step 5, meaningless before it.
- **Intra-document duplicates.** A statement prints the same movement in the
  main table and again in a per-product section. Today the extraction prompt is
  asked to suppress those and is bad at it (see the twin `Futbol › Mercado
  Pago 11.000` rows). One-to-one assignment stops them from stacking onto one
  existing transaction, but it cannot tell "four repeats of one charge" from
  "four identical charges" — the extra rows fall to *crear* and the user unticks
  them. Step 4 is what decides it properly.

## Measured on real data

Re-running the August Santander statement against a ledger that already held
an earlier import of it (rows with **no** `event_key`, so scoring alone, and
with categories/accounts the user had since edited by hand):

- 38 candidates extracted, **26 auto-associated**, 12 left to create
- payee text often disagreed completely (`Mercado Pago` ↔ «electronicafl»,
  `AFIP` ↔ «IVA RG 4240») and amount+day still carried the match, confirming
  the payee signal must stay a bonus rather than a requirement
- the Mercado Pago ↔ Santander transfer that motivated the whole feature was
  found as a `Mirror` («Nestor Dario Fusse», −400.000)
- of the 12 creations, 7 are the demoted multi-claim rows described above
- **No multi-file session.** Two statements imported back to back match against
  the *ledger*, so the second one matches whatever the first one wrote — which
  is the case that was broken and now works. Matching against another import's
  *unconfirmed* candidates is not supported.

## The other two doors

`Ingest.kt` turns notifications and email receipts into the same
`CandidateEvent` a statement row becomes, and `IngestRepository.inbox()`
offers them through the same review screen (`ReviewSource.Inbox`). Nothing in
the matcher changed to accommodate them, which was the point of making
`CandidateEvent` source-agnostic in the first place.

Two properties are worth keeping:

- **It never writes.** A parser bug costs a wrong suggestion on a review
  screen, never a wrong ledger row.
- **A message is offered until something links it.** `knownSourceRefs` filters
  refs already in `transaction_sources`, so acting on an alert — by creating
  *or* by associating — takes it out of the inbox.

The account a movement belongs to is the weak link: an app name is not an
account ("Santander" is four of them). `resolveAccountHint` uses the commodity
as a tiebreak and gives up when the winner is not alone, falling back to the
screen's default account rather than guessing.

### Known gaps here

- **Ingestion truncation.** Mercado Pago's "Pago aprobado en X" and Santander's
  "Aviso de transferencia" store an empty `body_html` and a `body_text` that is
  raw MIME cut at 10 kB — all stylesheet, no receipt. Those are unparseable
  until the worker's email path stores the receipt rather than the wrapper.
- **Broker mail.** IOL's "Estado de la Transacción" parses cleanly but
  describes a securities purchase (asset↔asset, commission, taxes), which the
  single-amount candidate shape cannot express.
- **No learned aliases yet.** "Google  youtube premiu" and a statement's
  "GOOGLE *YOUTUBE" only meet through the payee-affinity heuristic.

### Seeing it run

`app/web` (https://finance.fausto.ar) replays all of this in a browser: the
same prompt and context the worker sends to Gemini, then `parseNotification` /
`parseEmail` / `buildInbox` / `matchAll` over the real rows — the actual
Kotlin, compiled to JS from `:app:sharedLogic` via `WebBridge.kt`, not a port.
Read-only by construction. See `app/web/README.md`.
