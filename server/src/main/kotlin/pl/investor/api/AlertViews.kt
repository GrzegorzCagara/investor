package pl.investor.api

import pl.investor.extraction.AiPool
import pl.investor.monitoring.SourceHealth
import pl.investor.report.Market
import pl.investor.report.ReportKind
import pl.investor.report.ReportStatus
import pl.investor.source.Source
import java.math.BigDecimal
import java.time.Instant

enum class EventType { RESULTS }

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
    val medianLatencySeconds7d: Long?,
    val fcmConfigured: Boolean,
)
