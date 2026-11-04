package pl.investor.pipeline

import pl.investor.report.Company

sealed interface AvanzaIdentification {
    data class Identified(val company: Company) : AvanzaIdentification {
        val isin: String get() = checkNotNull(company.isin)
    }

    data object NotListed : AvanzaIdentification

    data object Unavailable : AvanzaIdentification
}
