package app.aaps.plugins.aps.replay

import java.io.File
import java.util.concurrent.Executors
import kotlin.test.Test

/**
 * Entry point for the loop replay. Does nothing unless `REPLAY_IN` is set, so it is a no-op in CI.
 *
 * Environment:
 * - `REPLAY_IN`: input file (see [ReplayInput])
 * - `REPLAY_OUT`: folder for the results
 * - `REPLAY_JOBS`: file with one [Scenario] per line (optional)
 * - `REPLAY_CHECK=1`: also rebuild every logged loop run and compare with what the phone decided
 * - `REPLAY_DIA`: insulin DIA in hours (default 5), peak is 55 min (Fiasp)
 *
 * Run: `REPLAY_IN=... REPLAY_OUT=... ./gradlew :plugins:aps:jvmTest --tests "*LoopReplayTest*" --rerun`
 */
class LoopReplayTest {

    @Test
    fun replay() {
        val inPath = System.getenv("REPLAY_IN") ?: return
        val out = File(System.getenv("REPLAY_OUT") ?: error("REPLAY_OUT not set")).apply { mkdirs() }
        val input = ReplayInput(File(inPath))
        val dia = (System.getenv("REPLAY_DIA") ?: "5").toDouble()
        val peak = (System.getenv("REPLAY_PEAK") ?: "55").toInt()

        if (System.getenv("REPLAY_CHECK") == "1")
            File(out, "check_dia$dia.tsv").writeText(LoopReplay(input, dia, peak).checkAgainstLog().joinToString("\n") + "\n")

        val jobs = System.getenv("REPLAY_JOBS")?.let { File(it).readLines() }.orEmpty()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { Scenario.parse(it) }
        if (jobs.isEmpty()) return
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val pool = Executors.newFixedThreadPool(threads)
        // one replay per thread: DetermineBasalSMB keeps its console lists as state
        val replays = ThreadLocal.withInitial { LoopReplay(input, dia, peak) }
        val results = jobs.map { job -> pool.submit<ReplayResult> { replays.get().run(job) } }.map { it.get() }
        pool.shutdown()

        val keys = results.first().metrics.keys.toList()
        val lines = ArrayList<String>()
        lines.add((listOf("name", "asRun", "pct", "basal", "isf", "cr", "dyn", "factor", "smbMin", "trueIsf") + keys).joinToString("\t"))
        for (r in results) {
            val s = r.scenario
            lines.add(
                (listOf(s.name, if (s.asRun) "1" else "0", s.percentage, s.basalScale, s.isfScale, s.crScale, if (s.dynIsf) "1" else "0", s.dynFactor, s.smbMinBg, s.trueIsf)
                    .map { it.toString() } + keys.map { "%.3f".format(r.metrics[it]) }).joinToString("\t")
            )
            if (s.trace) File(out, "trace_${s.name}.tsv").writeText(r.trace.joinToString("\n") + "\n")
        }
        File(out, "summary.tsv").writeText(lines.joinToString("\n") + "\n")
    }
}
