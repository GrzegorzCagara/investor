package pl.investor.api

import pl.investor.extraction.AiPool
import pl.investor.monitoring.SourceHealth
import pl.investor.report.EventType
import pl.investor.report.Market
import pl.investor.report.ReportKind
import pl.investor.report.ReportStatus
import pl.investor.source.Source
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class AlertItem(
    val id: Long,
    val companyName: String,
    val market: Market,
    val eventType: EventType,
    val publishedAt: Instant,
    val score: Int?,
    val belowThreshold: Boolean,
    val summary: String,
    val status: ReportStatus,
)

data class AlertsResponse(val items: List<AlertItem>)

data class ComponentView(val label: String, val points: Double, val max: Double)

data class RevenuePoint(val label: String, val value: Double, val currency: String)

data class AlertDetail(
    val id: Long,
    val companyName: String,
    val market: Market,
    val reportNumber: String,
    val reportKind: ReportKind,
    val publishedAt: Instant,
    val score: Int?,
    val components: List<ComponentView>,
    val penalty: Double,
    val warnings: List<String>,
    val reason: String,
    val outlookQuote: String?,
    val revenueHistory: List<RevenuePoint>,
    val sourceUrl: String,
    val reaction: ReactionView?,
    val consensus: List<ConsensusRowView>,
    val companyHistory: List<OutcomeItem>,
)

enum class Reaction { NEGATIVE, FLAT, POSITIVE }

data class ReactionView(
    val stockPct: Double,
    val indexPct: Double?,
    val indexName: String?,
    val volumeMultiple: Double?,
    val category: Reaction,
)

data class ConsensusRowView(val label: String, val actual: BigDecimal, val consensus: BigDecimal, val differencePct: Double)

data class OutcomeItem(
    val id: Long,
    val companyName: String,
    val market: Market,
    val eventType: EventType,
    val publishedAt: Instant,
    val score: Int,
    val reactionPct: Double?,
    val reactionIndexPct: Double?,
    val excess5Pct: Double?,
    val excess20Pct: Double?,
    val excess60Pct: Double?,
)

data class RankingResponse(val items: List<AlertItem>)

data class HorizonStats(val sessions: Int, val count: Int, val avgExcessPct: Double?)

data class ScoreBucketView(val eventType: EventType, val label: String, val alerts: Int, val horizons: List<HorizonStats>)

data class ReactionBucketView(val reaction: Reaction, val alerts: Int, val horizons: List<HorizonStats>)

data class EffectivenessResponse(
    val buckets: List<ScoreBucketView>,
    val byReaction: List<ReactionBucketView>,
    val items: List<OutcomeItem>,
)

data class DeviceRequest(val token: String)

data class SourceHealthView(
    val source: Source,
    val lastSuccessAt: Instant?,
    val lastFailureAt: Instant?,
    val lastError: String?,
    val outageNotified: Boolean,
)

fun SourceHealth.toView() = SourceHealthView(source, lastSuccessAt, lastFailureAt, lastError, outageNotified)

data class StatusResponse(
    val sources: List<SourceHealthView>,
    val aiSpentUsd: Map<AiPool, BigDecimal>,
    val aiLimitUsd: Map<AiPool, BigDecimal>,
    val medianLatencySeconds7d: Long?,
    val fcmConfigured: Boolean,
    val latestQuoteDate: LocalDate?,
)

data class QuietHoursView(val enabled: Boolean, val from: String, val to: String)

data class SettingsView(
    val pushThreshold: Int,
    val disabledMarkets: List<Market>,
    val disabledEventTypes: List<EventType>,
    val quietHours: QuietHoursView,
)

data class QuietHoursRequest(val enabled: Boolean?, val from: String?, val to: String?)

data class SettingsRequest(
    val pushThreshold: Double?,
    val disabledMarkets: List<Market?>?,
    val disabledEventTypes: List<EventType?>?,
    val quietHours: QuietHoursRequest?,
)
