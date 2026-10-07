package app.aaps.implementation.aps

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.SHOWER_HISTORY_KEEP_MS
import app.aaps.core.interfaces.aps.ShowerEpisode
import app.aaps.core.interfaces.aps.ShowerMode
import app.aaps.core.interfaces.aps.encodeShowerEpisodes
import app.aaps.core.interfaces.aps.showerEpisodes
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.DoubleNonKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.CoreUiStrings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Stores shower mode in preferences: the running one (end time, BG cap), which the algorithm reads
 * with `activeShowerCapMgdl`, and the history of episodes, which keeps the BG history capped. A note with the length goes into treatments, so it shows on the graph and in
 * Nightscout.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class ShowerModeImpl(
    private val preferences: Preferences,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil,
    private val rh: TextResolver
) : ShowerMode {

    override suspend fun start(minutes: Int): Boolean {
        val bg = glucoseStatusProvider.glucoseStatusData?.glucose ?: return false
        val now = dateUtil.now()
        preferences.put(DoubleNonKey.ShowerModeCapMgdl, bg)
        preferences.put(LongNonKey.ShowerModeStartedAt, now)
        preferences.put(LongNonKey.ShowerModeEndsAt, now + T.mins(minutes.toLong()).msecs())
        // The history the loop keeps capped, see applyShowerCaps. Old episodes are dropped here.
        val episodes = preferences.showerEpisodes().filter { it.end > now - SHOWER_HISTORY_KEEP_MS } +
            ShowerEpisode(start = now, end = now + T.mins(minutes.toLong()).msecs(), capMgdl = bg)
        preferences.put(StringNonKey.ShowerModeEpisodes, encodeShowerEpisodes(episodes))
        val note = rh.gs(CoreUiStrings.shower_mode_note, rh.gs(CoreUiStrings.format_mins, minutes))
        persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
            therapyEvent = TE(
                timestamp = now,
                type = TE.Type.NOTE,
                duration = T.mins(minutes.toLong()).msecs(),
                note = note,
                enteredBy = "AndroidAPS",
                glucoseUnit = GlucoseUnit.MGDL
            ),
            action = Action.CAREPORTAL,
            source = Sources.Note,
            note = note,
            timestamp = now,
            listValues = listOf(ValueWithUnit.Timestamp(now), ValueWithUnit.TEType(TE.Type.NOTE), ValueWithUnit.Minute(minutes))
        )
        return true
    }

    override fun stop() {
        preferences.put(LongNonKey.ShowerModeEndsAt, 0L)
        // Ended early: the episode ends now, so the cap and its tail follow the real end.
        val now = dateUtil.now()
        val episodes = preferences.showerEpisodes().map { if (it.start <= now && it.end > now) it.copy(end = now) else it }
        preferences.put(StringNonKey.ShowerModeEpisodes, encodeShowerEpisodes(episodes))
    }
}
