package pl.investor.history

import pl.investor.report.Market

const val SESSION_CALENDAR = "WIG"

fun Market.indexInstrument(): String? = when (this) {
    Market.GPW -> "WIG"
    Market.NC -> "NCINDEX"
    Market.US -> null
}
