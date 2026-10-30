package pl.investor.scoring

import pl.investor.extraction.OutlookTone

data class ScoringInput(
    val revenueYoyPct: Double?,
    val previousYoyPcts: List<Double>,
    val sur: Double?,
    val operatingProfitYoyPct: Double?,
    val marginChangePp: Double?,
    val revenueOnly: Boolean,
    val consecutiveStrongBefore: Int,
    val outlook: OutlookTone?,
    val pe: Double?,
    val ttmNetProfitGrowthPct: Double?,
    val ps: Double?,
    val psMedian20q: Double?,
    val cyclical: Boolean,
    val oneOff: Boolean,
    val priceChange10Pct: Double?,
    val indexChange10Pct: Double?,
    val lowLiquidity: Boolean,
    val aiUsed: Boolean,
    val uncertainNumbers: Boolean,
)
