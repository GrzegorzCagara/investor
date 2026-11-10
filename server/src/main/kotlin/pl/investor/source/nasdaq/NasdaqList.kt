package pl.investor.source.nasdaq

import pl.investor.report.Market
import pl.investor.support.STOCKHOLM
import java.time.ZoneId
import pl.investor.support.HELSINKI as HELSINKI_ZONE

enum class NasdaqList(val marketName: String, val market: Market, val zone: ZoneId) {
    MAIN_MARKET("Main Market, Stockholm", Market.SE, STOCKHOLM),
    FIRST_NORTH("First North Sweden", Market.SE, STOCKHOLM),
    HELSINKI("Main Market, Helsinki", Market.FI, HELSINKI_ZONE),
}
