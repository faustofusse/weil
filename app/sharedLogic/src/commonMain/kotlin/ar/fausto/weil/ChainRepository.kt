package ar.fausto.weil

/**
 * Client for the auth worker's sync-chain endpoints. Managing devices never
 * touches the database; everything rides on the auth session cookie.
 */
class ChainRepository(private val api: AuthApi, private val auth: AuthRepository) {

    suspend fun list(): List<ChainDevice> = api.chainDevices()

    suspend fun invite(): ChainInvite = api.chainInvite()

    suspend fun request(): ChainRequest = api.chainRequest()

    suspend fun requestStatus(requestId: String): ChainRequestStatus = api.chainRequestStatus(requestId)

    suspend fun approve(requestId: String) = api.chainApprove(requestId)

    suspend fun getEmail(): String? = api.getEmail()

    suspend fun setEmail(email: String) {
        api.setEmail(email)
    }

    /**
     * Revoking rotates the user's Turso tokens: the revoked device loses DB
     * access immediately, and every other device's JWT dies with it (they
     * recover via session/refresh). Revoking this device's own credential also
     * invalidates its session cookie server-side, so sign out locally.
     */
    suspend fun revoke(device: ChainDevice) {
        api.chainRevoke(device.id)
        if (device.current) {
            auth.signOutLocal()
        } else {
            try {
                auth.refresh()
            } catch (_: SessionExpired) {
                // refresh() already signed out locally
            }
        }
    }
}
