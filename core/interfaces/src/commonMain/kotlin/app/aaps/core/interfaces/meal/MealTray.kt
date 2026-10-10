package app.aaps.core.interfaces.meal

import kotlinx.coroutines.flow.StateFlow

/**
 * A list of foods collected before a meal, for example by tapping NFC cards, to open the bolus
 * wizard with their total carbs. It never doses: it only fills in the wizard, which the user
 * checks and confirms as usual.
 */
interface MealTray {

    /** The foods added so far, oldest first. Empty when nothing was added or the list expired. */
    val items: StateFlow<List<MealTrayItem>>

    /**
     * Adds the food described by [link] (`aaps://food?name=Toast&carbs=15&protein=4&fat=2`).
     * Returns the food added, or null when the link is not a valid food.
     */
    fun addFromLink(link: String): MealTrayItem?

    fun remove(index: Int)

    fun clear()
}

/** One food. Carbs in grams; protein and fat in grams, shown only (not used for dosing). */
data class MealTrayItem(
    val name: String,
    val carbs: Int,
    val protein: Double? = null,
    val fat: Double? = null
)
