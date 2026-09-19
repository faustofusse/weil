# QR pay handoff — phase 1 (spike)

## Goal

Scan a merchant QR in Weil, record the expense, and land the user on Mercado
Pago's payment confirmation screen. One scan, no camera in MP.

This phase is a **spike to prove the handoff works on a real merchant QR**.
Reconciliation with the incoming MP notification is explicitly out of scope and
is phase 2.

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
  → QrPayRoute(raw, merchant, amountMinor)
  → TransactionQuickScreen prefilled (Gasto, amount, payee)
  → user taps Registrar → ledger.add(...)
  → WalletLauncher.payWithMercadoPago(raw) → MP opens on confirm screen
  → pop back to Home
```

Record-then-hand-off is deliberate for the spike: there is no result callback
from MP, so the alternative (hand off, then confirm on return) needs state we
don't have yet. Cost: if the user abandons the payment in MP, Weil holds a
transaction that didn't happen. Acceptable — it's an ordinary transaction, the
existing Snackbar undo and the journal both remove it in one tap.

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

### 4. Route + screen prefill

`Routes.kt`:

```kotlin
data class QrPayRoute(
    val raw: String,
    val merchant: String?,
    val amountMinor: Long?,
)
```

`TransactionQuickScreen` gains two optional params, defaulted so every existing
call site is untouched:

```kotlin
prefillAmount: String? = null,
prefillPayee: String? = null,
```

seeded into the existing `amountText` / `description` `remember` initializers.
`description` is already what `ledger.add` passes as payee, so the merchant name
lands in the right column. Autofocus stays on the amount field — on a static QR
that's exactly where the user needs to be.

`AppRoot.kt` entry:

```kotlin
entry<QrPayRoute> { route ->
    TransactionQuickScreen(
        ledger = graph.ledger, accounts = graph.accounts, settings = graph.settings,
        kind = TxnKind.Expense,
        prefillAmount = route.amountMinor?.let { formatMinorUnits(it) },
        prefillPayee = route.merchant,
        onSaved = {
            graph.wallet?.payWithMercadoPago(route.raw)
            pop()
        },
        onNavigateBack = { pop() },
    )
}
```

### 5. Entry point — Home overflow

New `OverflowItem` «Pagar con QR» above «Detectar movimientos». On tap:
`graph.scanner?.scan()` in a coroutine, then `parseEmvcoQr(raw)` and navigate to
`QrPayRoute`. A payload we can't parse still navigates — with null prefills —
because the handoff works regardless of whether *we* understood the QR.

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
| `app/sharedLogic/.../WalletLauncher.kt` | new, expect |
| `app/sharedLogic/src/androidMain/.../AndroidWalletLauncher.kt` | new, actual |
| `app/sharedLogic/src/iosMain/.../WalletLauncher.ios.kt` | new, no-op actual |
| `app/sharedUI/src/desktopMain/.../WalletLauncher.desktop.kt` | new, no-op actual |
| `app/sharedLogic/.../AppGraph.kt` | `wallet` provider |
| `app/androidApp/.../WeilApplication.kt` | build the launcher |
| `app/sharedUI/.../Routes.kt` | `QrPayRoute` |
| `app/sharedUI/.../TransactionQuickScreen.kt` | two optional prefill params |
| `app/sharedUI/.../AppRoot.kt` | mount `QrPayRoute` |
| `app/sharedUI/.../HomeScreen.kt` | overflow item + scan callback |
| `app/sharedLogic/.../Reconcile.kt` | `EventSource.Qr` |
| `app/sharedLogic/.../TransactionsRepository.kt` | `setNote` (failed-handoff capture) |
| `.../composeResources/values/strings.xml` | one string |

No schema change, so **no `SCHEMA_VERSION` bump**.

## Verify

- `./gradlew :app:sharedLogic:jvmTest` — parser tests.
- `./gradlew :app:androidApp:assembleDebug` — compiles.
- `./gradlew :app:sharedUI:compileKotlinIosSimulatorArm64` — iOS actual compiles.
- On device: scan a real merchant QR in a shop. Expect MP's app-lock prompt,
  then the spinner, then the confirm screen with the right amount and merchant.

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

That could have argued for handing off first and recording on return, but
there is no result callback, so that needs pending state phase 1 doesn't have.
Decision: **keep record-then-hand-off, and let phase 2's reconciliation correct
the amount** rather than only deduplicate. The hook is already written — every
QR-paid transaction carries a `transaction_sources` row with
`kind='qr'` and the payload as `ref` (`EventSource.Qr`), so the matcher can
find it without guessing.

Phase 2 therefore needs an amount-tolerant tier: `CandidateEvent.eventKey`
includes `amountMinor`, so a typed estimate will *not* fingerprint-match the
wallet's push. Matching a push against a recent `kind='qr'` transaction on the
same account within a few minutes, then rewriting both postings to the push's
amount, is the shape.

## Explicit non-goals (phase 2+)

- Reconciling the MP push notification against the transaction we just wrote —
  **this phase will produce duplicates**, knowingly, and leaves the amount as
  whatever the user typed.
- Pending / provisional transaction state.
- MCC → category mapping (parsed and dropped for now).
- iOS (`LSApplicationQueriesSchemes` + `UIApplication.openURL`).
- `resolve_params`, `ui_config`, `journey_id`.
- Other wallets, MP payment-link (`https://mpago.la/…`) `ACTION_VIEW` routing.

## Risks

| Risk | Handling |
|---|---|
| Undocumented deep link; MP can change it | Three-step fallback chain, never crashes; worst case the user scans in MP as today |
| Duplicate transaction once the push arrives | Known and accepted for the spike; it's the whole point of phase 2 |
| Abandoned payment leaves a phantom transaction | Ordinary transaction, one-tap delete |
| MP app lock adds a biometric prompt | Unavoidable, MP's own security layer |
| `Barcode.rawValue` is null for non-UTF8 payloads | Treat null as a cancelled scan; EMVCo is ASCII so it shouldn't bite |
| Re-encoding corrupts the payload | Pass the scanner's string through untouched; only `Uri.encode` for transport |
