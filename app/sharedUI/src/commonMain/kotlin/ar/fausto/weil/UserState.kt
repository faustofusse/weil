package ar.fausto.weil

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The user's display name, owned by the auth worker (it identifies the
 * account, not this app's data) and mirrored into [SettingsRepository] so the
 * greeting paints instantly, offline, on the very first frame — a name that
 * appears a second late reads as the screen correcting itself.
 *
 * Hoisted above the nav host: Home greets with it and Profile edits it, and
 * one edit updates both without a refetch.
 */
@Stable
class UserState(
    private val chain: ChainRepository,
    private val settings: SettingsRepository,
) {
    var name by mutableStateOf<String?>(null)
        private set
    var email by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var emailBusy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var emailError by mutableStateOf<String?>(null)
        private set

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var loaded = false
    private var emailLoaded = false

    /** Cache first, then the worker; a failed fetch leaves the cached name up. */
    fun load() {
        if (loaded) return
        loaded = true
        scope.launch {
            runCatching { settings.all()[NAME_KEY] }.getOrNull()?.let { name = it }
            busy = true
            try {
                val remote = chain.getName()
                name = remote
                settings.set(NAME_KEY, remote)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Offline is not an error worth showing next to a greeting.
            } finally {
                busy = false
            }
        }
    }

    /** Same cache-first shape as [load], kept separate: the email has its own
     * error banner in Profile and no greeting depends on it. */
    fun loadEmail() {
        if (emailLoaded) return
        emailLoaded = true
        scope.launch {
            runCatching { settings.all()[EMAIL_KEY] }.getOrNull()?.let { email = it }
            emailBusy = true
            try {
                val remote = chain.getEmail()
                email = remote
                remote?.let { settings.set(EMAIL_KEY, it) }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                emailError = e.message ?: e.toString()
            } finally {
                emailBusy = false
            }
        }
    }

    fun setEmail(value: String, onDone: () -> Unit = {}) {
        scope.launch {
            emailBusy = true
            emailError = null
            try {
                chain.setEmail(value)
                val stored = chain.getEmail()
                email = stored
                stored?.let { settings.set(EMAIL_KEY, it) }
                onDone()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                emailError = e.message ?: e.toString()
            } finally {
                emailBusy = false
            }
        }
    }

    /** Blank clears the name (the greeting drops back to a plain "Hola"). */
    fun setName(value: String, onDone: () -> Unit = {}) {
        val trimmed = value.trim()
        scope.launch {
            busy = true
            error = null
            try {
                chain.setName(trimmed)
                // Read back rather than trusting the draft: the worker
                // normalizes (collapse whitespace, 40-char cap), and the
                // greeting should show what is actually stored.
                val stored = chain.getName()
                name = stored
                settings.set(NAME_KEY, stored)
                onDone()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    companion object {
        /** Local mirror of the worker's value; the worker stays the source of truth. */
        const val NAME_KEY = "profile.name"
        const val EMAIL_KEY = "profile.email"
    }
}
