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
                title = { Text("Profile") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Navigate back")
                    }
                },
                actions = {
                    IconButton(onClick = onSignOut) {
                        Icon(
                            Icons.Filled.Logout,
                            contentDescription = "Sign out",
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

    if (!state.loaded) {
        LaunchedEffect(Unit) { state.refresh() }
    }

    Text("Sync chain", style = MaterialTheme.typography.titleMedium)
    Text(
        "Your devices share one account and its database. Every device signs in with its own passkey.",
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
                Text("Add device")
            }
            OutlinedButton(
                onClick = { state.scanAndApprove() },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text("Approve device")
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
            "No devices yet — use Add device here, or pair a new device from its sign-in screen.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }

    revoking?.let { device ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text("Revoke ${chainDeviceName(device)}?") },
            text = {
                Text(
                    if (device.current) {
                        "This is the device you are using — you will be signed out immediately."
                    } else {
                        "This device will be signed out immediately and lose database access. " +
                            "Other devices recover automatically."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = device
                        revoking = null
                        state.revoke(target)
                    },
                ) { Text("Revoke") }
            },
            dismissButton = {
                TextButton(onClick = { revoking = null }) { Text("Cancel") }
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (expired) {
            Text(
                "Invite expired",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onRestart,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("New invite")
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
                contentDescription = "Pairing QR code",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp, vertical = 8.dp),
            )
            Text(
                "Scan with the new device — it joins with its own passkey. Single use.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                "Expires in ${remainingSeconds / 60}:${(remainingSeconds % 60).toString().padStart(2, '0')}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        TextButton(onClick = onCancel, modifier = Modifier.padding(top = 4.dp)) {
            Text("Cancel")
        }
    }
}

@Composable
private fun ChainDeviceRow(
    device: ChainDevice,
    onRevoke: () -> Unit,
) {
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
                    append(chainDeviceName(device))
                    if (device.current) append("  · this device")
                    if (device.revokedAt != null) append("  · revoked")
                },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (device.revokedAt == null) {
            TextButton(onClick = onRevoke) {
                Text(
                    "Revoke",
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
            error = e.message ?: e.toString()
        } finally {
            busy = false
        }
    }

    Text("Contact email", style = MaterialTheme.typography.titleMedium)
    Text(
        "Emails sent here are ingested into your account's database (and forwarded to Gmail).",
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
                    email ?: "Not set",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (email == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    draft = email ?: ""
                    editing = true
                }) {
                    Text("Edit")
                }
            }
        }
    }

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Contact email") },
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
                                error = e.message ?: e.toString()
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text("Cancel") }
            },
        )
    }
}
