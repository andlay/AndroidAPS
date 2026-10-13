package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.overview.graph.AxisRange
import app.aaps.core.interfaces.overview.graph.GraphConfig
import app.aaps.core.interfaces.overview.graph.GraphConfigRepository
import app.aaps.core.interfaces.overview.graph.GraphTrace
import app.aaps.core.interfaces.overview.graph.PlotStyle
import app.aaps.core.interfaces.overview.graph.SecondaryGraph
import app.aaps.core.interfaces.overview.graph.SeriesType
import app.aaps.core.interfaces.overview.graph.TraceTransform
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class GraphConfigRepositoryImpl(
    private val preferences: Preferences,
    private val aapsLogger: AAPSLogger
) : GraphConfigRepository {

    // Not the main dispatcher: all this scope does is parse a preference string into a StateFlow,
    // which is safe from any thread, and reaching for `Dispatchers.Main` in a constructor makes the
    // object graph unbuildable in a plain JVM unit test (no main looper).
    private val scope = CoroutineScope(SupervisorJob() + aapsIoDispatcher)
    private val _graphConfigFlow = MutableStateFlow(load())
    override val graphConfigFlow: StateFlow<GraphConfig> = _graphConfigFlow.asStateFlow()

    init {
        // Sync from preferences when changed externally (e.g. import/restore)
        scope.launch {
            preferences.observe(StringNonKey.ComposeGraphConfig)
                .drop(1) // Skip initial (already loaded)
                .collect { json -> _graphConfigFlow.value = parseJson(json) }
        }
    }

    override fun update(config: GraphConfig) {
        _graphConfigFlow.value = config
        preferences.put(StringNonKey.ComposeGraphConfig, toJson(config))
    }

    private fun load(): GraphConfig = parseJson(preferences.get(StringNonKey.ComposeGraphConfig))

    private fun parseJson(json: String): GraphConfig {
        if (json.isBlank()) return GraphConfig()
        return try {
            fromJson(json)
        } catch (e: Exception) {
            aapsLogger.error(LTag.UI, "Failed to parse graph config, using defaults", e)
            GraphConfig()
        }
    }

    companion object {

        private const val KEY_SECONDARY_GRAPHS = "secondaryGraphs"
        private const val KEY_BG_OVERLAYS = "bgOverlays"
        private const val KEY_IOB_OVERLAYS = "iobOverlays"
        private const val KEY_BG_HEIGHT = "bgHeight"
        private const val KEY_IOB_HEIGHT = "iobHeight"
        private const val KEY_SERIES = "series"
        private const val KEY_HEIGHT = "height"
        private const val KEY_TRACES = "traces"
        private const val KEY_TYPE = "type"
        private const val KEY_TRANSFORM = "transform"
        private const val KEY_WINDOW = "window"
        private const val KEY_STYLE = "style"
        private const val KEY_COLOR = "color"
        private const val KEY_VERSION = "version"
        private const val KEY_BG_TRACES = "bgTraces"
        private const val KEY_BG_LEFT_RANGE = "bgLeftRange"
        private const val KEY_BG_RIGHT_RANGE = "bgRightRange"
        private const val KEY_LEFT_RANGE = "leftRange"
        private const val KEY_RIGHT_RANGE = "rightRange"
        private const val KEY_ACTIVITY_OVERLAY = "activityOverlay"
        private const val KEY_MIN = "min"
        private const val KEY_MAX = "max"

        /**
         * 2: the IOB graph is the first graph of the list. Before, it was a fixed graph of its own
         * with [KEY_IOB_HEIGHT] and [KEY_IOB_OVERLAYS]; such a config gets it put in front once.
         */
        private const val FORMAT_VERSION = 2

        private fun overlaysToJson(overlays: List<SeriesType>) = buildJsonArray {
            for (type in overlays) add(type.name)
        }

        /**
         * Absent means "never written", so the caller's default applies. An empty array means the
         * user turned them all off, which is a different thing and is kept.
         */
        private fun overlaysFromJson(arr: JsonArray?, default: List<SeriesType>): List<SeriesType> {
            if (arr == null) return default
            val out = mutableListOf<SeriesType>()
            for (element in arr) {
                val type = element.seriesTypeOrNull() ?: continue // Skip unknown types (forward compat)
                if (type !in out) out.add(type)
            }
            return out
        }

        fun toJson(config: GraphConfig): String =
            buildJsonObject {
                put(KEY_VERSION, FORMAT_VERSION)
                put(KEY_BG_OVERLAYS, overlaysToJson(config.bgOverlays))
                put(KEY_BG_HEIGHT, config.bgHeight)
                put(KEY_BG_TRACES, buildJsonArray { for (trace in config.bgTraces) add(traceToJson(trace)) })
                rangeToJson(config.bgLeftRange)?.let { put(KEY_BG_LEFT_RANGE, it) }
                rangeToJson(config.bgRightRange)?.let { put(KEY_BG_RIGHT_RANGE, it) }
                put(
                    KEY_SECONDARY_GRAPHS,
                    buildJsonArray {
                        for (graph in config.secondaryGraphs) {
                            addJsonObject {
                                // Kept for older versions, which only read the series names
                                put(KEY_SERIES, buildJsonArray { for (series in graph.series.distinct()) add(series.name) })
                                put(KEY_HEIGHT, graph.height)
                                put(KEY_TRACES, buildJsonArray { for (trace in graph.traces) add(traceToJson(trace)) })
                                rangeToJson(graph.leftRange)?.let { put(KEY_LEFT_RANGE, it) }
                                rangeToJson(graph.rightRange)?.let { put(KEY_RIGHT_RANGE, it) }
                                if (graph.activityOverlay) put(KEY_ACTIVITY_OVERLAY, true)
                            }
                        }
                    }
                )
            }.toString()

        private fun traceToJson(trace: GraphTrace): JsonObject = buildJsonObject {
            put(KEY_TYPE, trace.type.name)
            put(KEY_TRANSFORM, trace.transform.name)
            put(KEY_WINDOW, trace.windowMinutes)
            trace.style?.let { put(KEY_STYLE, it.name) }
            trace.color?.let { put(KEY_COLOR, it) }
        }

        /** Null for Auto, so nothing is written for it. */
        private fun rangeToJson(range: AxisRange): JsonObject? =
            if (range.isAuto) null
            else buildJsonObject {
                range.min?.let { put(KEY_MIN, it) }
                range.max?.let { put(KEY_MAX, it) }
            }

        /** A stored range; Auto when absent, and for an end that is not a number. A range with min >= max is dropped. */
        private fun rangeFromJson(element: JsonElement?): AxisRange {
            val obj = element as? JsonObject ?: return AxisRange.AUTO
            fun end(key: String) = (obj[key] as? JsonPrimitive)?.let { runCatching { it.double }.getOrNull() }?.takeIf { it.isFinite() }
            val range = AxisRange(end(KEY_MIN), end(KEY_MAX))
            val min = range.min
            val max = range.max
            return if (min != null && max != null && min >= max) AxisRange.AUTO else range
        }

        /** A stored trace, or null when its series is unknown to this version. Unknown options fall back to defaults. */
        private fun traceFromJson(element: JsonElement): GraphTrace? {
            val obj = element as? JsonObject ?: return null
            val type = obj[KEY_TYPE]?.seriesTypeOrNull() ?: return null
            val transform = obj.string(KEY_TRANSFORM)?.let { name -> TraceTransform.entries.firstOrNull { it.name == name } } ?: TraceTransform.NONE
            val window = ((obj[KEY_WINDOW] as? JsonPrimitive)?.let { runCatching { it.int }.getOrNull() } ?: GraphTrace.DEFAULT_WINDOW_MINUTES)
                .coerceIn(GraphTrace.MIN_WINDOW_MINUTES, GraphTrace.MAX_WINDOW_MINUTES)
            val style = obj.string(KEY_STYLE)?.let { name -> PlotStyle.entries.firstOrNull { it.name == name } }
            val color = (obj[KEY_COLOR] as? JsonPrimitive)?.let { runCatching { it.long }.getOrNull() }
            return GraphTrace(type, transform, window, style, color)
        }

        /**
         * Traces without repeats, cut down to fit one graph. IOB is kept here: the user picked it in
         * this format. Only the old format drops it (see [parseSeriesArray]).
         */
        private fun cleanTraces(traces: List<GraphTrace>): List<GraphTrace> {
            val out = mutableListOf<GraphTrace>()
            for (trace in traces) {
                if (out.none { it.sameDataAs(trace) }) out.add(trace)
            }
            return out.fitToGraph(keep = out.firstOrNull())
        }

        /** Extra traces of the BG graph: no repeats, and no BG as it is (the BG graph draws that itself). */
        private fun cleanBgTraces(traces: List<GraphTrace>): List<GraphTrace> =
            cleanTraces(listOf(GraphTrace(SeriesType.BG)) + traces).filter { it.type != SeriesType.BG || it.isDerived }

        private fun parseSeriesArray(seriesArray: JsonArray): List<SeriesType> {
            val series = mutableListOf<SeriesType>()
            for (element in seriesArray) {
                val type = element.seriesTypeOrNull() ?: continue // Skip unknown series (forward compat)
                // IOB was a fixed graph then; it is put in front as its own graph (see fromJson)
                if (type == SeriesType.IOB) continue
                if (type !in series) series.add(type)
            }
            return series.take(2)
        }

        fun fromJson(json: String): GraphConfig {
            val obj = Json.parseToJsonElement(json) as JsonObject
            val bgOverlays = overlaysFromJson(obj.array(KEY_BG_OVERLAYS), listOf(SeriesType.ACTIVITY, SeriesType.PREDICTIONS))
            val bgHeight = obj.height(KEY_BG_HEIGHT)
            val graphs = mutableListOf<SecondaryGraph>()
            val version = (obj[KEY_VERSION] as? JsonPrimitive)?.let { runCatching { it.int }.getOrNull() } ?: 1
            if (version < 2) {
                // The fixed IOB graph of before becomes the first graph, as it was
                val iobOverlays = overlaysFromJson(obj.array(KEY_IOB_OVERLAYS), listOf(SeriesType.ACTIVITY))
                graphs.add(GraphConfig.DEFAULT_IOB_GRAPH.copy(height = obj.height(KEY_IOB_HEIGHT), activityOverlay = SeriesType.ACTIVITY in iobOverlays))
            }
            for (raw in obj.array(KEY_SECONDARY_GRAPHS).orEmpty()) {
                // Legacy format: element is an array of series names.
                // New format: element is an object { series: [...], height: Int }.
                // Newest format: the object also has "traces" with options per trace.
                val (traces, height) = when (raw) {
                    is JsonArray  -> parseSeriesArray(raw).map { GraphTrace(it) } to GraphConfig.DEFAULT_GRAPH_HEIGHT_DP
                    is JsonObject -> {
                        val stored = raw.array(KEY_TRACES)?.mapNotNull { traceFromJson(it) }?.let { cleanTraces(it) }.orEmpty()
                        stored.ifEmpty { raw.array(KEY_SERIES)?.let { parseSeriesArray(it) }.orEmpty().map { GraphTrace(it) } } to raw.height(KEY_HEIGHT)
                    }

                    else          -> emptyList<GraphTrace>() to GraphConfig.DEFAULT_GRAPH_HEIGHT_DP
                }
                if (traces.isEmpty()) continue
                val options = raw as? JsonObject
                graphs.add(
                    SecondaryGraph(
                        traces, height,
                        leftRange = rangeFromJson(options?.get(KEY_LEFT_RANGE)),
                        rightRange = rangeFromJson(options?.get(KEY_RIGHT_RANGE)),
                        activityOverlay = (options?.get(KEY_ACTIVITY_OVERLAY) as? JsonPrimitive)?.let { runCatching { it.boolean }.getOrNull() } ?: false
                    )
                )
            }
            return GraphConfig(
                bgOverlays = bgOverlays,
                bgHeight = bgHeight,
                bgTraces = obj.array(KEY_BG_TRACES)?.mapNotNull { traceFromJson(it) }?.let { cleanBgTraces(it) }.orEmpty(),
                bgLeftRange = rangeFromJson(obj[KEY_BG_LEFT_RANGE]),
                bgRightRange = rangeFromJson(obj[KEY_BG_RIGHT_RANGE]),
                secondaryGraphs = graphs.take(GraphConfig.MAX_SECONDARY_GRAPHS)
            )
        }

        /** The named string, or null when it is absent or is not a string. */
        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** The named array, or null when it is absent or is something other than an array. */
        private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

        /** A stored height, defaulted when absent and always brought inside the allowed range. */
        private fun JsonObject.height(key: String): Int =
            ((this[key] as? JsonPrimitive)?.let { runCatching { it.int }.getOrNull() } ?: GraphConfig.DEFAULT_GRAPH_HEIGHT_DP)
                .coerceIn(GraphConfig.DEFAULT_GRAPH_HEIGHT_DP, GraphConfig.MAX_GRAPH_HEIGHT_DP)

        /** The series this element names, or null if it is not a name this version knows. */
        private fun JsonElement.seriesTypeOrNull(): SeriesType? =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.let { name -> SeriesType.entries.firstOrNull { it.name == name } }
    }
}
