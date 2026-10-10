package app.aaps.plugins.main.mealTray

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.meal.MealTray
import app.aaps.core.interfaces.meal.MealTrayItem
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.ui.compose.icons.IcCarbs
import app.aaps.plugins.main.MainStrings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntKey
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * NFC meal cards: tapping a card adds its food to a list, and the list opens the bolus wizard with
 * the total carbs. Reading the card is done by the app (Android NFC); this plugin keeps the list.
 *
 * The list forgets itself [EXPIRY_MS] after the last change, so an old, half finished meal is never
 * added to the next one. Nothing here doses.
 */
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(880)
@SingleIn(AppScope::class)
@Inject
class MealTrayPlugin(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    notificationManager: NotificationManager,
    private val dateUtil: DateUtil
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .icon(IcCarbs)
        .pluginName(MainStrings.meal_tray_name)
        .description(MainStrings.meal_tray_description),
    aapsLogger, rh, notificationManager
), MealTray {

    private val _items = MutableStateFlow<List<MealTrayItem>>(emptyList())
    override val items: StateFlow<List<MealTrayItem>> = _items.asStateFlow()

    private var lastChange = 0L

    override fun addFromLink(link: String): MealTrayItem? {
        val item = FoodLink.parse(link)
        if (item == null) {
            aapsLogger.warn(LTag.CORE, "Meal card not read: $link")
            return null
        }
        val now = dateUtil.now()
        _items.update { current -> (if (now - lastChange > EXPIRY_MS) emptyList() else current) + item }
        lastChange = now
        aapsLogger.info(LTag.CORE, "Meal card added: ${item.name} ${item.carbs} g")
        return item
    }

    override fun remove(index: Int) {
        _items.update { current -> if (index in current.indices) current.filterIndexed { i, _ -> i != index } else current }
        lastChange = dateUtil.now()
    }

    override fun clear() {
        _items.value = emptyList()
    }

    internal companion object {

        const val EXPIRY_MS = 15 * 60 * 1000L
    }
}
