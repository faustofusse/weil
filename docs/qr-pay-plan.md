# QR pay handoff — phase 1 (spike)

## Goal

Scan a merchant QR in Weil and land on Mercado Pago's payment confirmation
screen. One scan, no camera in MP.

The expense is *not* recorded here: MP's push notification arrives right after
the payment and the existing «Detectar movimientos» flow turns it into the
ledger entry. So the app never guesses an amount, and there is exactly one
record of each payment.

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
  → EmvcoQr.parse(raw)                  (new, pure Kotlin — only to log/report)
  → WalletLauncher.payWithMercadoPago(raw) → MP opens on confirm screen
  → that's it. Weil writes nothing.
```

**The app records nothing on this path.** The ledger entry comes from MP's own
push notification, which lands seconds after the payment and which `Ingest.kt`
already recognizes (`mp.paid.to`, `mp.paid.amount.to`, `mp.paid.approved`), so
it surfaces in «Detectar movimientos» with the real amount and merchant.

That is strictly better than recording up front, and the live test is what
showed why: MP's QRs carry no amount (see below), so recording first means
asking the user to type a number *before* seeing it, then storing a guess for a
payment that may never happen. Waiting costs nothing — the notification is the
movement that actually occurred.

The parse survives only to describe what was scanned; nothing downstream
depends on it.

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
Every attempt (success or failure) writes `qr_pay.last` in `settings`
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
| `app/sharedUI/.../AppRoot.kt` | scan callback → wallet + `qr_pay.last` |
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

- Recognizing the push as *this* scan's payment specifically. Today the push
  is reviewed like any other detected movement; linking it back to the scan
  (to attach the merchant name from tag 59, say) would need the payload kept
  as pending state.
- Pending / provisional transaction state.
- MCC → category mapping (parsed and dropped for now).
- iOS (`LSApplicationQueriesSchemes` + `UIApplication.openURL`).
- `resolve_params`, `ui_config`, `journey_id`.
- Other wallets, MP payment-link (`https://mpago.la/…`) `ACTION_VIEW` routing.

## Risks

| Risk | Handling |
|---|---|
| Undocumented deep link; MP can change it | Three-step fallback chain, never crashes; worst case the user scans in MP as today |
| Duplicate transaction once the push arrives | Gone: the push *is* the record, the app writes nothing on scan |
| Abandoned payment leaves a phantom transaction | Gone for the same reason — no payment, no push, no row |
| The push never arrives (notification access off) | The payment is simply unrecorded, as before this feature; «Detectar movimientos» has nothing to offer |
| MP app lock adds a biometric prompt | Unavoidable, MP's own security layer |
| `Barcode.rawValue` is null for non-UTF8 payloads | Treat null as a cancelled scan; EMVCo is ASCII so it shouldn't bite |
| Re-encoding corrupts the payload | Pass the scanner's string through untouched; only `Uri.encode` for transport |
