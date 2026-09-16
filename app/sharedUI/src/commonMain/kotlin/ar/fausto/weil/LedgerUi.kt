package ar.fausto.weil

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.account_name_label
import weil.app.sharedui.generated.resources.action_add
import weil.app.sharedui.generated.resources.action_cancel
import androidx.compose.ui.unit.dp
import kotlin.math.abs
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

    /** Plain snackbar, no action (errors and one-off confirmations). */
    fun show(message: String) {
        val state = host ?: return
        scope.launch {
            try {
                state.showSnackbar(message)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
    }

    fun undoable(message: String, actionLabel: String = "Undo", onAction: suspend () -> Unit) {
        val state = host ?: return
        scope.launch {
            try {
                // Explicit Short: the default duration is Indefinite whenever
                // an actionLabel is passed, which left these hanging on screen.
                val shown = state.showSnackbar(
                    message,
                    actionLabel,
                    withDismissAction = false,
                    duration = SnackbarDuration.Short,
                )
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

/**
 * Transaction-row color for one posting: green when it adds to an asset
 * account, red only when it takes from one — that's the only sign flip a
 * user actually parses at a glance ("did my money grow or shrink"). Every
 * other posting (categories, liabilities, equity) is bookkeeping detail, not
 * a balance moving, so it stays neutral instead of inheriting the raw
 * debit/credit sign.
 */
@Composable
fun postingColor(accountType: AccountType?, minor: Long): Color = when {
    accountType != AccountType.Asset -> MaterialTheme.colorScheme.onSurface
    minor >= 0 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

/** Corner radius of the grouped list cards (accounts, devices, sections). */
internal val GroupRadius = 18.dp

/**
 * Skin of one row inside a grouped card: only the first/last row of a run get
 * rounded corners, and every row but the first carries an inset hairline. Rows
 * stay independent lazy items while reading as a single card.
 */
internal data class RowSkin(val shape: Shape, val container: Color, val divider: Boolean)

@Composable
internal fun rowSkin(first: Boolean, last: Boolean): RowSkin = RowSkin(
    shape = RoundedCornerShape(
        topStart = if (first) GroupRadius else 0.dp,
        topEnd = if (first) GroupRadius else 0.dp,
        bottomStart = if (last) GroupRadius else 0.dp,
        bottomEnd = if (last) GroupRadius else 0.dp,
    ),
    container = MaterialTheme.colorScheme.surfaceContainerLow,
    divider = !first,
)

/**
 * Right-aligned balance. Every commodity renders at the same [style] and the
 * same color rule — ARS and USD used to differ (one bold headline, the rest
 * demoted to a small muted line), which read as "the small one doesn't
 * matter." A multi-commodity account now costs one extra line per commodity
 * instead of a size difference.
 *
 * [signalNegative] is off for income/liability/equity, whose balances are
 * negative by bookkeeping convention — painting those red turned the whole
 * tree into a warning.
 */
@Composable
internal fun BalanceText(
    totals: Map<String, Long>,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleSmall,
    signalNegative: Boolean = true,
) {
    val entries = totals.entries.sortedByDescending { abs(it.value) }
    Column(horizontalAlignment = Alignment.End, modifier = modifier) {
        if (entries.isEmpty()) {
            Text(
                "—",
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        entries.forEach { (commodity, minor) ->
            Text(
                "$commodity ${formatMinorUnits(minor)}",
                style = style,
                color = if (signalNegative) amountColor(minor) else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Rounded tonal chip for totals and short status words. */
@Composable
internal fun ValuePill(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(shape = CircleShape, color = container, modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** Inline failure banner; every screen renders repository errors the same way. */
@Composable
internal fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Banner(
        message = message,
        icon = Icons.Filled.Warning,
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier,
    )
}

/** Inline success banner (device approved, credential revoked, …). */
@Composable
internal fun NoticeBanner(message: String, modifier: Modifier = Modifier) {
    Banner(
        message = message,
        icon = Icons.Filled.Check,
        container = MaterialTheme.colorScheme.secondaryContainer,
        content = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
    )
}

@Composable
private fun Banner(
    message: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = container,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = content)
        }
    }
}

/**
 * Read-only text field used as a tap-to-pick selector (accounts, parent,
 * category): same shape, border and label behavior as the editable fields
 * around it, with a transparent overlay making the whole field open a picker.
 *
 * The placeholder rides in the *value* slot, dimmed: an M3 field only shows
 * its real `placeholder` while focused, and a read-only picker never gets
 * focus, so an unpicked field used to display its label centered like a value
 * — next to a picked sibling with a floating label, the two read as different
 * widgets. Putting text in the value keeps every label floating and the
 * "nothing picked yet" state visible.
 */
@Composable
internal fun PickerField(
    label: String,
    value: String?,
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value ?: placeholder,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ExpandMore, contentDescription = null) },
            singleLine = true,
            textStyle = if (value == null) {
                LocalTextStyle.current.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            } else {
                LocalTextStyle.current
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onClick() },
        )
    }
}

/**
 * Inline "create an account" dialog: a name field and Save, nothing else —
 * used wherever a picker offers to create the thing it doesn't have yet
 * (an expense category from the quick screen or the import review, or any
 * account from the full editor's unrestricted picker).
 *
 * [type] fixed by the caller skips the type picker entirely (an expense
 * category from the quick screen has only one sensible type); passing null
 * shows a [TypeDropdown] so the full editor — whose account picker isn't
 * scoped to one type — can create an asset, a liability, anything.
 */
@Composable
internal fun CreateAccountDialog(
    title: String,
    type: AccountType?,
    accounts: AccountsRepository,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
    onCreated: suspend (id: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var pickedType by remember { mutableStateOf(type ?: AccountType.Expense) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val nameFocus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { nameFocus.requestFocus() }

    fun create() {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || busy) return
        busy = true
        scope.launch {
            try {
                val id = accounts.add(trimmed, type ?: pickedType)
                onCreated(id)
                onDismiss()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                onError(e.message ?: e.toString())
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.account_name_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { create() }),
                    modifier = Modifier.focusRequester(nameFocus),
                )
                if (type == null) {
                    Spacer(Modifier.height(12.dp))
                    TypeDropdown(initial = pickedType, onPick = { pickedType = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { create() }, enabled = name.isNotBlank() && !busy) {
                Text(stringResource(Res.string.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}
