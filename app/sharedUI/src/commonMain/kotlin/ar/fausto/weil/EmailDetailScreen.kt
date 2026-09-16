@file:OptIn(ExperimentalMaterial3Api::class)

package ar.fausto.weil

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.action_back
import weil.app.sharedui.generated.resources.email_detail_from
import weil.app.sharedui.generated.resources.email_detail_title
import weil.app.sharedui.generated.resources.email_detail_to
import weil.app.sharedui.generated.resources.emails_no_subject

/** Vista de solo lectura de un correo: remitente, destinatario y cuerpo completo. */
@Composable
fun EmailDetailScreen(
    emails: EmailsRepository,
    id: String,
    onNavigateBack: () -> Unit,
) {
    var email by remember { mutableStateOf<EmailDetail?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        email = emails.get(id)
        loaded = true
    }

    val noSubject = stringResource(Res.string.emails_no_subject)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.email_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            !loaded -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(32.dp))
                    CircularProgressIndicator()
                }
            }
            email == null -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
                ) {
                    Text(
                        stringResource(Res.string.emails_no_subject),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            else -> {
                val current = email!!
                // The header stays put and the body scrolls on its own: the
                // HTML view brings its own scrolling and must never sit inside
                // a verticalScroll parent (unbounded height breaks measuring).
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(16.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(GroupRadius),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                current.subject ?: noSubject,
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(Res.string.email_detail_from, current.fromEmail),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(Res.string.email_detail_to, current.toEmail),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                formatTimestamp(current.receivedAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        shape = RoundedCornerShape(GroupRadius),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) {
                        val bodyHtml = current.bodyHtml
                        if (bodyHtml.isNullOrBlank()) {
                            // Pre-HTML rows, and mails that only carried text.
                            Text(
                                current.bodyText.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .padding(16.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        } else {
                            HtmlView(
                                html = rememberEmailDocument(bodyHtml),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}
