package app.aaps.ui.compose.overview.chips

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CarbsChipTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsCobAndTheCarbsRequiredBelowIt() {
        compose.setContent { MaterialTheme { CarbsChip(CobUiState(text = "12 g 16req", carbsReq = 16, cobValue = 12.0, valueText = "12 g", carbsReqText = "16 g req")) } }

        compose.onNodeWithText("12 g", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("16 g req", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun noCarbsRequiredShowsCobOnly() {
        compose.setContent { MaterialTheme { CarbsChip(CobUiState(text = "0 g", valueText = "0 g")) } }

        compose.onNodeWithText("0 g", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("g req", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }
}
