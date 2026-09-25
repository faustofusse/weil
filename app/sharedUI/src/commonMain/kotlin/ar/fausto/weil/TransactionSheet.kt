@file:OptIn(ExperimentalMaterial3Api::class)

package ar.fausto.weil

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import ar.fausto.weil.AccountType
import ar.fausto.weil.AccountsRepository
import ar.fausto.weil.DraftPosting
import ar.fausto.weil.Money
import ar.fausto.weil.SettingsRepository
import ar.fausto.weil.TransactionsRepository
import ar.fausto.weil.epochMillis
import ar.fausto.weil.resolveDefault
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.picker_create
import weil.app.sharedui.generated.resources.quick_category_label
import weil.app.sharedui.generated.resources.quick_choose
import weil.app.sharedui.generated.resources.quick_error_amount
import weil.app.sharedui.generated.resources.quick_expense_title
import weil.app.sharedui.generated.resources.quick_from_label
import weil.app.sharedui.generated.resources.quick_income_title
import weil.app.sharedui.generated.resources.quick_record
import weil.app.sharedui.generated.resources.quick_source_label
import weil.app.sharedui.generated.resources.quick_to_label
import weil.app.sharedui.generated.resources.quick_transfer_from_label
import weil.app.sharedui.generated.resources.quick_transfer_title
import weil.app.sharedui.generated.resources.sheet_description_placeholder
import weil.app.sharedui.generated.resources.sheet_more
import weil.app.sharedui.generated.resources.sheet_new_title
import weil.app.sharedui.generated.resources.sheet_suggestion_confidence

/**
 * Entering a movement, presented as a panel that rises out of the bottom bar
 * and stops under the header.
 *
 * It is *not* a nav route: a route would cross-fade or slide sideways like
 * every other destination, and the whole point is that the create button
 * grows into the surface it sits on — same mint, same rounded top edge, the
 * header of the screen behind still visible so this reads as a layer over the
 * app rather than a place you travelled to. Back closes it without touching
 * the back stack, so a tab is never left behind by dismissing a form.
 *
 * The older [TransactionQuickScreen] (a full destination with its own app
 * bar) is untouched and still routable; this is the one the bar opens.
 *
 * While the description is typed, [suggester] guesses the category from it —
 * see [TransactionSheetForm] for how little it is allowed to touch.
 */
@Composable
fun TransactionSheet(
    visible: Boolean,
    state: LedgerState,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onMore: () -> Unit = onDismiss,
    suggester: CategorySuggester? = null,
) {
    // Back closes the panel instead of leaving the tab behind it: it is not
    // on the back stack, so nothing else would have answered.
    val backState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(state = backState, isBackEnabled = visible, onBackCompleted = onDismiss)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // Tracks a drag-to-dismiss offset on top of the enter/exit slide: the
    // handle at the top of the panel is a real drag target, like any modal
    // sheet, not just a decoration. Reset whenever the panel is (re)opened
    // so a previous drag never leaks into the next appearance.
    val dragOffset = remember { Animatable(0f) }
    LaunchedEffect(visible) { if (visible) dragOffset.snapTo(0f) }
    Box(modifier = Modifier.fillMaxSize()) {
        // Dim only what stays visible (the header strip): the panel itself
        // covers everything else, so a full-screen scrim would just darken
        // the app for the length of the animation.
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(SheetEnterMs)),
            exit = fadeOut(tween(SheetExitMs)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                    // Tapping the visible header closes it; no ripple, this
                    // is a dismiss area, not a control.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                // What the panel leaves uncovered: the status bar and one
                // header's worth of the screen behind.
                .statusBarsPadding()
                .padding(top = TopBarHeight)
                // The keyboard shortens the panel instead of pushing it: the
                // panel *is* the window here, and anything below the keyboard
                // is unreachable anyway. Inside the form this was the bug —
                // the content shrank but the mint stayed full height, leaving
                // a keyboard-sized empty band under the record button.
                .imePadding(),
        ) {
            AnimatedVisibility(
                visible = visible,
                modifier = Modifier.align(Alignment.BottomCenter),
                // Rises from below the screen's edge, which is where the bar
                // (and its create button) is.
                enter = slideInVertically(
                    animationSpec = tween(SheetEnterMs, easing = FastOutSlowInEasing),
                    initialOffsetY = { it },
                ),
                exit = slideOutVertically(
                    animationSpec = tween(SheetExitMs, easing = FastOutSlowInEasing),
                    targetOffsetY = { it },
                ),
            ) {
                Surface(
                    // Mint, like the bar it grew out of, and the same corner
                    // radius the bar rounds its top with.
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(topStart = BarCorner, topEnd = BarCorner),
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { IntOffset(0, dragOffset.value.roundToInt()) },
                ) {
                    val onSheetDrag: (Float) -> Unit = { delta ->
                        scope.launch {
                            dragOffset.snapTo((dragOffset.value + delta).coerceAtLeast(0f))
                        }
                    }
                    val onSheetDragEnd: () -> Unit = {
                        scope.launch {
                            val thresholdPx = with(density) { DragDismissThreshold.toPx() }
                            if (dragOffset.value > thresholdPx) {
                                // Carry the panel the rest of the way off screen so the
                                // exit doesn't jump back to zero before sliding out.
                                val offscreenPx = with(density) { 1200.dp.toPx() }
                                dragOffset.animateTo(offscreenPx, tween(SheetExitMs))
                                onDismiss()
                            } else {
                                dragOffset.animateTo(0f, tween(200))
                            }
                        }
                    }
                    TransactionSheetForm(
                        state = state,
                        onMore = onMore,
                        onSaved = onSaved,
                        suggester = suggester,
                        onSheetDrag = onSheetDrag,
                        onSheetDragEnd = onSheetDragEnd,
                    )
                }
            }
        }
    }
}

/** Which of the two legs a picker is being opened for. */
private enum class SheetSide { From, To }

private const val SheetEnterMs = 320
private const val SheetExitMs = 220

/** How far down the handle has to move before it counts as a dismiss. */
private val DragDismissThreshold = 96.dp

/**
 * The grip at the top of the panel, dragged like any modal sheet's handle.
 * The title next to it shares the same gesture ([sheetDrag]) so the whole
 * header reads as grabbable, not just a 4dp sliver; "Ver más" stays out of
 * it by being a sibling rather than nested inside the draggable area, so
 * there is no gesture arbitration between a tap and a drag.
 */
@Composable
private fun SheetDragHandle(onDrag: (Float) -> Unit, onDragEnd: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .sheetDrag(onDrag, onDragEnd)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 36.dp, height = 4.dp)
                .background(
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f),
                    RoundedCornerShape(2.dp),
                ),
        )
    }
}

/** The vertical-drag detector [SheetDragHandle] and the title share. */
private fun Modifier.sheetDrag(onDrag: (Float) -> Unit, onDragEnd: () -> Unit): Modifier =
    pointerInput(Unit) {
        detectVerticalDragGestures(
            onVerticalDrag = { change, dragAmount ->
                change.consume()
                onDrag(dragAmount)
            },
            onDragEnd = onDragEnd,
            onDragCancel = onDragEnd,
        )
    }

/** Below this a description is not yet a word worth spending a call on. */
private const val SuggestMinChars = 3

/** Long enough that a normal typing rhythm produces one call, not six. */
private const val SuggestDebounceMs = 450L

/**
 * There is no confidence threshold, on purpose. Confidence measures how
 * concentrated the probability is across the categories, not how right the
 * top one is: "panadería" spreads over Comida, Supermercado and Otros and
 * still picks the intended category at 22%. A low number means the model
 * hesitated between plausible neighbours, and the alternative to its pick is
 * the seeded default, which is not better for having been chosen by nobody.
 *
 * What does gate this is the question itself: the Choice carries an explicit
 * "ninguna de estas categorías", so the model declines instead of guessing
 * when the text says nothing — and the pick is one tap to override, after
 * which the guessing stops for the entry. The row still reports the
 * confidence, now purely as information.
 */

/** The last guess, kept so the row can report what the model was sure of. */
private data class GuessReadout(val path: String?, val confidence: Double)

@Composable
private fun TransactionSheetForm(
    state: LedgerState,
    onMore: () -> Unit,
    onSaved: () -> Unit,
    suggester: CategorySuggester? = null,
    onSheetDrag: (Float) -> Unit = {},
    onSheetDragEnd: () -> Unit = {},
) {
    val accounts = state.accounts
    val settings = state.settings
    var currentKind by remember { mutableStateOf(TxnKind.Expense) }
    val kindLabel = sheetKindTitle(currentKind)
    val accountLabel = stringResource(Res.string.quick_from_label)
    val fromLabel = when (currentKind) {
        TxnKind.Expense -> accountLabel
        TxnKind.Income -> stringResource(Res.string.quick_source_label)
        TxnKind.Transfer -> stringResource(Res.string.quick_transfer_from_label)
    }
    val toLabel = when (currentKind) {
        TxnKind.Expense -> stringResource(Res.string.quick_category_label)
        TxnKind.Income -> accountLabel
        TxnKind.Transfer -> stringResource(Res.string.quick_to_label)
    }
    val chooseLabel = stringResource(Res.string.quick_choose)
    val newCategoryLabel = stringResource(Res.string.picker_create)

    var amountText by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var fromId by remember { mutableStateOf<String?>(null) }
    var toId by remember { mutableStateOf<String?>(null) }
    var tree by remember { mutableStateOf<List<AccountNode>>(emptyList()) }
    var defaults by remember { mutableStateOf<Map<AccountType, String>>(emptyMap()) }
    var paths by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var picking by remember { mutableStateOf<SheetSide?>(null) }
    var creatingCategory by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // The guess stops for good once the user picks a category themselves.
    var categoryTouched by remember { mutableStateOf(false) }
    var suggesting by remember { mutableStateOf(false) }
    var lastGuess by remember { mutableStateOf<GuessReadout?>(null) }
    val scope = rememberCoroutineScope()
    val amountFocus = remember { FocusRequester() }

    val money = Money.parse(amountText.trim(), Money.DEFAULT_COMMODITY)
    val amountValid = money != null && money.minorUnits > 0
    val canRecord = amountValid && fromId != null && toId != null

    suspend fun reloadTree() {
        defaults = settings.defaultAccounts()
        tree = accounts.tree()
        paths = tree.flatMap { it.selfAndDescendants }
            .associate { it.account.id to it.path.censored().displayPath() }
    }

    LaunchedEffect(Unit) {
        amountFocus.requestFocus()
        reloadTree()
    }

    // Same defaulting as the quick screen: the account marked default for
    // each type, else the seeded fallback. The pick the user would most
    // often not change anyway, and a wrong guess is one tap to fix.
    LaunchedEffect(tree, defaults, currentKind) {
        if (tree.isEmpty()) return@LaunchedEffect
        fun default(type: AccountType): String? = resolveDefault(tree, type, defaults[type])
        when (currentKind) {
            TxnKind.Expense -> {
                if (fromId == null) fromId = default(AccountType.Asset)
                if (toId == null) toId = default(AccountType.Expense)
            }
            TxnKind.Income -> {
                if (fromId == null) fromId = default(AccountType.Income)
                if (toId == null) toId = default(AccountType.Asset)
            }
            TxnKind.Transfer -> {
                if (fromId == null && toId == null) {
                    val from = default(AccountType.Asset)
                    fromId = from
                    toId = tree.filter { it.account.type == AccountType.Asset }
                        .firstOrNull { it.account.id != from }?.account?.id
                }
            }
        }
    }

    // Which leg is the category depends on the kind; a transfer has none
    // (both legs are the user's own accounts).
    val categoryType = when (currentKind) {
        TxnKind.Expense -> AccountType.Expense
        TxnKind.Income -> AccountType.Income
        TxnKind.Transfer -> null
    }
    // Parents included: the tree is organizational and a posting can name any
    // node, so filtering to leaves would hide "Comida" the moment it grows a
    // child.
    val categoryOptions = remember(tree, paths, categoryType) {
        if (categoryType == null) {
            emptyList()
        } else {
            tree.filter { it.account.type == categoryType }
                .flatMap { it.selfAndDescendants }
                .mapNotNull { node -> paths[node.account.id]?.let { CategoryOption(node.account.id, it) } }
        }
    }

    // Debounced category guess. Keying the effect on the text is both the
    // debounce and the cancellation: a keystroke drops the in-flight call, so
    // a late answer can never land on a description the user moved past.
    val amountForHint = amountText
    LaunchedEffect(description, currentKind, categoryOptions, categoryTouched) {
        val text = description.trim()
        if (suggester == null || categoryTouched || categoryOptions.isEmpty()) return@LaunchedEffect
        if (text.length < SuggestMinChars) {
            lastGuess = null
            return@LaunchedEffect
        }
        delay(SuggestDebounceMs)
        suggesting = true
        val guess = try {
            suggester.suggest(
                text = text,
                options = categoryOptions,
                kind = if (currentKind == TxnKind.Income) ImportDirection.Income else ImportDirection.Expense,
                amount = if (amountValid) "${Money.DEFAULT_COMMODITY} $amountForHint" else null,
            )
        } finally {
            suggesting = false
        }
        // A null account is the model answering "none of these", which is a
        // real answer: the default in the picker stands, and there is no
        // confidence worth reporting because no category was named.
        val id = guess?.accountId
        if (guess == null || id == null) {
            lastGuess = null
            return@LaunchedEffect
        }
        when (currentKind) {
            TxnKind.Expense -> toId = id
            TxnKind.Income -> fromId = id
            TxnKind.Transfer -> return@LaunchedEffect
        }
        lastGuess = GuessReadout(guess.path, guess.confidence)
    }

    fun switchKind(next: TxnKind) {
        if (next == currentKind) return
        // The asset side survives the switch: the account is the part the
        // user already chose, only its role changes.
        val asset = when (currentKind) {
            TxnKind.Expense, TxnKind.Transfer -> fromId
            TxnKind.Income -> toId
        }
        fromId = null
        toId = null
        when (next) {
            TxnKind.Expense, TxnKind.Transfer -> fromId = asset
            TxnKind.Income -> toId = asset
        }
        error = null
        lastGuess = null
        currentKind = next
    }

    fun record() {
        val amount = money ?: return
        val from = fromId ?: return
        val to = toId ?: return
        if (!canRecord) return
        error = null
        val drafts = listOf(
            DraftPosting(from, formatMinorUnits(-amount.minorUnits)),
            DraftPosting(to, formatMinorUnits(amount.minorUnits)),
        )
        // Returns as soon as the row is on screen; the insert runs in the
        // background (see LedgerState.record), so the panel closes on the
        // tap instead of on the database. Only the synchronous half — the
        // balance check — can still fail here, and that keeps the form open.
        try {
            state.record(epochMillis(), description.trim().ifBlank { kindLabel }, null, drafts)
        } catch (e: LedgerValidationException) {
            error = e.message ?: e.toString()
            return
        }
        onSaved()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SheetDragHandle(onDrag = onSheetDrag, onDragEnd = onSheetDragEnd)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(Res.string.sheet_new_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.inverseSurface,
                modifier = Modifier
                    .weight(1f)
                    .sheetDrag(onSheetDrag, onSheetDragEnd),
            )
            // "Ver más" instead of a close button: the panel already closes
            // by tapping the header above it or pressing back, and the thing
            // this form cannot do — a date, a note, more than two postings —
            // is exactly one tap away in the full editor.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fadeOnPress(onMore).padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Text(
                    stringResource(Res.string.sheet_more),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.inverseSurface,
                )
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.inverseSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.padding(top = 10.dp))
        // Only the fields scroll; the title stays put and the record button
        // stays reachable, which is what a weighted scroll region buys that
        // a scrolling column with a weighted spacer in it does not (that was
        // the other half of the keyboard bug).
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            KindSwitcher(current = currentKind, onSelect = { switchKind(it) })
            Spacer(Modifier.padding(top = 12.dp))

            // Account and amount share one row, as in the design: "this much,
            // out of that account" is a single thought, and splitting it in
            // two boxes made the panel a four-row form for what is really two
            // decisions plus a name.
            SheetRow {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fadeOnPress { picking = SheetSide.From }
                        .padding(start = 12.dp, end = 4.dp)
                        .weight(1f),
                ) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.inverseSurface,
                        modifier = Modifier.size(20.dp).rotate(90f),
                    )
                    Spacer(Modifier.padding(start = 6.dp))
                    Text(
                        fromId?.let { paths[it] } ?: chooseLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.inverseSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // On an Ingreso this row *is* the category (the source of
                    // the money), so the guess reports itself here.
                    if (suggesting && categoryType == AccountType.Income) {
                        Spacer(Modifier.padding(start = 8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.6f),
                        )
                    }
                }
                // The amount writes itself into the row instead of onto a
                // canvas of its own: focused on open, so the keyboard is
                // already pointed at the only field that has no default.
                TextField(
                    value = amountText,
                    onValueChange = { amountText = sanitizeAmountInput(it, allowNegative = false) },
                    placeholder = {
                        Text(
                            "$" + formatMinorUnits(0),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.45f),
                            textAlign = TextAlign.End,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    // The symbol rides along inside the transformation rather
                    // than in `prefix`: a prefix slot is pinned to the left
                    // edge of the field, so with right-aligned digits the "$"
                    // ended up marooned in the middle of the row.
                    visualTransformation = AmountWithSymbol,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge.copy(textAlign = TextAlign.End),
                    isError = amountText.isNotBlank() && !amountValid,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        errorContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent,
                        focusedTextColor = MaterialTheme.colorScheme.inverseSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.inverseSurface,
                        cursorColor = MaterialTheme.colorScheme.inverseSurface,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(amountFocus),
                )
            }
            if (amountText.isNotBlank() && !amountValid) {
                Text(
                    stringResource(Res.string.quick_error_amount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 6.dp),
                )
            }

            // Description above the category, because it is what *feeds* the
            // category now: you type what you bought and watch the row below
            // settle on where it goes.
            Spacer(Modifier.padding(top = 10.dp))
            SheetRow {
                TextField(
                    value = description,
                    onValueChange = { description = it },
                    placeholder = {
                        Text(
                            stringResource(Res.string.sheet_description_placeholder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.55f),
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { record() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = MaterialTheme.colorScheme.inverseSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.inverseSurface,
                        cursorColor = MaterialTheme.colorScheme.inverseSurface,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            }

            Spacer(Modifier.padding(top = 10.dp))
            SheetRow {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fadeOnPress { picking = SheetSide.To }
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                ) {
                    Text(
                        toLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.inverseSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        toId?.let { paths[it] } ?: chooseLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (suggesting) {
                        Spacer(Modifier.padding(start = 8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.6f),
                        )
                    }
                }
            }
            // How sure the model was about the category now in the row.
            // Kept visible after the threshold was removed (a 22% pick was
            // right often enough to make a cutoff cost more than it saved),
            // both as the explanation for a row that moved on its own and as
            // running evidence about the question's wording.
            lastGuess?.let { guess ->
                Text(
                    stringResource(
                        Res.string.sheet_suggestion_confidence,
                        (guess.confidence * 100).roundToInt().toString(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 6.dp),
                )
            }
        }

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Button(
            onClick = { record() },
            enabled = canRecord,
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars)
                // 52, not the design's ~80: it is the last thing you touch,
                // not the thing you look at, and a slab that tall reads as a
                // second surface rather than a button.
                .heightIn(min = 52.dp),
        ) {
            // No spinner state: recording no longer waits on the database.
            Text(
                stringResource(Res.string.quick_record),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        }
    }

    picking?.let { field ->
        val pickerType = when (currentKind) {
            TxnKind.Expense -> if (field == SheetSide.From) AccountType.Asset else AccountType.Expense
            TxnKind.Income -> if (field == SheetSide.From) AccountType.Income else AccountType.Asset
            TxnKind.Transfer -> AccountType.Asset
        }
        val exclude = when {
            currentKind != TxnKind.Transfer -> emptySet()
            field == SheetSide.From -> listOfNotNull(toId).toSet()
            else -> listOfNotNull(fromId).toSet()
        }
        val isCategory = pickerType == AccountType.Expense
        AccountPickerSheet(
            tree = tree.filter { it.account.type == pickerType },
            title = if (field == SheetSide.From) fromLabel else toLabel,
            exclude = exclude,
            createLabel = if (isCategory) newCategoryLabel else null,
            onCreate = if (isCategory) {
                {
                    picking = null
                    creatingCategory = true
                }
            } else {
                null
            },
            onDismiss = { picking = null },
        ) { picked ->
            if (field == SheetSide.From) fromId = picked.account.id else toId = picked.account.id
            if (picked.account.type == categoryType) {
                categoryTouched = true
                lastGuess = null
            }
            picking = null
        }
    }

    if (creatingCategory) {
        CreateAccountDialog(
            title = newCategoryLabel,
            type = AccountType.Expense,
            accounts = accounts,
            onDismiss = { creatingCategory = false },
            onError = { error = it },
            onCreated = { id ->
                toId = id
                categoryTouched = true
                lastGuess = null
                reloadTree()
            },
        )
    }
}

/**
 * Gasto / Ingreso / Traspaso as one segmented track: three separate pills read
 * as three buttons that each do something, while a single track with one lit
 * segment reads as a choice among three states of the same form.
 */
@Composable
private fun KindSwitcher(current: TxnKind, onSelect: (TxnKind) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondary,
        shape = RoundedCornerShape(SegmentCorner),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TxnKind.entries.forEach { kind ->
                val selected = kind == current
                Surface(
                    color = if (selected) {
                        MaterialTheme.colorScheme.inverseSurface
                    } else {
                        Color.Transparent
                    },
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shape = RoundedCornerShape(SegmentCorner),
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable { onSelect(kind) }
                            .heightIn(min = 46.dp)
                            .fillMaxWidth(),
                    ) {
                        Icon(
                            kindIcon(kind),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.padding(start = 6.dp))
                        Text(
                            sheetKindTitle(kind),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

/** Direction, as a glyph: down-left out of the wallet, up-right into it. */
private fun kindIcon(kind: TxnKind) = when (kind) {
    TxnKind.Expense -> Icons.Filled.ArrowDownLeft
    TxnKind.Income -> Icons.Filled.ArrowUpRight
    TxnKind.Transfer -> Icons.Filled.Remove
}

private val SegmentCorner = 24.dp

/** Grouped digits with the peso sign glued to their left. */
/** Shared with the full editor, which draws the same money in the same slab. */
internal val AmountWithSymbol = amountWithSymbol("$")

/** Grouped digits with [symbol] glued to their left ("US$-99,98" in a dollar row). */
internal fun amountWithSymbol(symbol: String) = VisualTransformation { text ->
    // Empty stays empty: a lone "$" counts as content and would suppress the
    // placeholder, leaving the row showing a symbol and nothing else.
    if (text.text.isEmpty()) {
        TransformedText(text, OffsetMapping.Identity)
    } else {
        val grouped = AmountVisualTransformation.filter(text)
        val inner = grouped.offsetMapping
        val n = symbol.length
        TransformedText(
            AnnotatedString(symbol) + grouped.text,
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = inner.originalToTransformed(offset) + n
                override fun transformedToOriginal(offset: Int) =
                    inner.transformedToOriginal((offset - n).coerceAtLeast(0))
            },
        )
    }
}

/** The translucent slab every field of the panel (and of the editor) sits on. */
@Composable
internal fun SheetRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Surface(
        // A tint of the dark ink over the mint, not a solid grey: the field
        // has to belong to the panel it floats on, and an opaque colour would
        // have been a fourth surface in a screen that only has the mint.
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.22f),
        contentColor = MaterialTheme.colorScheme.inverseSurface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = 64.dp),
            content = content,
        )
    }
}

/**
 * Tap feedback for the halves of a slab row: a fade of the row's own content
 * instead of a ripple.
 *
 * A ripple is bounded by the clickable's layout, and these clickables are a
 * text and an icon inside a 64.dp slab — the splash came out as a thin
 * rectangle hugging the glyphs, which reads as a rendering fault rather than
 * as a press. Fading what was touched says the same thing and respects the
 * shape the slab actually has.
 */
@Composable
internal fun Modifier.fadeOnPress(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (pressed) 0.45f else 1f,
        // Quick in, slower out: the press should register instantly, the
        // release should not snap.
        animationSpec = tween(if (pressed) 90 else 180),
    )
    return this
        .graphicsLayer { this.alpha = alpha }
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

@Composable
private fun sheetKindTitle(kind: TxnKind): String = stringResource(
    when (kind) {
        TxnKind.Expense -> Res.string.quick_expense_title
        TxnKind.Income -> Res.string.quick_income_title
        TxnKind.Transfer -> Res.string.quick_transfer_title
    },
)
