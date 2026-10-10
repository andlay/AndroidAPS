package app.aaps.plugins.main.mealTray

/**
 * What making a meal card needs from the phone: the barcode scanner, the product lookup and the NFC
 * writer. Android only, so the app provides it; without it the plugin has no "Make a card" screen.
 */
interface MealCardTools {

    /** Opens the barcode scanner. The barcode, or null when the user cancelled. */
    suspend fun scanBarcode(): String?

    /** Looks [barcode] up in Open Food Facts. Null when the product is not known. Throws on a network error. */
    suspend fun lookUp(barcode: String): FoodFacts?

    /** Waits for a card to be held to the phone and writes [link] to it. */
    suspend fun writeCard(link: String): CardWriteResult
}

/** A product as found in the database. Grams per 100 g of product; any value can be missing. */
data class FoodFacts(
    val name: String?,
    val brand: String?,
    val carbsPer100g: Double?,
    val proteinPer100g: Double?,
    val fatPer100g: Double?,
    val servingText: String?,
    val servingGrams: Double?
)

enum class CardWriteResult { WRITTEN, NO_NFC, NFC_OFF, READ_ONLY, TOO_SMALL, NOT_SUPPORTED, FAILED, NO_SCREEN }
