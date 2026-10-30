package pl.investor.source.quotes

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.WorkbookFactory
import pl.investor.source.biznesradar.PolishNumbers
import java.io.ByteArrayInputStream
import java.math.BigDecimal

internal object Spreadsheets {

    private val OLE_MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B)

    fun isWorkbook(bytes: ByteArray): Boolean = bytes.startsWith(OLE_MAGIC) || bytes.startsWith(ZIP_MAGIC)

    fun open(bytes: ByteArray): Workbook = WorkbookFactory.create(ByteArrayInputStream(bytes))

    private fun ByteArray.startsWith(prefix: ByteArray) =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}

internal fun Cell?.decimalOrNull(): BigDecimal? = when (this?.cellType) {
    CellType.NUMERIC -> BigDecimal.valueOf(numericCellValue)
    CellType.STRING -> PolishNumbers.parse(stringCellValue)
    else -> null
}

internal fun Cell?.textOrEmpty(): String = when (this?.cellType) {
    CellType.STRING -> stringCellValue.trim()
    CellType.NUMERIC -> BigDecimal.valueOf(numericCellValue).stripTrailingZeros().toPlainString()
    else -> ""
}

internal fun Row.cellAt(column: Int): Cell? = getCell(column)

internal fun BigDecimal.thousandsToUnits(): BigDecimal = movePointRight(3)
