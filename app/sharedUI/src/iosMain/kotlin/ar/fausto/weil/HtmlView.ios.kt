package ar.fausto.weil

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIColor
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebpagePreferences

@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
@Composable
actual fun HtmlView(html: String, modifier: Modifier) {
    UIKitView(
        modifier = modifier,
        // Defaults to the cooperative interaction mode, which is what lets the
        // web view scroll and handle link taps.
        properties = UIKitInteropProperties(),
        factory = {
            val configuration = WKWebViewConfiguration().apply {
                // No user interaction should ever run script; the markup is
                // sanitized server-side, this just closes the door locally.
                defaultWebpagePreferences = WKWebpagePreferences().apply {
                    allowsContentJavaScript = false
                }
            }
            WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = configuration).apply {
                opaque = false
                backgroundColor = UIColor.clearColor
                scrollView.bounces = false
            }
        },
        update = { it.loadHTMLString(html, baseURL = null) },
    )
}
