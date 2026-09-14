package ar.fausto.weil

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Sync-chain state for the profile screen: device list plus invite/approve
 * flows. Hoisted next to [AccountsState] so the list and a pending invite QR
 * survive navigating away and back. */
@Stable
class ChainState(
    private val chain: ChainRepository,
    private val scanner: () -> QrScanner?,
) {
    var devices by mutableStateOf<List<ChainDevice>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var loaded by mutableStateOf(false)
        private set

    /** Non-null while this device is showing an invite QR. */
    var invite by mutableStateOf<ChainInvite?>(null)
        private set

    /** Wall-clock deadline of the current invite, for the countdown. */
    var inviteExpiresAt by mutableStateOf(0L)
        private set

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    fun refresh() {
        scope.launch {
            busy = true
            error = null
            try {
                devices = chain.list()
                loaded = true
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    fun startInvite() {
        scope.launch {
            busy = true
            error = null
            notice = null
            try {
                val created = chain.invite()
                invite = created
                inviteExpiresAt = epochMillis() + created.expiresIn * 1000
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    fun cancelInvite() {
        invite = null
        inviteExpiresAt = 0L
    }

    /** [revokedNotice] comes pre-formatted from the composable layer (i18n). */
    fun revoke(device: ChainDevice, revokedNotice: String) {
        scope.launch {
            busy = true
            error = null
            try {
                chain.revoke(device)
                // Revoking the current credential signs this device out
                // server-side; the auth state flip swaps the UI to login.
                if (!device.current) {
                    devices = chain.list()
                    notice = revokedNotice
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    /** Opens the platform scanner and approves a pairing request QR. The
     * message strings come pre-formatted from the composable layer (i18n). */
    fun scanAndApprove(
        qrUnavailable: String,
        notPairingRequest: String,
        approvedNotice: String,
    ) {
        scope.launch {
            error = null
            notice = null
            val scanner = scanner()
            if (scanner == null) {
                error = qrUnavailable
                return@launch
            }
            busy = true
            var previousCount: Int? = null
            try {
                val raw = scanner.scan() ?: return@launch
                val link = ChainLink.parse(raw, AuthConfig.SLUG)
                if (link == null || link.action != ChainLink.Action.Approve) {
                    error = notPairingRequest
                    return@launch
                }
                previousCount = devices.size
                chain.approve(link.id)
                devices = chain.list()
                notice = approvedNotice
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
            // Approving only flags the request server-side; the new device's
            // credential row is created afterwards by its own chain/join call,
            // once it finishes its passkey ceremony. That list() above can
            // easily be too early, so poll quietly in the background (no
            // `busy`, buttons stay enabled) instead of leaving the list stale
            // until the user reopens the screen.
            previousCount?.let { pollForNewDevice(it) }
        }
    }

    private suspend fun pollForNewDevice(previousCount: Int) {
        repeat(10) { // ~20s: covers a typical biometric prompt + join round trip
            delay(2000)
            try {
                val updated = chain.list()
                devices = updated
                if (updated.size > previousCount) return
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // transient network hiccup — keep polling; refresh() still covers
                // anything left once the loop ends.
            }
        }
    }
}

/** Label for a device; the localized passkey fallback is supplied by the caller. */
internal fun chainDeviceName(device: ChainDevice, passkeyFallback: String): String =
    device.displayName ?: device.label ?: passkeyFallback
