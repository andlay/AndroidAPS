package app.aaps

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.ui.compose.navigation.color
import app.aaps.core.ui.compose.navigation.description
import app.aaps.core.ui.compose.navigation.icon
import app.aaps.core.ui.compose.navigation.label
import app.aaps.core.ui.compose.stringResourceOrNull
import kotlin.math.roundToInt

/**
 * Launcher shortcuts (long press on the app icon): the treatments from the Treatments sheet.
 *
 * Most launchers show only the first four, so the order matters: the most used first.
 * Treatment (insulin and carbs together) is last, because Insulin and Carbs cover it.
 */
internal val appShortcutElements = listOf(
    ElementType.CARBS,
    ElementType.INSULIN,
    ElementType.BOLUS_WIZARD,
    ElementType.SHOWER_MODE,
    ElementType.TREATMENT
)

internal const val ACTION_APP_SHORTCUT = "app.aaps.action.APP_SHORTCUT"
private const val EXTRA_ELEMENT = "element"

/** The element a launcher shortcut asks for, or null when [intent] did not come from one. */
internal fun appShortcutElement(intent: Intent?): ElementType? {
    if (intent?.action != ACTION_APP_SHORTCUT) return null
    val name = intent.getStringExtra(EXTRA_ELEMENT) ?: return null
    // The activity is exported: accept only the shortcuts we publish, nothing else by name.
    return appShortcutElements.firstOrNull { it.name == name }
}

/**
 * Publishes a shortcut for each of [visible] (a subset of [appShortcutElements]), with the same
 * label, icon and colour as in the Treatments sheet. Published again when the list or the theme changes.
 */
@Composable
internal fun PublishAppShortcuts(visible: List<ElementType>, activityClass: Class<*>, onError: (Exception) -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val surface = MaterialTheme.colorScheme.surface
    // Fixed list, so the painters are remembered in the same order on every pass.
    val styles = appShortcutElements.map { type ->
        ShortcutStyle(
            type = type,
            label = stringResourceOrNull(type.label()) ?: type.name,
            description = stringResourceOrNull(type.description()),
            painter = rememberVectorPainter(type.icon()),
            color = type.color()
        )
    }
    LaunchedEffect(visible, surface, styles.map { it.color }) {
        val shortcuts = styles.filter { it.type in visible }.mapIndexed { rank, style ->
            ShortcutInfoCompat.Builder(context, style.type.name)
                .setShortLabel(style.label)
                .apply { style.description?.let { setLongLabel(it) } }
                .setRank(rank)
                .setIcon(IconCompat.createWithAdaptiveBitmap(shortcutIcon(style.painter, style.color, surface, density)))
                .setIntent(
                    Intent(context, activityClass)
                        .setAction(ACTION_APP_SHORTCUT)
                        .putExtra(EXTRA_ELEMENT, style.type.name)
                )
                .build()
        }
        try {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        } catch (e: Exception) {
            // Some launchers refuse shortcuts (rate limit, work profile). The app works without them.
            onError(e)
        }
    }
}

private class ShortcutStyle(
    val type: ElementType,
    val label: String,
    val description: String?,
    val painter: Painter,
    val color: Color
)

/**
 * The sheet's tonal icon, drawn as an adaptive launcher icon: the element colour at low strength
 * behind the glyph. The glyph takes the same share of the circle as `TonalIcon` (24 of 40 dp).
 */
private fun shortcutIcon(painter: Painter, color: Color, surface: Color, density: Density): Bitmap {
    // Adaptive icons are 108 dp, and the launcher shows the middle 72 dp.
    val sizePx = with(density) { 108.dp.toPx() }.roundToInt()
    val glyphPx = with(density) { (72.dp * 24f / 40f).toPx() }
    val bitmap = ImageBitmap(sizePx, sizePx)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(sizePx.toFloat(), sizePx.toFloat())) {
        drawRect(color.copy(alpha = 0.12f).compositeOver(surface))
        val offset = (sizePx - glyphPx) / 2f
        translate(offset, offset) {
            with(painter) { draw(Size(glyphPx, glyphPx), colorFilter = ColorFilter.tint(color)) }
        }
    }
    return bitmap.asAndroidBitmap()
}
