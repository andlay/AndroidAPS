package app.aaps.plugins.main.mealTray

import app.aaps.core.interfaces.meal.MealTrayItem
import kotlin.math.round

/**
 * Reads a food from an NFC card link: `aaps://food?name=Toast&carbs=15&protein=4&fat=2`.
 *
 * Only carbs are required. The values are checked here because anyone can write a card: a link
 * that is not ours, has no carbs, or has carbs outside 0 to [MAX_CARBS_PER_FOOD] is refused.
 */
internal object FoodLink {

    const val MAX_CARBS_PER_FOOD = 200
    private const val PREFIX = "aaps://food"
    private const val MAX_NAME_LENGTH = 40

    fun parse(link: String): MealTrayItem? {
        val text = link.trim()
        if (!text.startsWith(PREFIX, ignoreCase = true)) return null
        val query = text.substring(PREFIX.length).removePrefix("/").let { if (it.startsWith("?")) it.substring(1) else return null }
        val values = query.split('&').mapNotNull { pair ->
            val index = pair.indexOf('=')
            if (index <= 0) null else decode(pair.substring(0, index)).lowercase() to decode(pair.substring(index + 1))
        }.toMap()
        val carbs = values["carbs"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it <= MAX_CARBS_PER_FOOD } ?: return null
        val name = values["name"]?.trim()?.take(MAX_NAME_LENGTH)?.takeIf { it.isNotEmpty() } ?: "Food"
        return MealTrayItem(
            name = name,
            carbs = round(carbs).toInt(),
            protein = values["protein"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 },
            fat = values["fat"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
        )
    }

    /** Percent decoding as in a URL query ("%20" and "+" are a space). Invalid escapes stay as they are. */
    private fun decode(value: String): String {
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '+' -> { bytes.add(' '.code.toByte()); i++ }
                c == '%' && i + 2 < value.length && value.substring(i + 1, i + 3).toIntOrNull(16) != null -> {
                    bytes.add(value.substring(i + 1, i + 3).toInt(16).toByte()); i += 3
                }
                else -> { c.toString().encodeToByteArray().forEach { bytes.add(it) }; i++ }
            }
        }
        return bytes.toByteArray().decodeToString()
    }
}
