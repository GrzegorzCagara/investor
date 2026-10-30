package pl.investor.source.biznesradar

import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.YearMonth

internal class QuarterTable private constructor(
    val quarterEnds: List<LocalDate>,
    private val rows: Map<String, List<String>>,
) {

    fun text(field: String, column: Int): String? =
        rows[field]?.getOrNull(column)?.takeIf { it.isNotBlank() }

    fun number(field: String, column: Int) = text(field, column)?.let(PolishNumbers::parse)

    fun date(field: String, column: Int) =
        text(field, column)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    companion object {
        private val QUARTER_HEADER = Regex("(\\d{4})/Q([1-4])")

        fun parse(html: String): QuarterTable {
            val table = Jsoup.parse(html).selectFirst("table.report-table")
                ?: error("BiznesRadar report table not found")
            val quarterEnds = table.select("th.thq").map { it.ownText().trim().toQuarterEnd() }
            val rows = table.select("tr[data-field]").associate { row ->
                row.attr("data-field") to row.select("td.h").take(quarterEnds.size).map { cell ->
                    cell.selectFirst("span.value")?.text().orEmpty()
                }
            }
            return QuarterTable(quarterEnds, rows)
        }

        private fun String.toQuarterEnd(): LocalDate {
            val match = QUARTER_HEADER.matchEntire(this) ?: error("Unexpected quarter header: $this")
            val (year, quarter) = match.destructured
            return YearMonth.of(year.toInt(), quarter.toInt() * 3).atEndOfMonth()
        }
    }
}
