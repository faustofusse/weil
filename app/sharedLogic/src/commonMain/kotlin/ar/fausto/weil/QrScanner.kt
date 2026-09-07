package ar.fausto.weil

interface QrScanner {
    /** Opens the platform scanner UI; null when the user cancelled. */
    suspend fun scan(): String?
}
