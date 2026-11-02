package pl.investor.history

import pl.investor.report.Market

const val SESSION_CALENDAR = "WIG"
const val SWEDISH_INDEX = "OMXSPI"

fun Market.indexInstrument(): String? = when (this) {
    Market.GPW -> "WIG"
    Market.NC -> "NCINDEX"
    Market.SE -> SWEDISH_INDEX
    Market.US -> null
}

fun Market.sessionCalendar(): String = when (this) {
    Market.GPW, Market.NC, Market.US -> SESSION_CALENDAR
    Market.SE -> SWEDISH_INDEX
}
