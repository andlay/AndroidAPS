package app.aaps.ui.compose.overview.graphs

import kotlin.test.Test
import kotlin.test.assertEquals

/** The right axis shows as many decimals as its tick step needs, no more. */
class GraphEndAxisTest {

    @Test
    fun wholeStepsHaveNoDecimals() {
        assertEquals(0, decimalsFor(1.0))
        assertEquals(0, decimalsFor(20.0))
    }

    @Test
    fun halfAndTenthStepsHaveOneDecimal() {
        assertEquals(1, decimalsFor(0.5))
        assertEquals(1, decimalsFor(2.5))
        assertEquals(1, decimalsFor(0.2))
    }

    @Test
    fun quarterStepsHaveTwoDecimals() {
        assertEquals(2, decimalsFor(0.25))
        assertEquals(2, decimalsFor(0.05))
    }

    @Test
    fun noFixedStepShowsOneDecimal() {
        assertEquals(1, decimalsFor(null))
    }
}
