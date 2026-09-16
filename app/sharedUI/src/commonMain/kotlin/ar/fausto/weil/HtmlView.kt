package ar.fausto.weil

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Renders a full HTML document. The markup is already sanitized by the worker
 * (see `email.ts`), so platforms only need to avoid executing scripts and
 * loading remote resources.
 *
 * Scrolls internally — callers must give it bounded height (a `weight`), never
 * nest it inside a `verticalScroll` parent.
 */
@Composable
expect fun HtmlView(html: String, modifier: Modifier)

/**
 * Wraps an email body in a themed document: emails carry their own colors for
 * a white background, so a dark theme needs a readable default, and wide
 * fixed-width tables need to be reined in to the viewport.
 */
@Composable
fun rememberEmailDocument(bodyHtml: String): String {
    val colors = MaterialTheme.colorScheme
    return buildEmailDocument(
        bodyHtml = bodyHtml,
        text = colors.onSurface,
        background = colors.surfaceContainerLow,
        link = colors.primary,
    )
}

internal fun buildEmailDocument(
    bodyHtml: String,
    text: Color,
    background: Color,
    link: Color,
): String = """
<!DOCTYPE html>
<html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  html, body {
    margin: 0; padding: 12px;
    background: ${background.css()};
    color: ${text.css()};
    font-family: -apple-system, system-ui, sans-serif;
    font-size: 15px; line-height: 1.5;
    word-break: break-word; overflow-wrap: anywhere;
  }
  a { color: ${link.css()}; }
  img { max-width: 100%; height: auto; }
  table { max-width: 100%; }
  pre { white-space: pre-wrap; }
</style>
</head><body>$bodyHtml</body></html>
""".trimIndent()

/** `#rrggbb` for CSS. */
private fun Color.css(): String {
    val argb = toArgb()
    val hex = (argb and 0xFFFFFF).toString(16).padStart(6, '0')
    return "#$hex"
}
