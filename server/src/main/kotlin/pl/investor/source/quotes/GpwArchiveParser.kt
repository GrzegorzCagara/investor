package pl.investor.source.quotes

import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.springframework.stereotype.Component
import pl.investor.history.DailyQuote
import java.math.BigDecimal
import java.time.LocalDate

@Component
class GpwArchiveParser {

    fun parse(xls: ByteArray, date: LocalDate): List<DailyQuote> =
        quotes(xls, date) { it.isin }.filter { it.instrument.isNotEmpty() }

    fun parseIndices(xls: ByteArray, date: LocalDate): List<DailyQuote> =
        quotes(xls, date) { it.name }

    fun parseIndex(xls: ByteArray, date: LocalDate, index: String): DailyQuote? =
        quotes(xls, date) { it.name }.singleOrNull { it.instrument == index }

    private fun quotes(xls: ByteArray, date: LocalDate, instrument: (ArchiveRow) -> String): List<DailyQuote> =
        Spreadsheets.open(xls).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            val columns = columns(sheet) ?: return emptyList()
            sheet.drop(1).mapNotNull { row -> columns.read(row)?.toQuote(date, instrument) }
        }

    private fun columns(sheet: Sheet): ArchiveColumns? =
        sheet.getRow(sheet.firstRowNum)?.let { header ->
            val names = (0 until header.lastCellNum).map { header.getCell(it).textOrEmpty() }
            fun index(name: String) = names.indexOf(name).also { check(it >= 0) { "Missing column '$name' in GPW archive" } }
            ArchiveColumns(
                name = index("Nazwa"),
                isin = index("ISIN"),
                close = index("Kurs zamknięcia"),
                volume = index("Wolumen"),
                turnover = index("Obrót"),
            )
        }

    private fun ArchiveRow.toQuote(date: LocalDate, instrument: (ArchiveRow) -> String) =
        DailyQuote(
            instrument = instrument(this),
            date = date,
            close = close,
            volume = volume,
            turnover = turnover,
        )

    private class ArchiveRow(
        val name: String,
        val isin: String,
        val close: BigDecimal,
        val volume: Long,
        val turnover: BigDecimal?,
    )

    private class ArchiveColumns(val name: Int, val isin: Int, val close: Int, val volume: Int, val turnover: Int) {

        fun read(row: Row): ArchiveRow? {
            val close = row.cellAt(close).decimalOrNull() ?: return null
            val name = row.cellAt(name).textOrEmpty().ifEmpty { return null }
            return ArchiveRow(
                name = name,
                isin = row.cellAt(isin).textOrEmpty(),
                close = close,
                volume = row.cellAt(volume).decimalOrNull()?.toLong() ?: 0L,
                turnover = row.cellAt(turnover).decimalOrNull()?.thousandsToUnits(),
            )
        }
    }
}
