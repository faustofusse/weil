package ar.fausto.weil

import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real [PasskeyCeremony] for the JVM: there is no WebAuthn on desktop, so the
 * ceremony runs in the system browser on the app's rp_id origin
 * (https://finance.fausto.ar/desktop-pair, served by the auth worker) and the
 * raw credential response comes back over a loopback HTTP listener.
 *
 * Only `navigator.credentials.create/get` happens in the browser — every API
 * call (the start and finish endpoints) is made by [AuthApi] with the app's
 * own HTTP client, so the httpOnly `auth_finance` cookie is set on this app.
 * That's what keeps `session/refresh` and the cookie-authed chain endpoints
 * working afterwards.
 *
 * Because it satisfies the shared interface, login, register and chain/join
 * all work through the normal [AuthRepository] code paths.
 */
class BrowserPasskeys(
    private val pairUrl: String = "https://${AuthConfig.RP_DOMAIN}/desktop-pair",
    private val timeoutSeconds: Long = 180,
) : PasskeyCeremony {

    override suspend fun create(optionsJson: String): String = ceremony("create", optionsJson)

    override suspend fun assert(optionsJson: String): String = ceremony("get", optionsJson)

    private suspend fun ceremony(mode: String, optionsJson: String): String = withContext(Dispatchers.IO) {
        val state = java.util.UUID.randomUUID().toString()
        // Capacity 1 + offer(): the handler must never block the HTTP thread,
        // and a late/duplicate POST is simply dropped.
        val results = ArrayBlockingQueue<Map<String, String>>(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        server.createContext("/finish") { exchange ->
            try {
                val fields = when (exchange.requestMethod) {
                    "POST" -> parseForm(exchange.requestBody)
                    "OPTIONS" -> emptyMap()
                    else -> emptyMap()
                }
                // CORS + Private Network Access headers so a fetch-based
                // delivery also works; the page uses a form POST navigation,
                // which needs neither.
                exchange.responseHeaders.add("Access-Control-Allow-Origin", "https://${AuthConfig.RP_DOMAIN}")
                exchange.responseHeaders.add("Access-Control-Allow-Methods", "POST, OPTIONS")
                exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
                exchange.responseHeaders.add("Access-Control-Allow-Private-Network", "true")
                if (exchange.requestMethod == "OPTIONS") {
                    exchange.sendResponseHeaders(204, -1)
                    return@createContext
                }
                val body = """
                    <!doctype html><meta charset="utf-8">
                    <body style="font-family:ui-monospace,monospace;text-align:center;margin-top:3rem">
                    Done — you can close this tab and return to Weil.</body>
                """.trimIndent().toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                if (fields["state"] == state) results.offer(fields)
            } finally {
                exchange.close()
            }
        }
        server.start()

        try {
            val payload = buildString {
                append("{\"mode\":\"").append(mode).append("\",")
                append("\"port\":").append(server.address.port).append(',')
                append("\"state\":\"").append(state).append("\",")
                append("\"options\":").append(optionsJson)
                append('}')
            }
            val fragment = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
            val url = "$pairUrl#$fragment"
            // Printed as a fallback: if the browser fails to launch (headless,
            // SSH, no default handler) the link can still be opened by hand.
            println("[weil] passkey ceremony — open this if your browser didn't: $url")
            openBrowser(url)

            val fields = results.poll(timeoutSeconds, TimeUnit.SECONDS)
                ?: throw PasskeyCancelled("timed out waiting for the browser passkey ceremony")
            fields["error"]?.let { throw translate(it) }
            fields["response"] ?: throw PasskeyCancelled("browser returned no credential")
        } finally {
            server.stop(0)
        }
    }

    /** Maps the browser's DOMException text onto the shared exception types,
     * mirroring what bridgedPasskeys() does for the Swift bridge. */
    private fun translate(message: String): Exception {
        val lower = message.lowercase()
        return when {
            "notallowed" in lower || "abort" in lower || "cancel" in lower || "timed out" in lower ->
                PasskeyCancelled(message)
            "no passkey" in lower || "not found" in lower || "no credential" in lower ->
                PasskeyNotFound(message)
            else -> Exception("passkey ceremony failed: $message")
        }
    }

    private fun openBrowser(url: String) {
        val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
        if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
            desktop.browse(URI(url))
            return
        }
        // Headless-ish fallback: at least make the link reachable.
        ProcessBuilder("open", url).start()
    }

    private fun parseForm(stream: InputStream): Map<String, String> {
        val raw = stream.readBytes().toString(StandardCharsets.UTF_8)
        if (raw.isBlank()) return emptyMap()
        return raw.split("&").filter { it.isNotBlank() }.associate { pair ->
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            URLDecoder.decode(key, StandardCharsets.UTF_8) to
                URLDecoder.decode(value, StandardCharsets.UTF_8)
        }
    }
}
