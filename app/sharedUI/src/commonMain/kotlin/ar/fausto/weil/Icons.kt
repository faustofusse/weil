package ar.fausto.weil

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The handful of icons this app needs, built in-source. The Compose
 * Multiplatform icons artifacts are frozen at 1.7.3, so we inline the path
 * data instead of adding a stale dependency.
 *
 * They are **outlines**, not filled silhouettes: 24×24, a 2-unit stroke with
 * round caps and joins, drawn on the same grid as the account icons in
 * [AccountIcons]. Filled Material glyphs next to a line-art UI read as two
 * icon sets on one screen, which is exactly what the design isn't.
 *
 * Note this is genuinely different path data, not the filled paths stroked:
 * stroking a silhouette outlines its *edge* (you get a double line around a
 * solid shape), so each icon is line geometry from the start.
 */
object Icons {

    /** Stroked, round-capped line icon; [d] paths are drawn in order. */
    private fun lineIcon(name: String, vararg d: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            d.forEach { path ->
                addPath(
                    pathData = addPathNodes(path),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.9f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    /**
     * Solid shape, kept for the two icons a line version would ruin: the
     * overflow dots (a stroked dot is a ring) and the "this is the default
     * account" star, whose whole job is to differ from its outline sibling.
     */
    private fun solidIcon(name: String, d: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black))
        }.build()

    object Filled {
        val Notifications: ImageVector = lineIcon(
            "Notifications",
            "M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9z",
            "M13.73 21a2 2 0 0 1-3.46 0",
        )

        val Refresh: ImageVector = lineIcon(
            "Refresh",
            "M23 4v6h-6",
            "M1 20v-6h6",
            "M3.51 9a9 9 0 0 1 14.85-3.36L23 10",
            "M1 14l4.64 4.36A9 9 0 0 0 20.49 15",
        )

        val Warning: ImageVector = lineIcon(
            "Warning",
            "M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z",
            "M12 9v4",
            "M12 17h.01",
        )

        val ArrowBack: ImageVector = lineIcon(
            "ArrowBack",
            "M19 12H5",
            "M12 19l-7-7 7-7",
        )

        val AccountCircle: ImageVector = lineIcon(
            "AccountCircle",
            "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z",
            "M12 12.5a3.25 3.25 0 1 0 0-6.5 3.25 3.25 0 0 0 0 6.5z",
            "M5.9 19a6.4 6.4 0 0 1 12.2 0",
        )

        val Email: ImageVector = lineIcon(
            "Email",
            "M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
            "M22 6.5l-10 7-10-7",
        )

        /** Stands in for WhatsApp, whose mark is trademarked. */
        val Chat: ImageVector = lineIcon(
            "Chat",
            "M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z",
        )

        val Logout: ImageVector = lineIcon(
            "Logout",
            "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4",
            "M16 17l5-5-5-5",
            "M21 12H9",
        )

        val MenuBook: ImageVector = lineIcon(
            "MenuBook",
            "M2 4h6a4 4 0 0 1 4 4v13a3 3 0 0 0-3-3H2z",
            "M22 4h-6a4 4 0 0 0-4 4v13a3 3 0 0 1 3-3h7z",
        )

        /** The image/PDF import entry point. */
        val DocumentScanner: ImageVector = lineIcon(
            "DocumentScanner",
            "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z",
            "M14 2v6h6",
            "M9 13h6",
            "M9 17h6",
        )

        /** Movements detected in notifications and mail. */
        val Bolt: ImageVector = lineIcon(
            "Bolt",
            "M13 2L4 14h7l-1 8 9-12h-7l1-8z",
        )

        val MoreVert: ImageVector = solidIcon(
            "MoreVert",
            "M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z" +
                "m0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z",
        )

        val Search: ImageVector = lineIcon(
            "Search",
            "M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16z",
            "M21 21l-4.35-4.35",
        )

        val Add: ImageVector = lineIcon(
            "Add",
            "M12 5v14",
            "M5 12h14",
        )

        val Remove: ImageVector = lineIcon(
            "Remove",
            "M5 12h14",
        )

        val Close: ImageVector = lineIcon(
            "Close",
            "M6 6l12 12",
            "M18 6L6 18",
        )

        val ExpandMore: ImageVector = lineIcon(
            "ExpandMore",
            "M6 9.5l6 6 6-6",
        )

        val ChevronRight: ImageVector = lineIcon(
            "ChevronRight",
            "M9 18l6-6-6-6",
        )

        val Check: ImageVector = lineIcon(
            "Check",
            "M20 6L9 17l-5-5",
        )

        val Edit: ImageVector = lineIcon(
            "Edit",
            "M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7",
            "M18.5 2.5a2.12 2.12 0 0 1 3 3L12 15l-4 1 1-4z",
        )

        val Delete: ImageVector = lineIcon(
            "Delete",
            "M3 6h18",
            "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2",
            "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6",
            "M10 11v6",
            "M14 11v6",
        )

        val Smartphone: ImageVector = lineIcon(
            "Smartphone",
            "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z",
            "M12 18h.01",
        )

        /** Viewfinder corners around QR blocks — "scan to approve". */
        val QrScan: ImageVector = lineIcon(
            "QrScan",
            "M3 8V5a2 2 0 0 1 2-2h3",
            "M16 3h3a2 2 0 0 1 2 2v3",
            "M21 16v3a2 2 0 0 1-2 2h-3",
            "M8 21H5a2 2 0 0 1-2-2v-3",
            "M7.5 7.5h3v3h-3z",
            "M13.5 7.5h3v3h-3z",
            "M7.5 13.5h3v3h-3z",
            "M13.5 13.5h3v3h-3z",
        )

        /** Filled on purpose: the "default account" badge, next to [StarOutline]. */
        val Star: ImageVector = solidIcon(
            "Star",
            "M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z",
        )

        val StarOutline: ImageVector = lineIcon(
            "StarOutline",
            "M12 2.8l2.85 5.77 6.37.93-4.61 4.49 1.09 6.34L12 17.33l-5.7 3-1.09-6.34L.6 9.5l6.37-.93z",
        )

        val Tune: ImageVector = lineIcon(
            "Tune",
            "M4 21v-7",
            "M4 10V3",
            "M12 21v-9",
            "M12 8V3",
            "M20 21v-5",
            "M20 12V3",
            "M1 14h6",
            "M9 8h6",
            "M17 16h6",
        )

        val AccountTree: ImageVector = lineIcon(
            "AccountTree",
            "M6 4.5a2 2 0 1 0 0-4 2 2 0 0 0 0 4z",
            "M19 12a2 2 0 1 0 0-4 2 2 0 0 0 0 4z",
            "M19 22a2 2 0 1 0 0-4 2 2 0 0 0 0 4z",
            "M6 4.5V17a3 3 0 0 0 3 3h8",
            "M6 8.5a3 3 0 0 0 3 3h8",
        )

        /** Bottom bar: the home tab. */
        val Home: ImageVector = lineIcon(
            "Home",
            "M3 9.5l9-7 9 7V20a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
            "M9.5 22v-8h5v8",
        )

        /** Bottom bar: the movements tab. */
        val ListAlt: ImageVector = lineIcon(
            "ListAlt",
            "M8 6h13",
            "M8 12h13",
            "M8 18h13",
            "M3.5 6h.01",
            "M3.5 12h.01",
            "M3.5 18h.01",
        )

        /** Bottom bar: the categories tab — the mock's triangle/square/circle. */
        val Category: ImageVector = lineIcon(
            "Category",
            "M12 3l4 6.5H8z",
            "M4 14h6.5v6.5H4z",
            "M17.5 21a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z",
        )

        /** Bottom bar: the profile tab. */
        val Person: ImageVector = lineIcon(
            "Person",
            "M20 21v-1.5a4.5 4.5 0 0 0-4.5-4.5h-7A4.5 4.5 0 0 0 4 19.5V21",
            "M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z",
        )

        /** Balance hero: the amounts are visible. */
        val Visibility: ImageVector = lineIcon(
            "Visibility",
            "M1.5 12S5.5 4.5 12 4.5 22.5 12 22.5 12 18.5 19.5 12 19.5 1.5 12 1.5 12z",
            "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
        )

        /** Balance hero: the amounts are masked. */
        val VisibilityOff: ImageVector = lineIcon(
            "VisibilityOff",
            "M17.94 17.94A10.1 10.1 0 0 1 12 19.5C5.5 19.5 1.5 12 1.5 12a18.5 18.5 0 0 1 5.06-5.94",
            "M9.9 4.74A9.1 9.1 0 0 1 12 4.5c6.5 0 10.5 7.5 10.5 7.5a18.5 18.5 0 0 1-2.16 3.19",
            "M14.12 14.12a3 3 0 1 1-4.24-4.24",
            "M2 2l20 20",
        )
    }
}
