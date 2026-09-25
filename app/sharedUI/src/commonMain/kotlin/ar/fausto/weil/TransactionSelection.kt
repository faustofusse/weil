package ar.fausto.weil

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.action_cancel
import weil.app.sharedui.generated.resources.action_delete
import weil.app.sharedui.generated.resources.action_undo
import weil.app.sharedui.generated.resources.journal_delete_failed
import weil.app.sharedui.generated.resources.journal_delete_selected
import weil.app.sharedui.generated.resources.journal_delete_selected_body
import weil.app.sharedui.generated.resources.journal_delete_selected_title
import weil.app.sharedui.generated.resources.journal_selected_count

/**
 * A multi-select run over a list of movements: entered by long-pressing a
 * row, after which every tap toggles. Shared by Movimientos, the account
 * register and the category screen, so the gesture means the same thing in
 * every list of transactions.
 */
@Stable
class TransactionSelection {
    private val ids = mutableStateListOf<String>()

    /** Ids currently ticked, in pick order. */
    val selected: List<String> get() = ids.toList()

    val size: Int get() = ids.size

    /** True once a long-press has started a selection run. */
    val isSelecting: Boolean get() = ids.isNotEmpty()

    operator fun contains(id: String): Boolean = id in ids

    fun toggle(id: String) {
        if (id in ids) ids.remove(id) else ids.add(id)
    }

    fun clear() = ids.clear()

    /** Drops ticks on rows no longer on screen (a sync removed them). */
    fun retain(known: Set<String>) {
        ids.removeAll { it !in known }
    }
}

/** Everything needed to re-create a deleted transaction. */
data class TransactionBackup(val tx: Transaction, val sources: List<TransactionSource>)

/**
 * Hard-deletes [ids] in one transaction and returns what was removed, so
 * [restoreBackups] can put it back verbatim (date, payee, note, postings with
 * their cost, provenance) — undo as a true inverse.
 */
suspend fun TransactionsRepository.deleteWithBackup(ids: List<String>): List<TransactionBackup> {
    if (ids.isEmpty()) return emptyList()
    val backup = ids.mapNotNull { get(it) }
    val sources = sourcesFor(ids)
    deleteAll(ids)
    return backup.map { tx ->
        TransactionBackup(tx, sources[tx.id].orEmpty().map { s -> TransactionSource(s.kind, s.ref, s.eventKey) })
    }
}

/** The Deshacer half of [deleteWithBackup]. */
suspend fun TransactionsRepository.restoreBackups(backups: List<TransactionBackup>) {
    if (backups.isEmpty()) return
    addAll(
        backups.map { b ->
            NewTransaction(
                date = b.tx.date,
                payee = b.tx.payee,
                note = b.tx.note,
                drafts = b.tx.postings.map { it.toDraft() },
                timeKnown = b.tx.timeKnown,
                sources = b.sources,
            )
        },
    )
}

/** System back ends a selection run before it leaves the screen. */
@Composable
internal fun SelectionBackHandler(selection: TransactionSelection) {
    val backState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = backState,
        isBackEnabled = selection.isSelecting,
        onBackCompleted = { selection.clear() },
    )
}

/** Top-bar title while selecting: "3 seleccionadas". */
@Composable
internal fun selectionTitle(selection: TransactionSelection): String =
    stringResource(Res.string.journal_selected_count, selection.size)

/** The top-bar delete action shown while selecting. */
@Composable
internal fun DeleteSelectionAction(count: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            Icons.Filled.Delete,
            contentDescription = stringResource(Res.string.journal_delete_selected, count),
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * Confirm-and-delete for a selection run: one database transaction for the
 * whole batch, then the Snackbar carries every removed row back.
 */
@Composable
internal fun DeleteSelectedDialog(
    count: Int,
    delete: suspend () -> List<TransactionBackup>,
    restore: suspend (List<TransactionBackup>) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf(false) }
    val undoLabel = stringResource(Res.string.action_undo)
    val deletedMessage = stringResource(Res.string.journal_selected_count, count)
    val failedMessage = stringResource(Res.string.journal_delete_failed)

    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text(stringResource(Res.string.journal_delete_selected_title, count)) },
        text = {
            Text(
                stringResource(Res.string.journal_delete_selected_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    deleting = true
                    scope.launch {
                        try {
                            val backups = delete()
                            onDismiss()
                            if (backups.isNotEmpty()) {
                                Feedback.undoable(deletedMessage, undoLabel) { restore(backups) }
                            }
                        } catch (e: Throwable) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Feedback.show(failedMessage)
                            deleting = false
                        }
                    }
                },
                enabled = !deleting && count > 0,
            ) { Text(stringResource(Res.string.action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}
