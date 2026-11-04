package pl.investor.source.eqs

import pl.investor.report.Market
import pl.investor.source.gpw.ListingEntry

object EqsCategories {
    const val AD_HOC = "Ad-hoc"
    const val CORPORATE = "Corporate"
    val COVERED = setOf(AD_HOC, CORPORATE)
    private const val GERMAN_ISIN_PREFIX = "DE"

    fun covers(isin: String, category: String): Boolean = isin.startsWith(GERMAN_ISIN_PREFIX) && category in COVERED
}

fun ListingEntry.isEqsCorporateNews(): Boolean = market == Market.DE && number == EqsCategories.CORPORATE
