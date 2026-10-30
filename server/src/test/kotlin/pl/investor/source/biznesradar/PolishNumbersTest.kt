package pl.investor.source.biznesradar

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PolishNumbersTest {

    @Test
    fun `polish numbers`() {
        assertEquals(BigDecimal("1052.3"), PolishNumbers.parse("1 052,3"))
        assertEquals(BigDecimal("604100"), PolishNumbers.parseAmountInThousands("604,1 mln USD"))
        assertEquals(BigDecimal("1052000"), PolishNumbers.parseAmountInThousands("ok. 1,052 mld USD"))
        assertNull(PolishNumbers.parse("—"))
    }

    @Test
    fun `english formatted numbers use comma as thousands separator`() {
        assertEquals(BigDecimal("2965176"), PolishNumbers.parse("2,965,176"))
        assertEquals(BigDecimal("1696.7"), PolishNumbers.parse("1,696.7"))
        assertEquals(BigDecimal("1696700"), PolishNumbers.parseAmountInThousands("USD 1,696.7 million"))
        assertEquals(BigDecimal("2965.176"), PolishNumbers.parseAmountInThousands("2,965,176"))
        assertEquals(BigDecimal("1200000"), PolishNumbers.parseAmountInThousands("1,2 billion"))
    }

    @Test
    fun `single comma without a dot stays a polish decimal`() {
        assertEquals(BigDecimal("604.1"), PolishNumbers.parse("604,1"))
        assertEquals(BigDecimal("1.696"), PolishNumbers.parse("1,696"))
    }

    @Test
    fun `non breaking spaces and negatives are understood`() {
        assertEquals(BigDecimal("-4.5"), PolishNumbers.parse("-4,5"))
        assertEquals(BigDecimal("12.00"), PolishNumbers.parse("12,00"))
        assertEquals(BigDecimal("1052.3"), PolishNumbers.parse("1 052,3"))
    }

    @Test
    fun `blank and non numeric text has no value`() {
        assertNull(PolishNumbers.parse(""))
        assertNull(PolishNumbers.parse("   "))
        assertNull(PolishNumbers.parse("n/d"))
        assertNull(PolishNumbers.parseAmountInThousands("brak danych"))
    }

    @Test
    fun `thousands unit keeps the value as is`() {
        assertEquals(BigDecimal("1052.3"), PolishNumbers.parseAmountInThousands("1 052,3 tys. zł"))
    }
}
