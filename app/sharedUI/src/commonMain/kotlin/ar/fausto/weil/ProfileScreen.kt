@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package ar.fausto.weil

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.qrose.options.QrBrush
import io.github.alexzhirkevich.qrose.options.solid
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.action_cancel
import weil.app.sharedui.generated.resources.action_save
import weil.app.sharedui.generated.resources.login_expires_in
import weil.app.sharedui.generated.resources.passkey_name
import weil.app.sharedui.generated.resources.profile_add_device
import weil.app.sharedui.generated.resources.profile_approve_device
import weil.app.sharedui.generated.resources.profile_contact_email_subtitle
import weil.app.sharedui.generated.resources.profile_name_edit
import weil.app.sharedui.generated.resources.profile_name_label
import weil.app.sharedui.generated.resources.profile_name_not_set
import weil.app.sharedui.generated.resources.profile_name_subtitle
import weil.app.sharedui.generated.resources.profile_name_title
import weil.app.sharedui.generated.resources.nav_profile
import weil.app.sharedui.generated.resources.profile_contact_email_title
import weil.app.sharedui.generated.resources.profile_device_approved
import weil.app.sharedui.generated.resources.profile_device_revoked
import weil.app.sharedui.generated.resources.profile_device_this
import weil.app.sharedui.generated.resources.profile_devices_count_many
import weil.app.sharedui.generated.resources.profile_devices_count_one
import weil.app.sharedui.generated.resources.profile_embeddings_pending
import weil.app.sharedui.generated.resources.profile_embeddings_progress
import weil.app.sharedui.generated.resources.profile_embeddings_rebuild
import weil.app.sharedui.generated.resources.profile_embeddings_run
import weil.app.sharedui.generated.resources.profile_embeddings_subtitle
import weil.app.sharedui.generated.resources.profile_embeddings_title
import weil.app.sharedui.generated.resources.profile_embeddings_up_to_date
import weil.app.sharedui.generated.resources.profile_email_edit
import weil.app.sharedui.generated.resources.profile_email_not_set
import weil.app.sharedui.generated.resources.profile_invite_expired
import weil.app.sharedui.generated.resources.profile_new_invite
import weil.app.sharedui.generated.resources.profile_no_devices
import weil.app.sharedui.generated.resources.profile_qr_content
import weil.app.sharedui.generated.resources.profile_qr_not_request
import weil.app.sharedui.generated.resources.profile_qr_scan_hint
import weil.app.sharedui.generated.resources.profile_qr_unavailable
import weil.app.sharedui.generated.resources.profile_revoke
import weil.app.sharedui.generated.resources.profile_revoke_current_body
import weil.app.sharedui.generated.resources.profile_revoke_notice
import weil.app.sharedui.generated.resources.profile_revoke_other_body
import weil.app.sharedui.generated.resources.profile_revoke_title
import weil.app.sharedui.generated.resources.profile_session_title
import weil.app.sharedui.generated.resources.profile_sign_out
import weil.app.sharedui.generated.resources.profile_sign_out_body
import weil.app.sharedui.generated.resources.profile_sign_out_title
import weil.app.sharedui.generated.resources.profile_sync_chain_subtitle
import weil.app.sharedui.generated.resources.profile_sync_chain_title
import weil.app.sharedui.generated.resources.profile_theme_inspect_hint
import weil.app.sharedui.generated.resources.profile_theme_roles_subtitle
import weil.app.sharedui.generated.resources.profile_theme_roles_title
import weil.app.sharedui.generated.resources.profile_theme_selected
import weil.app.sharedui.generated.resources.profile_theme_subtitle
import weil.app.sharedui.generated.resources.profile_theme_title
import weil.app.sharedui.generated.resources.profile_wa_code_hint
import weil.app.sharedui.generated.resources.profile_wa_expired
import weil.app.sharedui.generated.resources.profile_wa_hide_qr
import weil.app.sharedui.generated.resources.profile_wa_open
import weil.app.sharedui.generated.resources.profile_wa_show_qr
import weil.app.sharedui.generated.resources.profile_wa_link
import weil.app.sharedui.generated.resources.profile_wa_linked_none
import weil.app.sharedui.generated.resources.profile_wa_new_code
import weil.app.sharedui.generated.resources.profile_wa_number_missing
import weil.app.sharedui.generated.resources.profile_wa_qr_content
import weil.app.sharedui.generated.resources.profile_wa_scan_hint
import weil.app.sharedui.generated.resources.profile_wa_sent
import weil.app.sharedui.generated.resources.profile_wa_subtitle
import weil.app.sharedui.generated.resources.profile_wa_title
import weil.app.sharedui.generated.resources.profile_wa_unlink

/**
 * Profile: the contact email and the sync chain, each on its own tonal card,
 * with sign-out kept at the bottom behind a confirmation so it can't be hit
 * by accident from the top bar.
 */
@Composable
fun ProfileScreen(
    chain: ChainRepository,
    chainState: ChainState,
    whatsappState: WhatsappState,
    embeddings: EmbeddingsRepository,
    userState: UserState,
    settings: SettingsRepository,
    onNavigateBack: () -> Unit,
    onSignOut: () -> Unit,
) {
    var confirmSignOut by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            // Always pushed now (from the account icon in Inicio's top bar),
            // so always the shared header with its back arrow.
            AppTopBar(
                title = stringResource(Res.string.nav_profile),
                onNavigateBack = onNavigateBack,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 32.dp),
        ) {
            DisplayNameSection(userState)
            Spacer(Modifier.height(16.dp))
            AccountEmailSection(userState)
            Spacer(Modifier.height(16.dp))
            ChainSection(chainState)
            Spacer(Modifier.height(16.dp))
            WhatsappSection(whatsappState)
            Spacer(Modifier.height(16.dp))
            ThemeSection(settings)
            Spacer(Modifier.height(16.dp))
            EmbeddingsSection(embeddings)
            Spacer(Modifier.height(24.dp))
            SignOutSection(onClick = { confirmSignOut = true })
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(Res.string.profile_sign_out_title)) },
            text = { Text(stringResource(Res.string.profile_sign_out_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSignOut = false
                        onSignOut()
                    },
                ) {
                    Text(
                        stringResource(Res.string.profile_sign_out),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}

/** Tonal card with an icon-badged title, an optional explainer and a body. */
@Composable
private fun SectionCard(
    title: String,
    icon: ImageVector,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                trailing()
            }
            if (subtitle != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

/**
 * The palette picker. Swapping is one assignment on [AppThemeState] — the
 * whole app already renders inside `FinanceTheme(themeState.theme)` — and the
 * write to `settings` is what makes the choice outlive the process and reach
 * the paired devices. The write happens after the swap and its failure is
 * swallowed: a palette that didn't sync is a smaller problem than a tap that
 * appears to do nothing.
 */
@Composable
private fun ThemeSection(settings: SettingsRepository) {
    val themeState = LocalAppThemeState.current
    val scope = rememberCoroutineScope()
    SectionCard(
        title = stringResource(Res.string.profile_theme_title),
        icon = Icons.Filled.Tune,
        subtitle = stringResource(Res.string.profile_theme_subtitle),
    ) {
        val selectedLabel = stringResource(Res.string.profile_theme_selected)
        // Long-pressing a row opens its full role list. Kept as state here
        // rather than inside the row so only one sheet can ever be up.
        var inspecting by remember { mutableStateOf<AppTheme?>(null) }
        AppTheme.entries.forEachIndexed { index, theme ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            ThemeOptionRow(
                theme = theme,
                selected = theme == themeState.theme,
                selectedLabel = selectedLabel,
                onClick = {
                    if (theme != themeState.theme) {
                        themeState.theme = theme
                        scope.launch { runCatching { settings.set(THEME_KEY, theme.name) } }
                    }
                },
                onLongClick = { inspecting = theme },
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(Res.string.profile_theme_inspect_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        inspecting?.let { theme ->
            ThemeRolesSheet(theme = theme, onDismiss = { inspecting = null })
        }
    }
}

/**
 * Every color a palette assigns, with its Material role name and its hex.
 * The role names are deliberately **not** translated: they are the
 * identifiers a palette is edited by in `AppTheme.kt`, so a screenshot of
 * this sheet has to be pasteable back into the code.
 *
 */
@Composable
private fun ThemeRolesSheet(theme: AppTheme, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                stringResource(Res.string.profile_theme_roles_title, theme.displayName),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(Res.string.profile_theme_roles_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            // Not a LazyColumn: the sheet already scrolls, and nesting a
            // lazy list inside it gives the inner list an unbounded height.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                (theme.colorScheme.roles() + theme.money.roles()).forEach { role ->
                    ThemeRoleRow(role)
                }
            }
        }
    }
}

@Composable
private fun ThemeRoleRow(role: ThemeRole) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // A hairline border, because half these colors are by construction
        // close to the sheet's own surface: without an edge the swatch of
        // `surfaceContainerLow` reads as empty space, not as a color.
        Box(
            modifier = Modifier
                .size(width = 38.dp, height = 26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(role.color)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            role.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            role.color.hex(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * One palette, drawn in its own colors: the page, the hero and the account
 * tile of *that* theme, not of the active one — the swatch has to answer
 * "what would the app look like" before the tap, so it deliberately reads
 * [AppTheme.colorScheme] instead of `MaterialTheme`.
 */
@Composable
private fun ThemeOptionRow(
    theme: AppTheme,
    selected: Boolean,
    selectedLabel: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val scheme = theme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            // `combinedClickable` rather than `Surface(onClick = …)`: the
            // surface overload has no long-press, and the inspector hangs
            // off exactly the same row the tap applies.
            modifier = Modifier
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(scheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(scheme.primaryContainer))
                    Box(Modifier.size(10.dp).clip(CircleShape).background(scheme.inverseSurface))
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                theme.displayName,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = selectedLabel,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * The only part of "parecidos a este" that touches the network: the sweep that
 * fills the vectors. It lives in the profile because it spans the three tables
 * and this is already where maintenance lives (device chain, session).
 *
 * The sweep writes after each batch, so leaving the screen (which cancels the
 * coroutine) loses nothing but the batch in flight.
 */
@Composable
private fun EmbeddingsSection(embeddings: EmbeddingsRepository) {
    var pending by remember { mutableStateOf<Int?>(null) }
    var progress by remember { mutableStateOf<EmbedProgress?>(null) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refreshPending() {
        pending = runCatching { embeddings.pendingCount() }.getOrNull()
    }

    LaunchedEffect(Unit) { refreshPending() }

    fun sweep(invalidateFirst: Boolean) {
        if (running) return
        scope.launch {
            running = true
            progress = null
            try {
                if (invalidateFirst) embeddings.invalidateAll()
                embeddings.embedPending { progress = it }
                refreshPending()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Feedback.show(e.message ?: e.toString())
            } finally {
                running = false
                progress = null
            }
        }
    }

    SectionCard(
        title = stringResource(Res.string.profile_embeddings_title),
        icon = Icons.Filled.Search,
        subtitle = stringResource(Res.string.profile_embeddings_subtitle),
    ) {
        val status = when {
            running -> progress?.let {
                stringResource(Res.string.profile_embeddings_progress, it.done, it.total)
            }
            pending == null -> null
            pending == 0 -> stringResource(Res.string.profile_embeddings_up_to_date)
            else -> stringResource(Res.string.profile_embeddings_pending, pending ?: 0)
        }
        if (status != null) {
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { sweep(invalidateFirst = false) },
                enabled = !running,
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(Res.string.profile_embeddings_run), maxLines = 1)
                }
            }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(
                onClick = { sweep(invalidateFirst = true) },
                enabled = !running,
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Text(stringResource(Res.string.profile_embeddings_rebuild), maxLines = 1)
            }
        }
    }
}

@Composable
private fun SignOutSection(onClick: () -> Unit) {
    Text(
        stringResource(Res.string.profile_session_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        Icon(Icons.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(Res.string.profile_sign_out))
    }
}

@Composable
private fun ChainSection(state: ChainState) {
    var revoking by remember { mutableStateOf<ChainDevice?>(null) }
    val devices = state.devices
    val busy = state.busy
    val qrUnavailable = stringResource(Res.string.profile_qr_unavailable)
    val notPairingRequest = stringResource(Res.string.profile_qr_not_request)
    val approvedNotice = stringResource(Res.string.profile_device_approved)
    val cancelLabel = stringResource(Res.string.action_cancel)

    if (!state.loaded) {
        LaunchedEffect(Unit) { state.refresh() }
    }

    val active = devices.count { it.revokedAt == null }
    SectionCard(
        title = stringResource(Res.string.profile_sync_chain_title),
        icon = Icons.Filled.AccountTree,
        subtitle = stringResource(Res.string.profile_sync_chain_subtitle),
        trailing = {
            if (active > 0) {
                ValuePill(
                    if (active == 1) {
                        stringResource(Res.string.profile_devices_count_one)
                    } else {
                        stringResource(Res.string.profile_devices_count_many, active)
                    },
                )
            }
        },
    ) {
        state.notice?.let {
            NoticeBanner(it, modifier = Modifier.padding(bottom = 12.dp))
        }
        state.error?.let {
            ErrorBanner(it, modifier = Modifier.padding(bottom = 12.dp))
        }

        val invite = state.invite
        if (invite != null) {
            InviteQrCard(
                invite = invite,
                expiresAt = state.inviteExpiresAt,
                onCancel = { state.cancelInvite() },
                onRestart = { state.startInvite() },
            )
        } else {
            // Full width, stacked: "Agregar dispositivo" does not fit beside
            // "Aprobar dispositivo" on a phone — both labels ellipsized.
            Button(
                onClick = { state.startInvite() },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(Res.string.profile_add_device), maxLines = 1)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { state.scanAndApprove(qrUnavailable, notPairingRequest, approvedNotice) },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Icon(Icons.Filled.QrScan, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(Res.string.profile_approve_device), maxLines = 1)
            }
        }

        if (devices.isEmpty() && busy) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }

        if (devices.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            devices.forEachIndexed { index, device ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 50.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    )
                }
                ChainDeviceRow(device = device, onRevoke = { revoking = device })
            }
        } else if (state.loaded && !busy && invite == null) {
            Text(
                stringResource(Res.string.profile_no_devices),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }

    revoking?.let { device ->
        val deviceName = chainDeviceName(
            device,
            stringResource(Res.string.passkey_name, device.id.takeLast(6)),
        )
        val revokedNotice = stringResource(Res.string.profile_revoke_notice, deviceName)
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text(stringResource(Res.string.profile_revoke_title, deviceName)) },
            text = {
                Text(
                    if (device.current) {
                        stringResource(Res.string.profile_revoke_current_body)
                    } else {
                        stringResource(Res.string.profile_revoke_other_body)
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = device
                        revoking = null
                        state.revoke(target, revokedNotice)
                    },
                ) {
                    Text(
                        stringResource(Res.string.profile_revoke),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { revoking = null }) { Text(cancelLabel) }
            },
        )
    }
}

/**
 * WhatsApp linking. The user never types a phone number here: the worker hands
 * out a short code and the number proves itself by *sending* it, which is both
 * simpler and safer than verifying a number typed into the app.
 */
@Composable
private fun WhatsappSection(state: WhatsappState) {
    if (!state.loaded) {
        LaunchedEffect(Unit) { state.refresh() }
    }
    val numbers = state.numbers
    SectionCard(
        title = stringResource(Res.string.profile_wa_title),
        icon = Icons.Filled.Chat,
        subtitle = stringResource(Res.string.profile_wa_subtitle),
        trailing = { if (numbers.isNotEmpty()) ValuePill(numbers.size.toString()) },
    ) {
        state.error?.let { ErrorBanner(it, modifier = Modifier.padding(bottom = 12.dp)) }

        val code = state.code
        if (code != null && numbers.isEmpty()) {
            WhatsappCodeCard(
                code = code,
                onCancel = { state.cancelLink() },
                onRestart = { state.startLink() },
                onDone = { state.refresh() },
            )
        } else if (numbers.isEmpty()) {
            Text(
                stringResource(Res.string.profile_wa_linked_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Button(
                onClick = { state.startLink() },
                enabled = !state.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(Res.string.profile_wa_link), maxLines = 1)
            }
        }

        numbers.forEachIndexed { index, number ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 50.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            ) {
                Text(
                    "+${number.number}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { state.unlink(number) }, enabled = !state.busy) {
                    Text(
                        stringResource(Res.string.profile_wa_unlink),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * The pending code. The common case by far is that the number being linked
 * lives on *this* phone, so the primary action opens WhatsApp with the message
 * already written; the QR (for linking a number on another device) is one tap
 * away instead of taking over the card.
 */
@Composable
private fun WhatsappCodeCard(
    code: WhatsappLinkCode,
    onCancel: () -> Unit,
    onRestart: () -> Unit,
    onDone: () -> Unit,
) {
    var now by remember { mutableStateOf(epochMillis()) }
    LaunchedEffect(code.code) {
        while (true) {
            now = epochMillis()
            delay(1000)
        }
    }
    val remainingSeconds = ((code.expiresAt - now) / 1000).coerceAtLeast(0)
    // Captured once: the window only shrinks, so measuring it every frame
    // would pin the progress bar at 100%.
    val totalSeconds = remember(code.code) {
        ((code.expiresAt - epochMillis()) / 1000).coerceAtLeast(1)
    }
    val expired = remainingSeconds <= 0
    var showQr by remember(code.code) { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (expired) {
            Text(
                stringResource(Res.string.profile_wa_expired),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onRestart, modifier = Modifier.padding(top = 12.dp)) {
                Text(stringResource(Res.string.profile_wa_new_code))
            }
            return@Column
        }
        val link = code.link
        if (link != null) {
            Button(
                onClick = { uriHandler.openUri(link) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Icon(Icons.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(Res.string.profile_wa_open), maxLines = 1)
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { showQr = !showQr }) {
                Text(
                    stringResource(
                        if (showQr) Res.string.profile_wa_hide_qr else Res.string.profile_wa_show_qr,
                    ),
                )
            }
            if (showQr) {
                // White backing is deliberate: scanners need the contrast.
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White,
                    modifier = Modifier
                        .widthIn(max = 240.dp)
                        .fillMaxWidth(0.72f)
                        .aspectRatio(1f),
                ) {
                    Image(
                        painter = rememberQrCodePainter(link) {
                            colors {
                                dark = QrBrush.solid(Color.Black)
                                light = QrBrush.solid(Color.White)
                            }
                            background { fill = SolidColor(Color.White) }
                        },
                        contentDescription = stringResource(Res.string.profile_wa_qr_content),
                        modifier = Modifier.fillMaxSize().padding(14.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(Res.string.profile_wa_scan_hint, code.number ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Text(
                stringResource(Res.string.profile_wa_number_missing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(12.dp))
        // The literal message stays visible as the fallback for every case the
        // deep link cannot cover (another phone, a desktop session).
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Text(
                "vincular ${code.code}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(Res.string.profile_wa_code_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { (remainingSeconds.toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(4.dp)
                .clip(CircleShape),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(Res.string.login_expires_in, remainingSeconds),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.action_cancel)) }
            Spacer(Modifier.width(8.dp))
            // Nothing pushes the link back to the app, so the user says when
            // the message went out and we re-read the list.
            TextButton(onClick = onDone) { Text(stringResource(Res.string.profile_wa_sent)) }
        }
    }
}

/**
 * QR shown on this (signed-in) device for a new device to scan. The code sits
 * on its own white plate (scanner contrast) and the countdown doubles as a
 * progress track so the single-use window is visible at a glance.
 */
@Composable
private fun InviteQrCard(
    invite: ChainInvite,
    expiresAt: Long,
    onCancel: () -> Unit,
    onRestart: () -> Unit,
) {
    var now by remember { mutableStateOf(epochMillis()) }
    LaunchedEffect(invite.id) {
        while (true) {
            now = epochMillis()
            delay(1000)
        }
    }
    val remainingSeconds = ((expiresAt - now) / 1000).coerceAtLeast(0)
    val expired = remainingSeconds <= 0
    val total = invite.expiresIn.coerceAtLeast(1)
    val cancelLabel = stringResource(Res.string.action_cancel)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (expired) {
            Text(
                stringResource(Res.string.profile_invite_expired),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onRestart,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(stringResource(Res.string.profile_new_invite))
            }
        } else {
            // White backing is deliberate: scanners need the contrast.
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .fillMaxWidth(0.78f)
                    .aspectRatio(1f),
            ) {
                Image(
                    painter = rememberQrCodePainter(invite.url) {
                        colors {
                            dark = QrBrush.solid(Color.Black)
                            light = QrBrush.solid(Color.White)
                        }
                        background {
                            fill = SolidColor(Color.White)
                        }
                    },
                    contentDescription = stringResource(Res.string.profile_qr_content),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(Res.string.profile_qr_scan_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { (remainingSeconds.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(4.dp)
                    .clip(CircleShape),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    Res.string.login_expires_in,
                    "${remainingSeconds / 60}:${(remainingSeconds % 60).toString().padStart(2, '0')}",
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onCancel, modifier = Modifier.padding(top = 6.dp)) {
            Text(cancelLabel)
        }
    }
}

@Composable
private fun ChainDeviceRow(
    device: ChainDevice,
    onRevoke: () -> Unit,
) {
    val revoked = device.revokedAt != null
    val passkeyFallback = stringResource(Res.string.passkey_name, device.id.takeLast(6))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .alpha(if (revoked) 0.45f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(
                    if (device.current && !revoked) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Smartphone,
                contentDescription = null,
                tint = if (device.current && !revoked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            // The user agent names the *device*; the credential name is the
            // passkey provider ("Google Password Manager…"), so it reads as
            // the detail line.
            val credential = chainDeviceName(device, passkeyFallback)
            val agent = device.userAgent
            Text(
                agent ?: credential,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (agent != null) {
                Text(
                    credential,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (device.current || revoked) {
                Spacer(Modifier.height(6.dp))
                if (revoked) {
                    ValuePill(
                        text = stringResource(Res.string.profile_device_revoked),
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    ValuePill(
                        text = stringResource(Res.string.profile_device_this),
                        container = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        content = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        if (!revoked) {
            IconButton(onClick = onRevoke) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(Res.string.profile_revoke),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * The name the app greets you with. Lives next to the contact email because
 * both belong to the *account* rather than to the ledger, and both are edited
 * the same way: a value, a pencil, a one-field dialog.
 */
@Composable
private fun DisplayNameSection(state: UserState) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { state.load() }

    SectionCard(
        title = stringResource(Res.string.profile_name_title),
        icon = Icons.Filled.Person,
        subtitle = stringResource(Res.string.profile_name_subtitle),
    ) {
        state.error?.let { ErrorBanner(it) }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.name ?: stringResource(Res.string.profile_name_not_set),
                style = MaterialTheme.typography.bodyLarge,
                color = if (state.name == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    draft = state.name ?: ""
                    editing = true
                },
            ) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = stringResource(Res.string.profile_name_edit),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(Res.string.profile_name_title)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.profile_name_label)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        editing = false
                        // Blank is a valid edit: it clears the name and the
                        // greeting falls back to a plain "Hola".
                        state.setName(draft)
                    },
                ) { Text(stringResource(Res.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }
}

/** Contact email the finance worker routes wallet mail from. Lives on the auth
 * server (users.email), so only signed-in authenticated calls are involved.
 * Cached the same way as the display name in [UserState], so it doesn't
 * refetch every time Profile is opened. */
@Composable
private fun AccountEmailSection(state: UserState) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { state.loadEmail() }

    SectionCard(
        title = stringResource(Res.string.profile_contact_email_title),
        icon = Icons.Filled.Email,
        subtitle = stringResource(Res.string.profile_contact_email_subtitle),
    ) {
        when {
            state.emailBusy && state.email == null ->
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            state.emailError != null -> ErrorBanner(state.emailError ?: "")
            else -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        state.email ?: stringResource(Res.string.profile_email_not_set),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (state.email == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            draft = state.email ?: ""
                            editing = true
                        },
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = stringResource(Res.string.profile_email_edit),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(Res.string.profile_contact_email_title)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val value = draft.trim()
                        editing = false
                        if (!value.contains('@')) return@TextButton
                        state.setEmail(value)
                    },
                ) { Text(stringResource(Res.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }
}
