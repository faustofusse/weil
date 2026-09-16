package ar.fausto.weil

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Desktop has no bundled web engine (embedding JCEF would add ~100 MB to the
 * build for one screen), so the body degrades to its visible text. Good enough
 * for the shot harness and for reading; Android/iOS render the real thing.
 */
@Composable
actual fun HtmlView(html: String, modifier: Modifier) {
    val text = remember(html) { htmlToPlainText(html) }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.verticalScroll(rememberScrollState()),
    )
}

/** Drops head/script/style blocks and tags, then unescapes the common entities. */
internal fun htmlToPlainText(html: String): String =
    html
        .replace(Regex("(?is)<(script|style|head)\\b.*?</\\1>"), "")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>"), "\n")
        .replace(Regex("(?s)<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
