package app.aaps.plugins.aps.replay

import java.io.File

/** One loop run as logged in the device status (values the phone really used). */
internal class LoggedRun(
    val ts: Long,
    val algorithm: String,
    val bg: Double,
    val target: Double?,
    val ratio: Double,
    val dynIsf: Boolean,
    val variableSens: Double?,
    val cob: Double?,
    val iob: Double?,
    val smbAllowed: Boolean,
    val profileSens: Double?,
    val carbRatio: Double?,
    val basal: Double?,
    val rate: Double?,
    val duration: Int?,
    val units: Double?,
    val eventualBg: Double?,
    val insulinReq: Double?,
    /** Carbs in the absorption window and minutes since the last carbs, as the loop logged them. */
    val mealCarbs: Double?,
    val lastCarbAgeMinutes: Double?,
    /** Preferences the user changed during the period, read from the log text and carried forward. */
    val uam: Boolean,
    val smbAlways: Boolean,
    val smbInterval: Int,
    /** The phone asked for this SMB or temp change but the pump never delivered it. */
    val smbFailed: Boolean,
    val tempFailed: Boolean
)

internal class LoggedBolus(val ts: Long, val units: Double, val smb: Boolean)

/**
 * [minutes] is how long the temp really ran (Nightscout keeps that), [plannedMinutes] what the loop
 * asked for. The loop sees the planned remaining time, the pump delivered the real one.
 */
internal class LoggedTemp(val ts: Long, val rate: Double, val minutes: Int, val plannedMinutes: Int) {

    /** Set after loading: a temp ends after its real duration or when the next one starts. */
    var end: Long = ts + minutes * 60_000L
}

/**
 * Input for the loop replay, written by a script from Nightscout data (see [LoopReplay]).
 * Plain text, one record per line, so the replay needs no JSON library.
 */
internal class ReplayInput(file: File) {

    val settings = HashMap<String, String>()
    val candidateBasal = ArrayList<Pair<Int, Double>>()
    val candidateTargets = ArrayList<Triple<Int, Double, Double>>()
    val midnights = ArrayList<Long>()
    val boluses = ArrayList<LoggedBolus>()
    val temps = ArrayList<LoggedTemp>()
    val carbs = ArrayList<Pair<Long, Double>>()
    val runs = ArrayList<LoggedRun>()

    val bgTime: LongArray
    val truth: DoubleArray
    val raw: DoubleArray

    /** views[i][k] = what the loop saw at reading i for reading i-k, minus the true BG of reading i-k. */
    val views: Array<DoubleArray>

    private val basalFromLog = ArrayList<Pair<Long, Double>>()
    private val basalFromStore = ArrayList<Pair<Long, Double>>()

    init {
        val bgT = ArrayList<Long>()
        val bgTruth = ArrayList<Double>()
        val bgRaw = ArrayList<Double>()
        val viewRows = HashMap<Int, DoubleArray>()
        var runFields: List<String> = emptyList()
        file.forEachLine { line ->
            val p = line.trim().split(' ')
            when (p[0]) {
                "P"   -> {
                    settings[p[1]] = p[2]
                    if (p[1] == "run_fields") runFields = p[2].split(',')
                }

                "CB"  -> candidateBasal.add(p[1].toInt() to p[2].toDouble())
                "CT"  -> candidateTargets.add(Triple(p[1].toInt(), p[2].toDouble(), p[3].toDouble()))
                "DAY" -> midnights.add(p[1].toLong())
                "G"   -> {
                    bgT.add(p[1].toLong()); bgTruth.add(p[2].toDouble()); bgRaw.add(p[3].toDouble())
                }

                "V"   -> viewRows[p[1].toInt()] = DoubleArray(10) { p[2 + it].toDouble() }
                "B"   -> boluses.add(LoggedBolus(p[1].toLong(), p[2].toDouble(), p[3] == "SMB"))
                "T"   -> temps.add(LoggedTemp(p[1].toLong(), p[2].toDouble(), p[3].toDouble().toInt(), (p.getOrNull(4) ?: p[3]).toDouble().toInt()))
                "C"   -> carbs.add(p[1].toLong() to p[2].toDouble())
                "OB"  -> (if (p.getOrNull(3) == "log") basalFromLog else basalFromStore).add(p[1].toLong() to p[2].toDouble())
                "R"   -> runs.add(parseRun(runFields, p.drop(1)))
            }
        }
        bgTime = bgT.toLongArray()
        truth = bgTruth.toDoubleArray()
        raw = bgRaw.toDoubleArray()
        views = Array(bgTime.size) { viewRows[it] ?: DoubleArray(10) }
        boluses.sortBy { it.ts }
        carbs.sortBy { it.first }
        runs.sortBy { it.ts }
        midnights.sort()
        candidateBasal.sortBy { it.first }
        candidateTargets.sortBy { it.first }
        basalFromLog.sortBy { it.first }
        basalFromStore.sortBy { it.first }
        temps.sortBy { it.ts }
        for (i in temps.indices) if (i + 1 < temps.size) temps[i].end = minOf(temps[i].end, temps[i + 1].ts)
    }

    private fun parseRun(fields: List<String>, values: List<String>): LoggedRun {
        val m = fields.zip(values).toMap()
        fun d(key: String): Double? = m[key]?.takeIf { it != "NA" }?.toDouble()
        return LoggedRun(
            ts = m.getValue("ts").toLong(),
            algorithm = m.getValue("algo"),
            bg = d("bg") ?: 0.0,
            target = d("target"),
            ratio = d("ratio") ?: 1.0,
            dynIsf = m["dyn"] == "1",
            variableSens = d("vsens"),
            cob = d("cob"),
            iob = d("iob") ?: d("iob_obs"),
            smbAllowed = m["smb_allowed"] == "1",
            profileSens = d("psens"),
            carbRatio = d("cr"),
            basal = d("basal"),
            rate = d("rate"),
            duration = d("duration")?.toInt(),
            units = d("units"),
            eventualBg = d("eventual"),
            insulinReq = d("insulinReq"),
            mealCarbs = d("meal_carbs"),
            lastCarbAgeMinutes = d("last_carb_age"),
            uam = m["uam"] != "0",
            smbAlways = m["smb_always"] != "0",
            smbInterval = d("smb_interval")?.toInt() ?: 3,
            smbFailed = m["smb_failed"] == "1",
            tempFailed = m["temp_failed"] == "1"
        )
    }

    fun setting(key: String): String? = settings[key]

    /** Seconds since local midnight, using the midnights from the input (daylight saving safe). */
    fun secondsOfDay(t: Long): Int {
        val i = midnights.binarySearch(t).let { if (it >= 0) it else -it - 2 }
        return ((t - midnights[maxOf(i, 0)]) / 1000).toInt()
    }

    fun midnightBefore(t: Long): Long {
        val i = midnights.binarySearch(t).let { if (it >= 0) it else -it - 2 }
        return midnights[maxOf(i, 0)]
    }

    fun candidateBasalAt(t: Long): Double {
        val s = secondsOfDay(t)
        return candidateBasal.lastOrNull { it.first <= s }?.second ?: candidateBasal.first().second
    }

    fun candidateTargetAt(t: Long): Pair<Double, Double> {
        val s = secondsOfDay(t)
        val v = candidateTargets.lastOrNull { it.first <= s } ?: candidateTargets.first()
        return v.second to v.third
    }

    /** Scheduled basal the pump really ran: the loop's own log line, else the Nightscout profile store. */
    fun observedBasalAt(t: Long): Double {
        lastAtOrBefore(basalFromLog, t)?.let { if (t - it.first <= 30 * 60_000L) return it.second }
        return lastAtOrBefore(basalFromStore, t)?.second ?: candidateBasalAt(t)
    }

    fun lastRunAtOrBefore(t: Long, maxAgeMs: Long): LoggedRun? {
        var lo = 0
        var hi = runs.size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (runs[mid].ts <= t) {
                best = mid; lo = mid + 1
            } else hi = mid - 1
        }
        return if (best >= 0 && t - runs[best].ts <= maxAgeMs) runs[best] else null
    }

    private fun lastAtOrBefore(list: List<Pair<Long, Double>>, t: Long): Pair<Long, Double>? {
        var lo = 0
        var hi = list.size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (list[mid].first <= t) {
                best = mid; lo = mid + 1
            } else hi = mid - 1
        }
        return if (best >= 0) list[best] else null
    }
}
