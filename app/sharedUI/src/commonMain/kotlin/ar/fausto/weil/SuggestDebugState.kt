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
 * Keyed by door **and** id and never evicted: the whole point of the bench is
 * to run one message and study it, and a handful of traces per session is
 * nothing. The door is part of the key because the two tables have their own
 * id spaces — nothing says a notification and an email cannot collide, and a
 * trace shown under the wrong message is the one bug a bench must not have.
 * Re-running is explicit ([run] with `force`).
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

    fun trace(source: EventSource, id: String): SuggestTrace? = traces[key(source, id)]

    fun failure(source: EventSource, id: String): String? = failures[key(source, id)]

    fun isRunning(source: EventSource, id: String): Boolean = running[key(source, id)] == true

    /**
     * Starts the pipeline for one message unless there is already an answer or
     * a call in flight. [force] is the manual re-run: it drops what is cached
     * and asks again.
     */
    fun run(source: EventSource, id: String, force: Boolean = false) {
        val key = key(source, id)
        if (force) {
            jobs.remove(key)?.cancel()
        } else if (isRunning(source, id) || traces.containsKey(key) || failures.containsKey(key)) {
            return
        }
        traces.remove(key)
        failures.remove(key)
        running[key] = true
        jobs[key] = scope.launch {
            try {
                traces[key] = when (source) {
                    EventSource.Email -> suggestions.traceEmail(id)
                    else -> suggestions.traceNotification(id)
                }
                running[key] = false
            } catch (e: CancellationException) {
                // Only [force] cancels, and it has already set the flag for
                // the run that replaces this one — clearing it here would
                // hide the new call's spinner.
                throw e
            } catch (e: Throwable) {
                failures[key] = e.message ?: e.toString()
                running[key] = false
            }
        }
    }

    private fun key(source: EventSource, id: String) = "${source.db}:$id"
}
