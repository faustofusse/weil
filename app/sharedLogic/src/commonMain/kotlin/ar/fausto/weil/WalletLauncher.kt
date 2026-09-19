package ar.fausto.weil

/**
 * Hands a scanned QR payload to a wallet app so the user confirms the payment
 * there. There is no result callback — the wallet owns the rest of the flow.
 */
interface WalletLauncher {
    /** True when a wallet app was actually opened. Never throws. */
    fun payWithMercadoPago(rawQr: String): Boolean
}
