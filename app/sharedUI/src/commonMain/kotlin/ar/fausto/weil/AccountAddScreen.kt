@file:OptIn(ExperimentalMaterial3Api::class)

package ar.fausto.weil

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.account_add_title
import weil.app.sharedui.generated.resources.account_choose_parent
import weil.app.sharedui.generated.resources.account_name_label
import weil.app.sharedui.generated.resources.account_parent_none
import weil.app.sharedui.generated.resources.account_parent_under
import weil.app.sharedui.generated.resources.action_back
import weil.app.sharedui.generated.resources.action_save

/**
 * Full-screen account creation, reached from Home's and the tree's "+" — a
 * modal read as a lightweight afterthought for something that outlives the
 * session (an account sticks around forever), and it clipped the parent
 * picker's own sheet awkwardly on small screens. The name field autofocuses
 * so typing starts the instant the screen appears, keyboard already up.
 */
@Composable
fun AccountAddScreen(
    state: LedgerState,
    initialType: AccountType?,
    onSaved: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(initialType ?: AccountType.Asset) }
    var parent by remember { mutableStateOf<AccountNode?>(null) }
    var pickingParent by remember { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { nameFocus.requestFocus() }

    val effectiveType = parent?.account?.type ?: type

    fun save() {
        if (name.isBlank() || state.busy) return
        state.addAccount(
            name.trim(),
            effectiveType,
            parent?.account?.id,
        )
        onSaved()
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.account_add_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
        bottomBar = {
            // Sticky above the keyboard, same shape as every other record bar.
            Surface(tonalElevation = 3.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Button(
                        onClick = { save() },
                        enabled = name.isNotBlank() && !state.busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                    ) {
                        if (state.busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(stringResource(Res.string.action_save))
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.account_name_label)) },
                singleLine = true,
                // Enter records right away — name + defaults is the common
                // case, reaching for the sticky button shouldn't be required.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(nameFocus),
            )
            Spacer(Modifier.height(12.dp))
            PickerField(
                label = stringResource(Res.string.account_choose_parent),
                value = parent?.let { stringResource(Res.string.account_parent_under, it.path.censored().displayPath()) },
                placeholder = stringResource(Res.string.account_parent_none),
                onClick = { pickingParent = true },
            )
            Spacer(Modifier.height(12.dp))
            // Always shown, even when the entry point suggested a type:
            // Home's "+" sits under the assets section so Activo is the right
            // default there, but a liability (credit card) is created from the
            // same button and hiding the dropdown forced a detour through the
            // tree screen. A picked parent locks the type to its own.
            TypeDropdown(
                initial = if (parent != null) parent!!.account.type else type,
                enabled = parent == null,
                onPick = { type = it },
            )
        }
    }

    if (pickingParent) {
        // Candidate parents follow the picked type, so the sheet never offers
        // a parent that would silently flip the account's type.
        val allowedType = parent?.account?.type ?: type
        AccountPickerSheet(
            tree = state.tree.filter { it.account.type == allowedType },
            title = stringResource(Res.string.account_choose_parent),
            exclude = emptySet(),
            onDismiss = { pickingParent = false },
        ) { picked ->
            parent = picked
            pickingParent = false
        }
    }
}
