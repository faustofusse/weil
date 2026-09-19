package ar.fausto.weil

/**
 * Hands a scanned QR payload to a wallet app so the user confirms the payment
 * there. There is no result callback — the wallet owns the rest of the flow.
 */
interface WalletLauncher {
    /** True when a wallet app was actually opened. Never throws. */
    fun payWithMercadoPago(rawQr: String): Boolean
}

/**
 * Settings key holding the last QR handoff: `<epochMs>|ok|fail|<payload>`.
 * The spike's whole risk is discovering in a shop that the handoff failed and
 * coming home with nothing to debug, so the payload is kept (synced, one row,
 * overwritten every time) instead of living only in the back stack.
 */
const val QR_PAY_LAST_KEY = "qr_pay.last"
