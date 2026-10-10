package app.aaps.plugins.main.mealTray

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.meal.MealTrayItem
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

internal class MealTrayPluginTest {

    @Test
    fun `a full card link is read`() {
        assertThat(FoodLink.parse("aaps://food?name=Toast%20with%20jam&carbs=15&protein=4&fat=2.5"))
            .isEqualTo(MealTrayItem(name = "Toast with jam", carbs = 15, protein = 4.0, fat = 2.5))
    }

    @Test
    fun `only carbs are needed`() {
        assertThat(FoodLink.parse("AAPS://food/?carbs=20")).isEqualTo(MealTrayItem(name = "Food", carbs = 20))
    }

    @Test
    fun `links that are not meal cards or have bad carbs are refused`() {
        assertThat(FoodLink.parse("https://example.com/?carbs=10")).isNull()
        assertThat(FoodLink.parse("aaps://food?name=Toast")).isNull()
        assertThat(FoodLink.parse("aaps://food?carbs=-5")).isNull()
        assertThat(FoodLink.parse("aaps://food?carbs=500")).isNull()
        assertThat(FoodLink.parse("aaps://food?carbs=abc")).isNull()
        assertThat(FoodLink.parse("aaps://foodie?carbs=10")).isNull()
    }

    @Test
    fun `cards add up, and an old list is cleared before the next card`() {
        var now = 1_000_000L
        val dateUtil = mock<DateUtil>()
        whenever(dateUtil.now()).thenAnswer { now }
        val plugin = MealTrayPlugin(mock<AAPSLogger>(), mock<TextResolver>(), mock<NotificationManager>(), dateUtil)

        plugin.addFromLink("aaps://food?name=Toast&carbs=15")
        plugin.addFromLink("aaps://food?name=Toast&carbs=15")
        plugin.addFromLink("aaps://food?name=Juice&carbs=20")
        assertThat(plugin.items.value.sumOf { it.carbs }).isEqualTo(50)

        plugin.remove(0)
        assertThat(plugin.items.value.map { it.name }).containsExactly("Toast", "Juice").inOrder()

        now += MealTrayPlugin.EXPIRY_MS + 1
        plugin.addFromLink("aaps://food?name=Apple&carbs=12")
        assertThat(plugin.items.value.map { it.name }).containsExactly("Apple")
    }
}
