package ar.fausto.weil

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.qrose.options.QrBackground
import io.github.alexzhirkevich.qrose.options.QrBrush
import io.github.alexzhirkevich.qrose.options.solid
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.action_back
import weil.app.sharedui.generated.resources.action_cancel
import weil.app.sharedui.generated.resources.action_edit
import weil.app.sharedui.generated.resources.action_save
import weil.app.sharedui.generated.resources.login_expires_in
import weil.app.sharedui.generated.resources.passkey_name
import weil.app.sharedui.generated.resources.profile_add_device
import weil.app.sharedui.generated.resources.profile_approve_device
import weil.app.sharedui.generated.resources.profile_contact_email_subtitle
import weil.app.sharedui.generated.resources.profile_contact_email_title
import weil.app.sharedui.generated.resources.profile_device_approved
import weil.app.sharedui.generated.resources.profile_device_revoked
import weil.app.sharedui.generated.resources.profile_device_this
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
import weil.app.sharedui.generated.resources.profile_sign_out
import weil.app.sharedui.generated.resources.profile_sync_chain_subtitle
import weil.app.sharedui.generated.resources.profile_sync_chain_title
import weil.app.sharedui.generated.resources.profile_title

@Composable
fun ProfileScreen(
    chain: ChainRepository,
    chainState: ChainState,
    onNavigateBack: () -> Unit,
    onSignOut: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.profile_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onSignOut) {
                        Icon(
                            Icons.Filled.Logout,
                            contentDescription = stringResource(Res.string.profile_sign_out),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            AccountEmailSection(chain)
            Spacer(Modifier.height(24.dp))
            ChainSection(chainState)
        }
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

    Text(stringResource(Res.string.profile_sync_chain_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(Res.string.profile_sync_chain_subtitle),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
    )

    state.notice?.let {
        Text(
            it,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
    state.error?.let {
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 4.dp),
        )
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { state.startInvite() },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(Res.string.profile_add_device))
            }
            OutlinedButton(
                onClick = { state.scanAndApprove(qrUnavailable, notPairingRequest, approvedNotice) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(Res.string.profile_approve_device))
            }
        }
    }

    if (devices.isEmpty() && busy) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(24.dp)
                .padding(top = 16.dp),
            strokeWidth = 2.dp,
        )
    }

    if (devices.isNotEmpty()) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        devices.forEach { device ->
            ChainDeviceRow(
                device = device,
                onRevoke = { revoking = device },
            )
        }
    } else if (state.loaded && !busy && invite == null) {
        Text(
            stringResource(Res.string.profile_no_devices),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
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
                ) { Text(stringResource(Res.string.profile_revoke)) }
            },
            dismissButton = {
                TextButton(onClick = { revoking = null }) { Text(cancelLabel) }
            },
        )
    }
}

/** QR shown on this (signed-in) device for a new device to scan. */
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
    val cancelLabel = stringResource(Res.string.action_cancel)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
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
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(stringResource(Res.string.profile_new_invite))
            }
        } else {
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
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp, vertical = 8.dp),
            )
            Text(
                stringResource(Res.string.profile_qr_scan_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(
                    Res.string.login_expires_in,
                    "${remainingSeconds / 60}:${(remainingSeconds % 60).toString().padStart(2, '0')}",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        TextButton(onClick = onCancel, modifier = Modifier.padding(top = 4.dp)) {
            Text(cancelLabel)
        }
    }
}

@Composable
private fun ChainDeviceRow(
    device: ChainDevice,
    onRevoke: () -> Unit,
) {
    val thisDeviceSuffix = stringResource(Res.string.profile_device_this)
    val revokedSuffix = stringResource(Res.string.profile_device_revoked)
    val passkeyFallback = stringResource(Res.string.passkey_name, device.id.takeLast(6))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .alpha(if (device.revokedAt != null) 0.4f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val userAgent = device.userAgent
            if (userAgent != null) {
                Text(
                    userAgent,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                buildString {
                    append(chainDeviceName(device, passkeyFallback))
                    if (device.current) append("  · $thisDeviceSuffix")
                    if (device.revokedAt != null) append("  · $revokedSuffix")
                },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (device.revokedAt == null) {
            TextButton(onClick = onRevoke) {
                Text(
                    stringResource(Res.string.profile_revoke),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Contact email the finance worker routes wallet mail from. Lives on the auth
 * server (users.email), so only signed-in authenticated calls are involved. */
@Composable
private fun AccountEmailSection(chain: ChainRepository) {
    var email by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        busy = true
        try {
            email = chain.getEmail()
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message ?: e.toString()
        } finally {
            busy = false
        }
    }

    Text(stringResource(Res.string.profile_contact_email_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(Res.string.profile_contact_email_subtitle),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
    )

    when {
        busy -> Text("…", style = MaterialTheme.typography.bodyMedium)
        error != null -> Text(
            error ?: "",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        else -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    email ?: stringResource(Res.string.profile_email_not_set),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (email == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    draft = email ?: ""
                    editing = true
                }) {
                    Text(stringResource(Res.string.action_edit))
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
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                chain.setEmail(value)
                                email = chain.getEmail()
                            } catch (e: Throwable) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                error = e.message ?: e.toString()
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text(stringResource(Res.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }
}
