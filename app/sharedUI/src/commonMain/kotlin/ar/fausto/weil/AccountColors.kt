package ar.fausto.weil

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The palette a category can be painted with: eight pairs, sampled from the
 * design. A pair, not a color, because the design uses both halves — the disc
 * behind the glyph takes [tint], the glyph and the category's name take
 * [ink] — and the second is not derivable from the first. `ambar` darkens
 * within its own hue (#E2C79C → #A48B2F) while `terracota` drops two steps of
 * lightness *and* saturates (#B47C6B → #632A19); a `tint.darken(0.4f)` would
 * get one of them right and both of them wrong.
 *
 * Stored as [key] in `accounts.color`. Anything not in this list resolves to
 * [Neutral], so a palette entry removed here (or one written by a newer build
 * on another device) degrades to the pre-palette look instead of crashing or
 * painting a category some arbitrary color.
 */
enum class AccountColor(val key: String, val tint: Color, val ink: Color) {
    Terracota("terracota", Color(0xFFB47C6B), Color(0xFF632A19)),
    Azul("azul", Color(0xFFAEC1E2), Color(0xFF3F548B)),
    Rojo("rojo", Color(0xFFC59595), Color(0xFF6C0000)),
    Verde("verde", Color(0xFFC2D3A7), Color(0xFF5B7432)),
    Lila("lila", Color(0xFFBAA1B7), Color(0xFF562747)),
    Piedra("piedra", Color(0xFFAEC0C2), Color(0xFF3D515C)),
    Ambar("ambar", Color(0xFFE2C79C), Color(0xFFA48B2F)),
    Violeta("violeta", Color(0xFFA9A2CE), Color(0xFF32276C)),
    ;

    companion object {
        /** The stored key of an account, or null when it has no color. */
        fun of(key: String?): AccountColor? =
            key?.let { k -> entries.firstOrNull { it.key == k } }

        /**
         * The color an account gets when the user never picked one, derived
         * from its id. Every account predates the palette, so "no color"
         * would have meant a screen of grey discs until eleven dialogs had
         * been opened one by one — the design is the default, and picking is
         * the correction.
         *
         * Keyed on the **id**, not the name: ids are synced, so two devices
         * agree without writing anything, and renaming "Comida" doesn't
         * shuffle its color. Not written to the database either — a stored
         * value would be indistinguishable from a deliberate pick, and this
         * mapping has to stay free to change.
         */
        fun derived(id: String): AccountColor {
            // FNV-1a over the id: any stable hash does, but Kotlin's
            // String.hashCode is not guaranteed identical across platforms
            // and this one is, so Android and iOS paint the same category
            // the same color.
            var h = 2166136261u
            for (c in id) {
                h = h xor c.code.toUInt()
                h *= 16777619u
            }
            return entries[(h % entries.size.toUInt()).toInt()]
        }
    }
}

/**
 * What an account is actually painted with, palette entry or not. Resolved as
 * a composable so the uncolored case can be the *theme's* neutral pair rather
 * than a hardcoded grey.
 */
data class AccountPaint(val tint: Color, val ink: Color)

/**
 * [color] is the stored key. [seed] is the account id when the account is one
 * that should be colored anyway (a category — it is the thing being named and
 * counted), and null for the ones that shouldn't: an asset's disc stays
 * neutral, because on a transfer row the color would claim a meaning the row
 * doesn't have.
 */
@Composable
fun accountPaint(color: String?, seed: String? = null): AccountPaint {
    val entry = AccountColor.of(color) ?: seed?.let { AccountColor.derived(it) }
    return entry?.let { AccountPaint(it.tint, it.ink) }
        ?: AccountPaint(
            tint = MaterialTheme.colorScheme.background,
            ink = MaterialTheme.colorScheme.inverseSurface,
        )
}
