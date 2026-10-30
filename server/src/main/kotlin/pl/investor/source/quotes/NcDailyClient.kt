package pl.investor.source.quotes

import org.springframework.stereotype.Component
import pl.investor.history.DailyQuote
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Component
class NcDailyClient(
    private val http: PoliteHttpClient,
    private val parser: NcDailyParser,
) {

    fun day(date: LocalDate): List<DailyQuote> =
        extensionsFor(date).asSequence()
            .map { http.getBytes(Source.NC_DAILY, url(date, it)) }
            .firstOrNull(Spreadsheets::isWorkbook)
            ?.let { parser.parse(it, date) }
            ?: emptyList()

    private fun extensionsFor(date: LocalDate) =
        if (date >= XLSX_SINCE) listOf("xlsx", "xls") else listOf("xls", "xlsx")

    private fun url(date: LocalDate, extension: String) =
        "https://newconnect.pl/pub/NEWCONNECT/statystyki/statystyki_dzienne/NC_${date.format(FILE_DATE)}.$extension"

    private companion object {
        val XLSX_SINCE: LocalDate = LocalDate.of(2026, 10, 5)
        val FILE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}
