package pl.investor.history

import pl.investor.report.Market

const val SESSION_CALENDAR = "WIG"
const val SWEDISH_INDEX = "OMXSPI"
const val GERMAN_INDEX = "DAX"
const val DUTCH_INDEX = "AEX"
const val FINNISH_INDEX = "OMXH25"

fun Market.indexInstrument(): String? = when (this) {
    Market.GPW -> "WIG"
    Market.NC -> "NCINDEX"
    Market.SE -> SWEDISH_INDEX
    Market.DE -> GERMAN_INDEX
    Market.NL -> DUTCH_INDEX
    Market.FI -> FINNISH_INDEX
    Market.US -> null
}

fun Market.sessionCalendar(): String = when (this) {
    Market.GPW, Market.NC, Market.US -> SESSION_CALENDAR
    Market.SE -> SWEDISH_INDEX
    Market.DE -> GERMAN_INDEX
    Market.NL -> DUTCH_INDEX
    Market.FI -> FINNISH_INDEX
}
