package pl.investor.source.quotes

import org.springframework.stereotype.Component
import pl.investor.history.DailyQuote
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Component
class GpwArchiveClient(
    private val http: PoliteHttpClient,
    private val parser: GpwArchiveParser,
) {

    fun day(date: LocalDate): List<DailyQuote> =
        http.getBytes(Source.GPW_ARCHIVE, archiveUrl(STOCKS_TYPE, date))
            .takeIf(Spreadsheets::isWorkbook)
            ?.let { parser.parse(it, date) }
            ?: emptyList()

    private companion object {
        const val STOCKS_TYPE = 10
    }
}

internal val ARCHIVE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")

internal fun archiveUrl(type: Int, date: LocalDate) =
    "https://www.gpw.pl/archiwum-notowan?fetch=1&type=$type&instrument=&date=${date.format(ARCHIVE_DATE)}"
