package app.aaps.ui.compose.overview.chips

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.aaps.core.interfaces.overview.graph.TbrState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DosingChipTest {

    @get:Rule
    val compose = createComposeRule()

    private val state = DosingUiState(
        basalText = "0.00 U/h",
        smbText = "0.30 U",
        smbOn = true,
        lastRunAt = 1_000_000L,
        decisionTemp = "0.00 U/h for 30 min",
        decisionSmb = "0.30 U",
        reason = "Eventual BG 7.4 >= 5.4, insulinReq 0.4. Microbolusing 0.3U.",
        smbReason = "SMB enabled due to enableSMB_always"
    )

    @Test
    fun showsTheBasalAndTheNewestSmb() {
        compose.setContent { MaterialTheme { DosingChip(tbrState = TbrState.LOW, state = state, onTempBasalDetails = {}, modifier = Modifier.testTag("dosing")) } }

        compose.onNodeWithTag("dosing").assertIsDisplayed()
        compose.onNodeWithText("0.00 U/h", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("0.30 U", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun noSmbInTheLastRunShowsNoNumber() {
        compose.setContent { MaterialTheme { DosingChip(tbrState = TbrState.NONE, state = state.copy(smbText = "", smbOn = false), onTempBasalDetails = {}) } }

        compose.onNodeWithText("0.30 U", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aTapShowsTheDecisionAndTheReason() {
        compose.setContent { MaterialTheme { DosingChip(tbrState = TbrState.LOW, state = state, onTempBasalDetails = {}, modifier = Modifier.testTag("dosing")) } }

        compose.onNodeWithTag("dosing").performClick()

        compose.onNodeWithText(state.reason).assertIsDisplayed()
        compose.onNodeWithText(state.smbReason!!).assertIsDisplayed()
    }
}
