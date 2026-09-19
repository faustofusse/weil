package ar.fausto.weil

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The icon a user can pin to an account (a category, a wallet, a bank).
 *
 * Only the [key] is stored (`accounts.icon`); the drawing lives here, so the
 * catalog can be re-drawn or extended without touching a single synced row,
 * and a key written by a newer build on another device degrades to the
 * account type's default instead of breaking the row that shows it.
 *
 * Outlines on the same 24×24 grid and the same 1.9-unit round stroke as
 * [Icons], because they appear side by side on every movement row.
 */
data class AccountIcon(val key: String, val image: ImageVector)

private fun icon(key: String, vararg d: String): AccountIcon = AccountIcon(
    key,
    ImageVector.Builder(
        name = key,
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
    }.build(),
)

/**
 * The pickable catalog, in the order the picker shows it: everyday spending
 * first, then money and where it lives, because that's the order a user
 * reaches for them (categories are created constantly, accounts once).
 */
object AccountIcons {
    val catalog: List<AccountIcon> = listOf(
        icon(
            "food",
            "M4 2v6a3 3 0 0 0 6 0V2",
            "M7 11v11",
            "M17.5 2c-1.6 2-2.5 4.4-2.5 7h5V2",
            "M17.5 9v13",
        ),
        icon(
            "coffee",
            "M2 8h16v9a4 4 0 0 1-4 4H6a4 4 0 0 1-4-4z",
            "M18 9h1a3.5 3.5 0 0 1 0 7h-1",
            "M6 1.5v3",
            "M10 1.5v3",
            "M14 1.5v3",
        ),
        icon(
            "groceries",
            "M6.5 2.5L3.5 6.5V20a2 2 0 0 0 2 2h13a2 2 0 0 0 2-2V6.5l-3-4z",
            "M3.5 6.5h17",
            "M16 10.5a4 4 0 0 1-8 0",
        ),
        icon(
            "shopping",
            "M12 2.5l8.5 4.8v9.4L12 21.5l-8.5-4.8V7.3z",
            "M3.8 7.2L12 12l8.2-4.8",
            "M12 21.5V12",
        ),
        icon(
            "cart",
            "M9.5 21.5a1.25 1.25 0 1 0 0-2.5 1.25 1.25 0 0 0 0 2.5z",
            "M19 21.5a1.25 1.25 0 1 0 0-2.5 1.25 1.25 0 0 0 0 2.5z",
            "M2 2.5h3.2l2.5 12.2a1.8 1.8 0 0 0 1.8 1.4h9a1.8 1.8 0 0 0 1.8-1.4L22 6.5H6",
        ),
        icon(
            "car",
            "M4 16.5V12l2-5h12l2 5v4.5",
            "M4 12h16",
            "M4 16.5h16",
            "M5 16.5v2.2",
            "M19 16.5v2.2",
            "M7.5 14h.01",
            "M16.5 14h.01",
        ),
        icon(
            "home",
            "M3 9.5l9-7 9 7V20a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
            "M9.5 22v-8h5v8",
        ),
        icon(
            "bills",
            "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z",
            "M14 2v6h6",
            "M8.5 13h7",
            "M8.5 17h7",
        ),
        icon(
            "utilities",
            "M13 2L4 14h7l-1 8 9-12h-7l1-8z",
        ),
        icon(
            "health",
            "M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8l1 1.1L12 21.2l7.8-7.7 1-1.1a5.5 5.5 0 0 0 0-7.8z",
        ),
        icon(
            "fitness",
            "M22 12h-4l-3 8.5L9 3.5 6 12H2",
        ),
        icon(
            "education",
            "M2 7.5L12 3l10 4.5-10 4.5z",
            "M6 9.7V15c0 1.7 2.7 3 6 3s6-1.3 6-3V9.7",
            "M21 8v6",
        ),
        icon(
            "travel",
            "M17.8 19.2L16 11l3.5-3.5a2.1 2.1 0 0 0-3-3L13 8 4.8 6.2a1 1 0 0 0-.9 1.7L9 11l-2 4-3-1-1 1 4 2 2 4 1-1-1-3 4-2 3.1 5.1a1 1 0 0 0 1.7-.9z",
        ),
        icon(
            "fun",
            "M2.5 3.5h19v17h-19z",
            "M7 3.5v17",
            "M17 3.5v17",
            "M2.5 9h4.5",
            "M2.5 15h4.5",
            "M17 9h4.5",
            "M17 15h4.5",
        ),
        icon(
            "music",
            "M9 18V5l12-2v13",
            "M6 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
            "M18 19a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
        ),
        icon(
            "pets",
            "M5 10.5a2 2 0 1 0 0-4 2 2 0 0 0 0 4z",
            "M9.5 7.5a2 2 0 1 0 0-4 2 2 0 0 0 0 4z",
            "M14.5 7.5a2 2 0 1 0 0-4 2 2 0 0 0 0 4z",
            "M19 10.5a2 2 0 1 0 0-4 2 2 0 0 0 0 4z",
            "M12 11.5c-3 0-5 2.6-5 5A3.5 3.5 0 0 0 10.5 20h3a3.5 3.5 0 0 0 3.5-3.5c0-2.4-2-5-5-5z",
        ),
        icon(
            "gift",
            "M20 12v9.5H4V12",
            "M2.5 7.5h19V12h-19z",
            "M12 21.5V7.5",
            "M12 7.5H7.8a2.4 2.4 0 1 1 0-4.8c3.2 0 4.2 4.8 4.2 4.8z",
            "M12 7.5h4.2a2.4 2.4 0 1 0 0-4.8C13 2.7 12 7.5 12 7.5z",
        ),
        icon(
            "work",
            "M4 7.5h16a2 2 0 0 1 2 2V19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9.5a2 2 0 0 1 2-2z",
            "M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16",
        ),
        icon(
            "salary",
            "M12 1.5v21",
            "M17 5.5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6",
        ),
        icon(
            "wallet",
            "M21 7.5V6a2 2 0 0 0-2-2H5a2 2 0 0 0 0 4h16",
            "M3 6v13a2 2 0 0 0 2 2h16v-5",
            "M17.5 11.5H22v5h-4.5a2.5 2.5 0 0 1 0-5z",
        ),
        icon(
            "bank",
            "M2.5 21h19",
            "M4 10.5h16",
            "M5 7l7-4 7 4v3.5H5z",
            "M6.5 10.5V18",
            "M10.5 10.5V18",
            "M14.5 10.5V18",
            "M18.5 10.5V18",
        ),
        icon(
            "card",
            "M3 5.5h18a1.5 1.5 0 0 1 1.5 1.5v10a1.5 1.5 0 0 1-1.5 1.5H3A1.5 1.5 0 0 1 1.5 17V7A1.5 1.5 0 0 1 3 5.5z",
            "M1.5 10h21",
            "M5.5 14.5h4",
        ),
        icon(
            "phone",
            "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z",
            "M12 18h.01",
        ),
        icon(
            "transfer",
            "M17 2.5l4 4-4 4",
            "M21 6.5H7a4 4 0 0 0-4 4",
            "M7 21.5l-4-4 4-4",
            "M3 17.5h14a4 4 0 0 0 4-4",
        ),
    )

    private val byKey: Map<String, AccountIcon> = catalog.associateBy { it.key }

    /** Per-type fallback for accounts the user never picked an icon for. */
    fun default(type: AccountType?): ImageVector = when (type) {
        AccountType.Asset -> byKey.getValue("wallet").image
        AccountType.Liability -> byKey.getValue("card").image
        AccountType.Income -> byKey.getValue("salary").image
        AccountType.Expense -> byKey.getValue("cart").image
        AccountType.Equity -> byKey.getValue("bank").image
        null -> byKey.getValue("transfer").image
    }

    /** Stored key → vector, falling back to the type's default when unknown. */
    fun resolve(key: String?, type: AccountType?): ImageVector =
        key?.let { byKey[it]?.image } ?: default(type)
}
