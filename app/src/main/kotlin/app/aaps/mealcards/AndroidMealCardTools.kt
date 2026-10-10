package app.aaps.mealcards

import android.app.Activity
import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.main.mealTray.CardWriteResult
import app.aaps.plugins.main.mealTray.FoodFacts
import app.aaps.plugins.main.mealTray.MealCardTools
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android side of NFC meal cards: Google's barcode scanner, the Open Food Facts lookup and writing
 * the card. The scanner and the NFC reader need the visible activity, which the main activity hands
 * in with [attach] and [detach].
 */
@SingleIn(AppScope::class)
@Inject
class AndroidMealCardTools(private val aapsLogger: AAPSLogger) : MealCardTools {

    @Volatile private var activity: WeakReference<Activity>? = null

    fun attach(activity: Activity) {
        this.activity = WeakReference(activity)
    }

    fun detach(activity: Activity) {
        if (this.activity?.get() === activity) this.activity = null
    }

    override suspend fun scanBarcode(): String? = withContext(Dispatchers.Main) {
        val current = activity?.get() ?: return@withContext null
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
            .build()
        suspendCancellableCoroutine { cont ->
            GmsBarcodeScanning.getClient(current, options).startScan()
                .addOnSuccessListener { if (cont.isActive) cont.resume(it.rawValue) }
                .addOnCanceledListener { if (cont.isActive) cont.resume(null) }
                .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        }
    }

    override suspend fun lookUp(barcode: String): FoodFacts? = withContext(Dispatchers.IO) {
        if (barcode.isEmpty() || !barcode.all(Char::isDigit)) return@withContext null
        val url = URL("https://world.openfoodfacts.org/api/v2/product/$barcode.json?fields=product_name,brands,nutriments,serving_size,serving_quantity")
        val connection = url.openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            // Open Food Facts asks every app to name itself
            connection.setRequestProperty("User-Agent", "AndroidAPS NFC meal cards - Android")
            when (connection.responseCode) {
                HttpsURLConnection.HTTP_OK        -> Unit
                HttpsURLConnection.HTTP_NOT_FOUND -> return@withContext null
                else                              -> throw IOException("HTTP ${connection.responseCode}")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (json.optInt("status") != 1) return@withContext null
            val product = json.optJSONObject("product") ?: return@withContext null
            val nutriments = product.optJSONObject("nutriments")
            FoodFacts(
                name = product.optString("product_name").trim().ifEmpty { null },
                brand = product.optString("brands").substringBefore(',').trim().ifEmpty { null },
                carbsPer100g = nutriments?.number("carbohydrates_100g"),
                proteinPer100g = nutriments?.number("proteins_100g"),
                fatPer100g = nutriments?.number("fat_100g"),
                servingText = product.optString("serving_size").trim().ifEmpty { null },
                servingGrams = product.number("serving_quantity")?.takeIf { it > 0 }
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.number(key: String): Double? = optDouble(key).takeIf { !it.isNaN() && it >= 0 }

    override suspend fun writeCard(link: String): CardWriteResult = withContext(Dispatchers.Main) {
        val current = activity?.get() ?: return@withContext CardWriteResult.NO_SCREEN
        val adapter = NfcAdapter.getDefaultAdapter(current) ?: return@withContext CardWriteResult.NO_NFC
        if (!adapter.isEnabled) return@withContext CardWriteResult.NFC_OFF
        val message = NdefMessage(NdefRecord.createUri(link))
        // Reader mode: while it is on, a tapped card is not also opened as a meal card
        suspendCancellableCoroutine { cont ->
            adapter.enableReaderMode(
                current,
                { tag ->
                    val result = write(tag, message)
                    current.runOnUiThread { adapter.disableReaderMode(current) }
                    if (cont.isActive) cont.resume(result)
                },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
                null
            )
            cont.invokeOnCancellation { current.runOnUiThread { adapter.disableReaderMode(current) } }
        }
    }

    private fun write(tag: Tag, message: NdefMessage): CardWriteResult = try {
        val ndef = Ndef.get(tag)
        val formatable = NdefFormatable.get(tag)
        when {
            ndef != null       -> ndef.use {
                it.connect()
                when {
                    !it.isWritable                         -> CardWriteResult.READ_ONLY
                    it.maxSize < message.toByteArray().size -> CardWriteResult.TOO_SMALL
                    else                                   -> {
                        it.writeNdefMessage(message); CardWriteResult.WRITTEN
                    }
                }
            }
            formatable != null -> formatable.use {
                it.connect(); it.format(message); CardWriteResult.WRITTEN
            }
            else               -> CardWriteResult.NOT_SUPPORTED
        }
    } catch (e: IOException) {
        aapsLogger.warn(LTag.CORE, "Meal card write failed", e)
        CardWriteResult.FAILED
    } catch (e: FormatException) {
        aapsLogger.warn(LTag.CORE, "Meal card write failed", e)
        CardWriteResult.FAILED
    }

    private companion object {

        const val TIMEOUT_MS = 10_000
    }
}
