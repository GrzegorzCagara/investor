package pl.investor.source.quotes

import org.springframework.stereotype.Component
import pl.investor.history.DailyQuote
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
import java.time.LocalDate

@Component
class IndexDailyClient(
    private val http: PoliteHttpClient,
    private val parser: GpwArchiveParser,
) {

    @Volatile
    private var lastDay: Pair<LocalDate, List<DailyQuote>>? = null

    fun day(index: String, date: LocalDate): DailyQuote? =
        indices(date).singleOrNull { it.instrument == index }

    private fun indices(date: LocalDate): List<DailyQuote> =
        lastDay?.takeIf { it.first == date }?.second ?: fetch(date).also { if (it.isNotEmpty()) lastDay = date to it }

    private fun fetch(date: LocalDate): List<DailyQuote> =
        http.getBytes(Source.INDEX_DAILY, archiveUrl(INDICES_TYPE, date))
            .takeIf(Spreadsheets::isWorkbook)
            ?.let { parser.parseIndices(it, date) }
            ?: emptyList()

    private companion object {
        const val INDICES_TYPE = 1
    }
}
