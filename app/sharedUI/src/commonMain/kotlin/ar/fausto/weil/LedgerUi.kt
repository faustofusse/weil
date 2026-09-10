package ar.fausto.weil

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * App-scoped undo feedback. RootScreen installs the host state at startup;
 * screens fire undoable actions through [Feedback.undoable] from a scope that
 * survives the screen pop which immediately follows a destructive edit.
 */
object Feedback {
    var host: SnackbarHostState? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    fun undoable(message: String, actionLabel: String = "Undo", onAction: suspend () -> Unit) {
        val state = host ?: return
        scope.launch {
            try {
                val shown = state.showSnackbar(message, actionLabel, withDismissAction = false)
                if (shown == SnackbarResult.ActionPerformed) {
                    try {
                        onAction()
                    } catch (e: Throwable) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        state.showSnackbar(e.message ?: e.toString())
                    }
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
    }
}

/** Positive amounts readable, negatives (credits from the row's view) red. */
@Composable
fun amountColor(minor: Long): Color =
    if (minor >= 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
