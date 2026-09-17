package ar.fausto.weil

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The handful of Material icons this app needs, built in-source. The Compose
 * Multiplatform icons artifacts are frozen at 1.7.3, so we inline the official
 * Material path data instead of adding a stale dependency.
 */
object Icons {

    private fun materialIcon(name: String, d: String): ImageVector =
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
        val Notifications: ImageVector = materialIcon(
            "Notifications",
            "M12 22c1.1 0 2-.9 2-2h-4c0 1.1.9 2 2 2zm6-6v-5c0-3.07-1.63-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.64 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z",
        )

        val Refresh: ImageVector = materialIcon(
            "Refresh",
            "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z",
        )

        val Warning: ImageVector = materialIcon(
            "Warning",
            "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z",
        )

        val ArrowBack: ImageVector = materialIcon(
            "ArrowBack",
            "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z",
        )

        val AccountCircle: ImageVector = materialIcon(
            "AccountCircle",
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 3c1.66 0 3 1.34 3 3s-1.34 3-3 3-3-1.34-3-3 1.34-3 3-3zm0 14.2c-2.5 0-4.71-1.28-6-3.22.03-1.99 4-3.08 6-3.08 1.99 0 5.97 1.09 6 3.08-1.29 1.94-3.5 3.22-6 3.22z",
        )

        val Email: ImageVector = materialIcon(
            "Email",
            "M20 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 4-8 5-8-5V6l8 5 8-5v2z",
        )

        val Logout: ImageVector = materialIcon(
            "Logout",
            "M17 7l-1.41 1.41L18.17 11H8v2h10.17l-2.58 2.58L17 17l5-5zM4 5h8V3H4c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h8v-2H4V5z",
        )

        val MenuBook: ImageVector = materialIcon(
            "MenuBook",
            "M21 5c-1.11-.35-2.33-.5-3.5-.5-1.95 0-4.05.4-5.5 1.5-1.45-1.1-3.55-1.5-5.5-1.5S2.45 4.9 1 6v14.65c0 .25.25.5.5.5.1 0 .15-.05.25-.05C3.25 20.45 5.25 20 6.5 20c1.95 0 4.05.4 5.5 1.5 1.35-.85 3.8-1.5 5.5-1.5 1.65 0 3.35.3 4.75 1.05.1.05.15.05.25.05.25 0 .5-.25.5-.5V6c-.6-.45-1.25-.75-2-1zm0 13.5c-1.1-.35-2.3-.5-3.5-.5-1.7 0-4.15.65-5.5 1.5V8c1.35-.85 3.8-1.5 5.5-1.5 1.2 0 2.4.15 3.5.5v11.5z",
        )

        /** Material "document_scanner": the image/PDF import entry point. */
        val DocumentScanner: ImageVector = materialIcon(
            "DocumentScanner",
            "M4 4h3V2H4c-1.1 0-2 .9-2 2v3h2V4zm16 0v3h2V4c0-1.1-.9-2-2-2h-3v2h3zM4 17H2v3c0 1.1.9 2 2 2h3v-2H4v-3zm16 3h-3v2h3c1.1 0 2-.9 2-2v-3h-2v3zM17 6H7v12h10V6zm-2 10H9V8h6v8z",
        )

        /** Material "bolt": movements detected in notifications and mail. */
        val Bolt: ImageVector = materialIcon(
            "Bolt",
            "M11 21h-1l1-7H7.5c-.58 0-.57-.32-.38-.66.19-.34.05-.08.07-.12C8.48 10.94 10.42 7.54 13 3h1l-1 7h3.5c.49 0 .56.33.47.51l-.07.15C12.96 17.55 11 21 11 21z",
        )

        val MoreVert: ImageVector = materialIcon(
            "MoreVert",
            "M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z",
        )

        val Search: ImageVector = materialIcon(
            "Search",
            "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z",
        )

        val Add: ImageVector = materialIcon(
            "Add",
            "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
        )

        val Remove: ImageVector = materialIcon(
            "Remove",
            "M19 13H5v-2h14v2z",
        )

        val ExpandMore: ImageVector = materialIcon(
            "ExpandMore",
            "M16.59 8.59 12 13.17 7.41 8.59 6 10l6 6 6-6z",
        )

        val ChevronRight: ImageVector = materialIcon(
            "ChevronRight",
            "M10 6 8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z",
        )

        val Check: ImageVector = materialIcon(
            "Check",
            "M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z",
        )

        val Edit: ImageVector = materialIcon(
            "Edit",
            "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34" +
                "c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z",
        )

        val Delete: ImageVector = materialIcon(
            "Delete",
            "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z",
        )

        /** Phone body with the screen punched out by the reverse-wound subpath. */
        val Smartphone: ImageVector = materialIcon(
            "Smartphone",
            "M7 1h10c1.1 0 2 .9 2 2v18c0 1.1-.9 2-2 2H7c-1.1 0-2-.9-2-2V3c0-1.1.9-2 2-2z" +
                "M17 5H7v14h10V5z",
        )

        /** Viewfinder corners around QR-ish blocks — used for "scan to approve". */
        val QrScan: ImageVector = materialIcon(
            "QrScan",
            "M3 3h7v2H5v5H3V3zm11 0h7v7h-2V5h-5V3zM3 14h2v5h5v2H3v-7zm16 0h2v7h-7v-2h5v-5z" +
                "M7 7h3v3H7V7zm7 7h3v3h-3v-3zm-7 0h3v3H7v-3zm7-7h3v3h-3V7z",
        )

        val AccountTree: ImageVector = materialIcon(
            "AccountTree",
            "M17,11h3c1.11,0,2-0.9,2-2V5c0-1.11-0.9-2-2-2h-3c-1.11,0-2,0.9-2,2v1H9.01V5c0-1.11-0.9-2-2-2H4C2.9,3,2,3.9,2,5v4 " +
                "c0,1.11,0.9,2,2,2h3c1.11,0,2-0.9,2-2V8H11v7.01c0,1.65,1.34,2.99,2.99,2.99H15v1c0,1.11,0.9,2,2,2h3c1.11,0,2-0.9,2-2v-4 " +
                "c0-1.11-0.9-2-2-2h-3c-1.11,0-2,0.9-2,2v1h-1.01C13.45,16,13,15.55,13,15.01V8h2v1C15,10.1,15.9,11,17,11z",
        )
    }
}
