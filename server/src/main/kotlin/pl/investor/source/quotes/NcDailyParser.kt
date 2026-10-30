package pl.investor.source.quotes

import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.springframework.stereotype.Component
import pl.investor.history.DailyQuote
import java.time.LocalDate

@Component
class NcDailyParser {

    fun parse(workbook: ByteArray, date: LocalDate): List<DailyQuote> =
        Spreadsheets.open(workbook).use { book ->
            val sheet = book.getSheet(SHEET) ?: error("NewConnect daily file has no sheet '$SHEET'")
            val layout = layout(sheet)
            sheet.filter { it.rowNum >= layout.firstDataRow }.mapNotNull { layout.read(it, date) }
        }

    private fun layout(sheet: Sheet): Layout {
        val isinColumn = sheet.firstHeaderCell("kod isin")
        val firstDataRow = sheet.first { row -> row.cellAt(isinColumn).textOrEmpty().matches(ISIN) }.rowNum
        val headers = (0 until sheet.getRow(firstDataRow).lastCellNum).associateWith { column ->
            (sheet.firstRowNum until firstDataRow)
                .joinToString(" ") { sheet.getRow(it)?.cellAt(column).textOrEmpty() }
                .lowercase()
                .replace(WHITESPACE, " ")
        }
        fun column(header: String) =
            headers.entries.firstOrNull { it.value.contains(header) }?.key
                ?: error("Missing column '$header' in NewConnect daily file")
        return Layout(
            firstDataRow = firstDataRow,
            isin = isinColumn,
            close = column("kurs zamknięcia"),
            volume = column("wolumen"),
            turnover = column("wartość obrotu"),
        )
    }

    private fun Sheet.firstHeaderCell(text: String): Int =
        asSequence()
            .take(HEADER_SCAN_ROWS)
            .flatMap { row -> row.map { it.columnIndex to it.textOrEmpty().lowercase() } }
            .firstOrNull { (_, value) -> value == text }
            ?.first
            ?: error("Missing '$text' header in NewConnect daily file")

    private class Layout(val firstDataRow: Int, val isin: Int, val close: Int, val volume: Int, val turnover: Int) {

        fun read(row: Row, date: LocalDate): DailyQuote? {
            val isin = row.cellAt(isin).textOrEmpty().takeIf { it.matches(ISIN) } ?: return null
            val close = row.cellAt(close).decimalOrNull() ?: return null
            return DailyQuote(
                instrument = isin,
                date = date,
                close = close,
                volume = row.cellAt(volume).decimalOrNull()?.toLong() ?: 0L,
                turnover = row.cellAt(turnover).decimalOrNull(),
            )
        }
    }

    private companion object {
        const val SHEET = "akcje"
        const val HEADER_SCAN_ROWS = 15
        val ISIN = Regex("[A-Z]{2}[A-Z0-9]{9}[0-9]")
        val WHITESPACE = Regex("\\s+")
    }
}
