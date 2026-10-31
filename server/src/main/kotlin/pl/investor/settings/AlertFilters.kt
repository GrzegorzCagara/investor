package pl.investor.settings

import org.springframework.stereotype.Component
import pl.investor.report.EventType
import pl.investor.report.Market

@Component
class AlertFilters(private val settings: SettingRepository) {

    fun disabledMarkets(): List<Market> = read(MARKETS_KEY, Market.entries)

    fun disabledEventTypes(): List<EventType> = read(EVENT_TYPES_KEY, EventType.entries)

    fun allows(market: Market, eventType: EventType): Boolean =
        market !in disabledMarkets() && eventType !in disabledEventTypes()

    fun update(markets: Collection<Market>, eventTypes: Collection<EventType>) {
        settings.put(MARKETS_KEY, markets.toStored())
        settings.put(EVENT_TYPES_KEY, eventTypes.toStored())
    }

    private fun <E : Enum<E>> read(key: String, entries: List<E>): List<E> {
        val names = settings.find(key).orEmpty().split(SEPARATOR).map(String::trim).toSet()
        return entries.filter { it.name in names }
    }

    private fun <E : Enum<E>> Collection<E>.toStored(): String = toSortedSet().joinToString(SEPARATOR) { it.name }

    private companion object {
        const val MARKETS_KEY = "DISABLED_MARKETS"
        const val EVENT_TYPES_KEY = "DISABLED_EVENT_TYPES"
        const val SEPARATOR = ","
    }
}
