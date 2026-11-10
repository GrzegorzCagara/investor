package pl.investor.report

enum class Market(val currency: String, val lowLiquidityTurnover: Double?) {
    GPW("PLN", 100_000.0),
    NC("PLN", 100_000.0),
    US("USD", 100_000.0),
    SE("SEK", 300_000.0),
    DE("EUR", lowLiquidityTurnover = null),
    NL("EUR", lowLiquidityTurnover = null),
    FI("EUR", lowLiquidityTurnover = null),
    ;

    val isCoveredByAvanza: Boolean get() = this == SE || this == DE || this == NL || this == FI
}
