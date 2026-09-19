# QR pay handoff — phase 1 (spike)

## Goal

Scan a merchant QR in Weil and land on Mercado Pago's payment confirmation
screen. One scan, no camera in MP.

The transaction is recorded immediately, with the merchant from the QR and an
amount of **0,00** — the QR doesn't carry one. The row says "this purchase
happened, the figure is coming"; MP's push notification brings the figure, and
completing the placeholder from it is the next phase.

## Why this is possible

`mercadopago://qr_code?from=external_access&qr_data=<payload>` is an exported,
externally launchable deep link. Verified statically against the installed APK
(`com.mercadopago.wallet`, decompiled with jadx):

- `ISScannerProxyActivity` — `VIEW` + `DEFAULT` + `BROWSABLE`, authorities `qr` /
  `qr_code`. The only gate is a scheme allowlist (`meli`, `mercadopago`). No
  caller UID or signature check.
- `navigation/entrypoint/j.java` parses `qr_data` (or `data`) into
  `ISScannerNavigationSource.Deeplink.qrData`.
- `ISScannerComposeActivity.onCreate` mounts `ISQrDataDeeplinkLoadingFragment`
  over the camera when `qrData != null`, and passes it to the view model.
- `ISScannerViewModel` dispatches `OnNewQrData` to
  `ISScannerSessionImpl.d(code)` — **the same method the camera callback
  (`presentation/scanner/handler/c.java:111`) calls with the scanned string.**

So any QR MP's camera resolves, the deep link resolves: interoperable EMVCo QRs
from other acquirers included. Resolution is server-side, identical for both
paths. Confirmed live on device that the intent is accepted from an external uid
(`BAL_ALLOW_PERMISSION`, `result code=0`).

## Flow

```
Home overflow → «Pagar con QR»
  → QrScanner.scan()                    (existing, Google code scanner)
  → EmvcoQr.parse(raw)                  (new, pure Kotlin)
  → WalletLauncher.payWithMercadoPago(raw) → MP opens on confirm screen
  → QrPayments.record(qr)               (placeholder row, in the background)
```

The handoff goes first because the user is standing at a counter; the write
follows from the coroutine and nothing blocks it. Nothing is recorded when the
wallet never opened — no handoff, no payment.

The row is: payee = tag 59 (or «Pago con QR»), default asset account → default
category, amount = tag 54 **or zero**, and a `transaction_sources` row with
`kind='qr'` and the payload as `ref`. That last one is the handle the next
phase uses to find this transaction when MP's push arrives — `Ingest.kt`
already recognizes those pushes (`mp.paid.to`, `mp.paid.amount.to`,
`mp.paid.approved`).

Zero is deliberate, not a degenerate case. A transaction must balance and its
postings must name accounts, so the shape has to be complete from the start;
0,00 in the journal is also the most visible possible reminder that a figure is
missing. `resolvePostings` rejects zero amounts (a typo, in hand entry), so the
exemption is an explicit `allowZero` flag that only this path passes.

## Work

### 1. `app/sharedLogic/src/commonMain/kotlin/ar/fausto/weil/EmvcoQr.kt` (new)

Pure, no I/O, no platform deps — same shape as `Ingest.kt` / `Reconcile.kt`.

```kotlin
data class QrPayment(
    val raw: String,
    val merchant: String?,     // tag 59
    val amountMinor: Long?,    // tag 54, null on static QRs
    val commodity: String?,    // tag 53, ISO 4217 numeric → "ARS"
    val mcc: String?,          // tag 52, unused in phase 1, captured for phase 2
)

fun parseEmvcoQr(raw: String): QrPayment?   // null when not EMVCo
```

- TLV walk: 2-char tag, 2-char length, value. Bail on malformed length.
- Validate CRC16-CCITT (poly 0x1021, init 0xFFFF) over everything up to and
  including `6304`, compare with tag 63. Reject on mismatch.
- Tag 54 is a decimal string (`"1234.50"`) → minor units.
- Tag 53 `032` → `ARS`; map only what we need, unknown → null.
- Do **not** parse merchant account templates (26–51) in phase 1.

### 2. `commonTest` — `EmvcoQrTest.kt` (new)

- A known-good dynamic QR (amount + merchant) parses to the expected fields.
- Static QR (no tag 54) → `amountMinor == null`, still parses.
- Bad CRC → null.
- Non-EMVCo string (`"https://mpago.la/abc"`, `"test123"`) → null.

### 3. `WalletLauncher` — expect/actual in sharedLogic

```kotlin
// commonMain
interface WalletLauncher {
    /** true when a wallet app was actually opened. */
    fun payWithMercadoPago(rawQr: String): Boolean
}
```

Android (`AndroidWalletLauncher`, built with the **application** context):

1. `Intent(ACTION_VIEW, "mercadopago://qr_code?from=external_access&qr_data=$enc")`
   with `setPackage("com.mercadopago.wallet")` and `FLAG_ACTIVITY_NEW_TASK`.
   `enc` = `Uri.encode(rawQr)`.
2. Guard with `resolveActivity`; on null fall back to `mercadopago://scan_qr`,
   then `getLaunchIntentForPackage`, then return false.
3. Catch `ActivityNotFoundException` at every step — never crash on a handoff.

iOS/desktop actuals return `false` (phase 1 is Android-only).

Wired into `AppGraph` next to `scanner`: `wallet: () -> WalletLauncher? = { null }`,
exposed as `val wallet: WalletLauncher?`. Android builds it in
`WeilApplication`. No foreground activity needed, so no lazy-resolution dance.

Package visibility is already covered by the existing `QUERY_ALL_PACKAGES`.

### 4. No route, no screen

The scan callback calls the wallet directly; there is nothing to navigate to.
`QrPayments` (sharedLogic) writes the placeholder row afterwards. Every attempt
(success or failure) writes `qr_pay.last` in `settings`
(`<epochMs>|ok|fail|<payload>`) so a handoff that fails in a shop is still
diagnosable at home, and a failure shows a Snackbar.

### 5. Entry point — Home overflow

New `OverflowItem` «Pagar con QR» above «Detectar movimientos». On tap:
`graph.scanner?.scan()` in a coroutine, then `parseEmvcoQr(raw)` (for the
record only) and `graph.wallet?.payWithMercadoPago(raw)`. A payload we can't
parse is handed off anyway — the handoff works regardless of whether *we*
understood the QR.

Hidden when `graph.scanner == null` (desktop), same as the existing
scanner-dependent affordances.

### 6. Strings

`sharedUI/src/commonMain/composeResources/values/strings.xml`:
`home_pay_qr` = «Pagar con QR». No hardcoded text anywhere.

## Files touched

| File | Change |
|---|---|
| `app/sharedLogic/.../EmvcoQr.kt` | new, parser |
| `app/sharedLogic/src/commonTest/.../EmvcoQrTest.kt` | new, tests |
| `app/sharedLogic/.../WalletLauncher.kt` | new, interface + `QR_PAY_LAST_KEY` |
| `app/sharedLogic/src/androidMain/.../AndroidWalletLauncher.kt` | new, Android impl |
| `app/sharedLogic/.../AppGraph.kt` | `wallet` provider (null off Android) |
| `app/androidApp/.../WeilApplication.kt` | build the launcher |
| `app/sharedLogic/.../QrPayments.kt` | new, the placeholder row |
| `app/sharedLogic/.../Reconcile.kt` | `EventSource.Qr` |
| `app/sharedLogic/.../LedgerModels.kt` | `resolvePostings(allowZero)` |
| `app/sharedLogic/.../TransactionsRepository.kt` | `add(allowZeroAmounts)` |
| `app/sharedUI/.../AppRoot.kt` | scan callback → wallet + record + `qr_pay.last` |
| `app/sharedUI/.../HomeScreen.kt` | overflow item + scan callback |
| `.../composeResources/values/strings.xml` | three strings |

No schema change, so **no `SCHEMA_VERSION` bump**.

## Verify

- `./gradlew :app:sharedLogic:jvmTest` — parser tests.
- `./gradlew :app:androidApp:assembleDebug` — compiles.
- `./gradlew :app:sharedUI:compileKotlinIosSimulatorArm64` — iOS actual compiles.
- On device (done, Pixel 8): Home ⋮ → «Pagar con QR» → scanner → MP opened on
  its own and answered "No es posible pagar a tu propia cuenta" for the test
  payload, which is the resolver confirming it read the QR. A merchant QR that
  isn't yours lands on the confirm screen instead.

## What a real MP QR actually contains

Decoded from a live «Cobrar con QR» screen for $ 800 (pinned as
`EmvcoQrTest.parsesRealMercadoPagoQr`):

```
00020101021243650016com.mercadolibre0201306366b290f4f-…-c2b5d8af8fe7
50150011204371902685204970053030325802AR5917Negocio de Fausto6004CABA63047F8B
```

Merchant (59), currency (53) and MCC (52, `9700` — MP's generic wallet code,
not a category) come through. **Tag 54 is absent**: the amount lives in the
order behind tag 43's uuid and MP resolves it server-side, so the amount
prefill is null for precisely the QRs a shop shows. The user types what the
merchant says, or leaves an estimate.

This is what settled the flow. Recording first would mean asking for a number
the user hasn't seen, so the app now hands off immediately and writes nothing:
MP's push arrives with the real amount and merchant, `Ingest.kt` recognizes it,
and «Detectar movimientos» is where the transaction gets created. No pending
state, no phantom rows, no duplicates — there is only ever one record of the
payment.

## Explicit non-goals (phase 2+)

- **Completing the placeholder from MP's push.** The row sits at 0,00 until
  the user edits it, and the push is currently offered as a *new* movement in
  «Detectar movimientos» — accepting it would double-count. The matcher needs
  a QR tier: find the recent `kind='qr'` transaction on the same account and
  rewrite its postings instead of inserting. Note `CandidateEvent.eventKey`
  fingerprints the amount, so it cannot be the key here.
- Pending / provisional transaction state.
- MCC → category mapping (parsed and dropped for now).
- iOS (`LSApplicationQueriesSchemes` + `UIApplication.openURL`).
- `resolve_params`, `ui_config`, `journey_id`.
- Other wallets, MP payment-link (`https://mpago.la/…`) `ACTION_VIEW` routing.

## Risks

| Risk | Handling |
|---|---|
| Undocumented deep link; MP can change it | Three-step fallback chain, never crashes; worst case the user scans in MP as today |
| Duplicate once the push arrives | Real today: the push is offered as a new movement next to the 0,00 row. The QR tier of the matcher is the fix, and the `kind='qr'` source row is already written for it |
| Abandoned payment leaves a 0,00 row | Ordinary transaction, one-tap delete; and 0,00 doesn't move any balance |
| The push never arrives (notification access off) | The row stays at 0,00 until the user edits it — still better than no record |
| MP app lock adds a biometric prompt | Unavoidable, MP's own security layer |
| `Barcode.rawValue` is null for non-UTF8 payloads | Treat null as a cancelled scan; EMVCo is ASCII so it shouldn't bite |
| Re-encoding corrupts the payload | Pass the scanner's string through untouched; only `Uri.encode` for transport |
