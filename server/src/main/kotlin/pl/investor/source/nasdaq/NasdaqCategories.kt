package pl.investor.source.nasdaq

import pl.investor.report.Market
import pl.investor.report.ReportKind
import pl.investor.source.gpw.ListingEntry

object NasdaqCategories {

    private val KINDS = mapOf(
        "interim report (q1 and q3)" to ReportKind.QUARTERLY,
        "quarterly report" to ReportKind.QUARTERLY,
        "half year financial report" to ReportKind.HALF_YEARLY,
        "financial statement release" to ReportKind.ANNUAL,
        "inside information" to ReportKind.CURRENT,
        "tender offer" to ReportKind.CURRENT,
    )

    fun kindOf(category: String): ReportKind = KINDS[category.trim().lowercase()] ?: ReportKind.OTHER
}

fun ListingEntry.isIgnoredNasdaqCategory(): Boolean = market == Market.SE && kind == ReportKind.OTHER
