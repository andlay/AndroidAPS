package app.aaps.database

import app.aaps.database.daos.ApsResultMpcValues
import app.aaps.database.di.JvmAppDatabaseBuilder
import app.aaps.database.entities.APSResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test

/** The MPC meal values query of the graph, against a real database file. Read with `json_extract` like the AutoISF factors. */
class ApsResultMpcValuesTest {

    private val directory = createTempDirectory("aaps-mpc-test")
    private val database = JvmAppDatabaseBuilder().provideAppDatabase("$directory/aaps.db")

    @AfterTest
    fun tearDown() {
        database.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `reads the MPC values of the results that have them, oldest first`() = runTest {
        database.apsResultDao.insert(
            apsResult(
                NOW + FIVE_MIN,
                """{"mpc":{"mealChance":0.4,"weightedMealChance":0.7,"carbsIfMeal":30.5,"carbsIfNoMeal":4.0,"carbsExpected":22.6,"impactAgeMinutes":15}}"""
            )
        )
        database.apsResultDao.insert(
            apsResult(NOW, """{"mpc":{"mealChance":0.0,"weightedMealChance":0.1,"carbsIfMeal":0.0,"carbsIfNoMeal":0.0,"carbsExpected":0.0,"impactAgeMinutes":-1}}""")
        )
        // MPC meal SMBs off: no values, so no row
        database.apsResultDao.insert(apsResult(NOW + 2 * FIVE_MIN, """{"variable_sens":47.0}"""))

        assertThat(database.apsResultDao.getMpcValues(NOW, NOW + 3 * FIVE_MIN)).containsExactly(
            ApsResultMpcValues(NOW, 0.0, 0.1, 0.0, 0.0, 0.0),
            ApsResultMpcValues(NOW + FIVE_MIN, 0.4, 0.7, 30.5, 4.0, 22.6)
        ).inOrder()
    }

    private fun apsResult(timestamp: Long, resultJson: String) = APSResult(
        timestamp = timestamp,
        algorithm = APSResult.Algorithm.SMB,
        glucoseStatusJson = null,
        currentTempJson = null,
        iobDataJson = null,
        profileJson = null,
        autosensDataJson = null,
        mealDataJson = null,
        resultJson = resultJson
    )

    private companion object {

        const val NOW = 1_800_000_000_000L
        const val FIVE_MIN = 5 * 60 * 1000L
    }
}
