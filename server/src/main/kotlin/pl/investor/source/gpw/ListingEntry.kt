package pl.investor.source.gpw

import pl.investor.report.Market
import pl.investor.report.ReportKind
import java.time.Instant

data class ListingEntry(
    val market: Market,
    val companyName: String,
    val isin: String?,
    val kind: ReportKind,
    val number: String,
    val sourceRef: String,
    val title: String?,
    val publishedAt: Instant,
    val detailUrl: String,
)
