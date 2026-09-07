package ar.fausto.weil

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    fun revoke(device: ChainDevice) {
        scope.launch {
            busy = true
            error = null
            try {
                chain.revoke(device)
                // Revoking the current credential signs this device out
                // server-side; the auth state flip swaps the UI to login.
                if (!device.current) {
                    devices = chain.list()
                    notice = "${chainDeviceName(device)} revoked — its database access is gone"
                }
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    /** Opens the platform scanner and approves a pairing request QR. */
    fun scanAndApprove() {
        scope.launch {
            error = null
            notice = null
            val scanner = scanner()
            if (scanner == null) {
                error = "QR scanning is not available on this device"
                return@launch
            }
            busy = true
            try {
                val raw = scanner.scan() ?: return@launch
                val link = ChainLink.parse(raw, AuthConfig.SLUG)
                if (link == null || link.action != ChainLink.Action.Approve) {
                    error = "That QR code is not a pairing request"
                    return@launch
                }
                chain.approve(link.id)
                devices = chain.list()
                notice = "Device approved — finish pairing on the other device"
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

}

internal fun chainDeviceName(device: ChainDevice): String =
    device.displayName ?: device.label ?: "Passkey ${device.id.takeLast(6)}"
