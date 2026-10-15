package app.aaps.database

import app.aaps.database.daos.ApsResultAutoIsfFactors
import app.aaps.database.di.JvmAppDatabaseBuilder
import app.aaps.database.entities.APSResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The AutoISF factors query of the graph, against a real database file. Read with `json_extract`
 * like the variable sensitivity (see [ApsResultVariableSensTest]).
 */
class ApsResultAutoIsfFactorsTest {

    private val directory = createTempDirectory("aaps-autoisf-test")
    private val database = JvmAppDatabaseBuilder().provideAppDatabase("$directory/aaps.db")

    @AfterTest
    fun tearDown() {
        database.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `reads the factors of the results that have them, oldest first`() = runTest {
        database.apsResultDao.insert(
            apsResult(NOW + FIVE_MIN, """{"variable_sens":40.0,"autoIsfFactors":{"acce":1.2,"bg":1.05,"pp":1.01,"dura":1.0,"applied":1.2}}""")
        )
        database.apsResultDao.insert(
            apsResult(NOW, """{"variable_sens":47.0,"autoIsfFactors":{"acce":0.98,"bg":0.96,"pp":1.0,"dura":1.0,"applied":0.96}}""")
        )
        // Not AutoISF, or the weights off: no factors, so no row
        database.apsResultDao.insert(apsResult(NOW + 2 * FIVE_MIN, """{"variable_sens":47.0}"""))
        // Outside the interval
        database.apsResultDao.insert(
            apsResult(NOW + 3 * FIVE_MIN + 1, """{"autoIsfFactors":{"acce":1.0,"bg":1.0,"pp":1.0,"dura":1.0,"applied":1.0}}""")
        )

        assertThat(database.apsResultDao.getAutoIsfFactors(NOW, NOW + 3 * FIVE_MIN)).containsExactly(
            ApsResultAutoIsfFactors(NOW, 0.98, 0.96, 1.0, 1.0, 0.96),
            ApsResultAutoIsfFactors(NOW + FIVE_MIN, 1.2, 1.05, 1.01, 1.0, 1.2)
        ).inOrder()
    }

    private fun apsResult(timestamp: Long, resultJson: String) = APSResult(
        timestamp = timestamp,
        algorithm = APSResult.Algorithm.AUTO_ISF,
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
