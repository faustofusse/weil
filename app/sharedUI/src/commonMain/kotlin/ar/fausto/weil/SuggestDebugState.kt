package ar.fausto.weil

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The traces of `SuggestDebugScreen`, hoisted above the nav host for the same
 * reason as [JournalState]: a `remember` inside the screen dies the moment the
 * entry leaves composition, so «revisar y crear» and back re-ran the entire
 * pipeline — two model calls and ~20 s — to redraw something the user was
 * already looking at.
 *
 * Keyed by notification id and never evicted: the whole point of the bench is
 * to run one message and study it, and a handful of traces per session is
 * nothing. Re-running is explicit ([run] with `force`).
 *
 * The scope is the holder's own, not the composable's, so navigating away
 * mid-run doesn't cancel the call and come back to a fresh one.
 */
@Stable
class SuggestDebugState(private val suggestions: SuggestTracer) {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private val traces = mutableStateMapOf<String, SuggestTrace>()
    private val failures = mutableStateMapOf<String, String>()
    private val running = mutableStateMapOf<String, Boolean>()
    private val jobs = mutableMapOf<String, Job>()

    fun trace(id: String): SuggestTrace? = traces[id]

    fun failure(id: String): String? = failures[id]

    fun isRunning(id: String): Boolean = running[id] == true

    /**
     * Starts the pipeline for [id] unless there is already an answer or a call
     * in flight. [force] is the manual re-run: it drops what is cached and
     * asks again.
     */
    fun run(id: String, force: Boolean = false) {
        if (force) {
            jobs.remove(id)?.cancel()
        } else if (isRunning(id) || traces.containsKey(id) || failures.containsKey(id)) {
            return
        }
        traces.remove(id)
        failures.remove(id)
        running[id] = true
        jobs[id] = scope.launch {
            try {
                traces[id] = suggestions.traceNotification(id)
                running[id] = false
            } catch (e: CancellationException) {
                // Only [force] cancels, and it has already set the flag for
                // the run that replaces this one — clearing it here would
                // hide the new call's spinner.
                throw e
            } catch (e: Throwable) {
                failures[id] = e.message ?: e.toString()
                running[id] = false
            }
        }
    }
}
