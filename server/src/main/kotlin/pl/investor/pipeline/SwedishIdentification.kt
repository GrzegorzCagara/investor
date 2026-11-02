package pl.investor.pipeline

import pl.investor.report.Company

sealed interface SwedishIdentification {
    data class Identified(val company: Company) : SwedishIdentification {
        val isin: String get() = checkNotNull(company.isin)
    }

    data object NotListed : SwedishIdentification

    data object Unavailable : SwedishIdentification
}
