package ar.fausto.weil

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * WhatsApp linking state for the profile screen. Hoisted next to [ChainState]
 * for the same reason: a pending code has a deadline, and navigating away and
 * back should not silently restart it.
 */
@Stable
class WhatsappState(private val repo: WhatsappRepository) {
    var numbers by mutableStateOf<List<WhatsappNumber>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loaded by mutableStateOf(false)
        private set

    /** Non-null while a pairing code is pending; the user must send it. */
    var code by mutableStateOf<WhatsappLinkCode?>(null)
        private set

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    fun refresh() {
        scope.launch {
            busy = true
            error = null
            try {
                numbers = repo.numbers()
                loaded = true
                // The code is consumed by the act of sending it; once a number
                // shows up there is nothing left to display.
                if (numbers.isNotEmpty()) code = null
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    fun startLink() {
        scope.launch {
            busy = true
            error = null
            try {
                code = repo.createCode()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    fun cancelLink() {
        code = null
    }

    fun unlink(number: WhatsappNumber) {
        scope.launch {
            busy = true
            error = null
            try {
                repo.unlink(number.number)
                numbers = numbers.filterNot { it.number == number.number }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }
}
