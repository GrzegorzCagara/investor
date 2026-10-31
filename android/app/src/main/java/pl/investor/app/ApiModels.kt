package pl.investor.app

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
}

enum class Market { GPW, NC, US }

enum class ReportKind { CURRENT, QUARTERLY, HALF_YEARLY, ANNUAL, OTHER }

enum class ReportStatus {
    NEW,
    IRRELEVANT,
    CORRECTION_SKIPPED,
    EXTRACTED,
    SCORED,
    UNSCORED_NO_AI,
    UNSCORED_NO_DATA,
    FAILED,
    PENDING_ATTACHMENTS,
    PENDING_DATA,
    PENDING_AI,
    PUSH_PENDING,
    BACKFILLED,
}

enum class EventType { RESULTS }

enum class Reaction { NEGATIVE, FLAT, POSITIVE }

@Serializable
data class AlertItem(
    val id: Long,
    val companyName: String,
    val market: Market,
    val eventType: EventType,
    @Serializable(with = InstantSerializer::class) val publishedAt: Instant,
    val score: Int? = null,
    val belowThreshold: Boolean,
    val summary: String,
    val status: ReportStatus,
)

@Serializable
data class AlertsResponse(val items: List<AlertItem>)

@Serializable
data class ComponentView(val label: String, val points: Double, val max: Double)

@Serializable
data class RevenuePoint(val label: String, val value: Double, val currency: String)

@Serializable
data class AlertDetail(
    val id: Long,
    val companyName: String,
    val market: Market,
    val reportNumber: String,
    val reportKind: ReportKind,
    @Serializable(with = InstantSerializer::class) val publishedAt: Instant,
    val score: Int? = null,
    val components: List<ComponentView>,
    val penalty: Double,
    val warnings: List<String>,
    val reason: String,
    val outlookQuote: String? = null,
    val revenueHistory: List<RevenuePoint>,
    val sourceUrl: String,
    val reaction: ReactionView? = null,
    val consensus: List<ConsensusRowView> = emptyList(),
    val companyHistory: List<OutcomeItem> = emptyList(),
)

@Serializable
data class ReactionView(
    val stockPct: Double,
    val indexPct: Double? = null,
    val indexName: String? = null,
    val volumeMultiple: Double? = null,
    val category: Reaction,
)

@Serializable
data class ConsensusRowView(val label: String, val actual: Double, val consensus: Double, val differencePct: Double)

@Serializable
data class OutcomeItem(
    val id: Long,
    val companyName: String,
    val market: Market,
    val eventType: EventType,
    @Serializable(with = InstantSerializer::class) val publishedAt: Instant,
    val score: Int,
    val reactionPct: Double? = null,
    val reactionIndexPct: Double? = null,
    val excess5Pct: Double? = null,
    val excess20Pct: Double? = null,
    val excess60Pct: Double? = null,
)

@Serializable
data class RankingResponse(val items: List<AlertItem> = emptyList())

@Serializable
data class HorizonStats(val sessions: Int, val count: Int, val avgExcessPct: Double? = null)

@Serializable
data class ScoreBucketView(val eventType: EventType, val label: String, val alerts: Int, val horizons: List<HorizonStats> = emptyList())

@Serializable
data class ReactionBucketView(val reaction: Reaction, val alerts: Int, val horizons: List<HorizonStats> = emptyList())

@Serializable
data class EffectivenessResponse(
    val buckets: List<ScoreBucketView> = emptyList(),
    val byReaction: List<ReactionBucketView> = emptyList(),
    val items: List<OutcomeItem> = emptyList(),
)

@Serializable
data class SourceHealthView(
    val source: String,
    @Serializable(with = InstantSerializer::class) val lastSuccessAt: Instant? = null,
    @Serializable(with = InstantSerializer::class) val lastFailureAt: Instant? = null,
    val lastError: String? = null,
    val outageNotified: Boolean = false,
)

@Serializable
data class StatusResponse(
    val sources: List<SourceHealthView> = emptyList(),
    val aiSpentUsd: Map<String, Double> = emptyMap(),
    val aiLimitUsd: Map<String, Double> = emptyMap(),
    val medianLatencySeconds7d: Long? = null,
    val fcmConfigured: Boolean = false,
    val latestQuoteDate: String? = null,
)

@Serializable
data class QuietHoursView(val enabled: Boolean, val from: String, val to: String)

@Serializable
data class SettingsView(
    val pushThreshold: Int,
    val disabledMarkets: List<Market> = emptyList(),
    val disabledEventTypes: List<EventType> = emptyList(),
    val quietHours: QuietHoursView,
)

@Serializable
data class DeviceRequest(val token: String)
