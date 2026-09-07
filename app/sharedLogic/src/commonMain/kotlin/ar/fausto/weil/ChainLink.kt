package ar.fausto.weil

/**
 * Contents of a scanned chain QR. Invite QRs carry the join secret in the URL
 * fragment (`#token=...`); request QRs carry only the approve id.
 */
data class ChainLink(
    val action: Action,
    val id: String,
    val token: String?,
) {
    enum class Action { Join, Approve }

    companion object {
        // /apps/:slug/chain/join|approve/:id
        private val pattern = Regex("/apps/([A-Za-z0-9_-]+)/chain/(join|approve)/([A-Za-z0-9]+)")

        /** Returns null when the text is not a chain URL for [slug]. */
        fun parse(raw: String, slug: String): ChainLink? {
            val match = pattern.find(raw.trim()) ?: return null
            val (pathSlug, action, id) = match.destructured
            if (pathSlug != slug) return null
            val token = if (action == "join") {
                raw.substringAfter("#token=", "").substringBefore('&')
                    .trim().takeIf { it.isNotEmpty() }
            } else {
                null
            }
            val link = ChainLink(
                action = if (action == "join") Action.Join else Action.Approve,
                id = id,
                token = token,
            )
            return link
        }
    }
}
