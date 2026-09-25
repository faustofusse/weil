package ar.fausto.weil

interface PasskeyCeremony {
    suspend fun create(optionsJson: String): String

    /**
     * Sign in with a passkey that is already on this device. Throws
     * [PasskeyNotFound] without showing any UI when there is none — the caller
     * registers then — and [PasskeyCancelled] when the user dismissed the sheet.
     */
    suspend fun assert(optionsJson: String): String

    // WebAuthn Signal API: tells the password manager what the server knows so
    // the picker only ever offers passkeys that work. Best effort — a provider
    // or OS that does not support it simply ignores the call — and ids/handles
    // are base64url, as they travel in the ceremony JSON.

    /** The server does not know [credentialId]: hide or delete it. */
    suspend fun signalUnknownCredential(rpId: String, credentialId: String) {}

    /** Every passkey of [userHandle] not listed in [credentialIds] is stale. */
    suspend fun signalAcceptedCredentials(rpId: String, userHandle: String, credentialIds: List<String>) {}

    /** Rename the passkeys of [userHandle]. */
    suspend fun signalUserDetails(rpId: String, userHandle: String, name: String) {}
}
