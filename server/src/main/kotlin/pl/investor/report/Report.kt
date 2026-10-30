package pl.investor.report

import java.time.Instant

data class Report(
    val id: Long?,
    val companyId: Long,
    val market: Market,
    val kind: ReportKind,
    val number: String,
    val sourceRef: String,
    val title: String?,
    val publishedAt: Instant,
    val detectedAt: Instant,
    val detailUrl: String,
    val status: ReportStatus,
    val lastAttemptAt: Instant? = null,
    val statusChangedAt: Instant? = null,
)
