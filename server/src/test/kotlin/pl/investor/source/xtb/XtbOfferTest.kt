package pl.investor.source.xtb

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XtbOfferTest {

    private val offer = XtbOffer(
        mapOf(
            XtbCountry.PL to listOf(
                XtbStock("11B", "11 bit studios SA"),
                XtbStock("ZUK", "Zaklady Urzadzen Kotlowych Staporkow SA"),
            ),
            XtbCountry.SE to listOf(XtbStock("VOLVB", "Volvo AB"), XtbStock("SWECB", "Sweco AB (publ)")),
        ),
    )

    @Test
    fun `ticker matches regardless of spaces and case`() {
        assertTrue(offer.lists(XtbCountry.SE, listOf("VOLV B"), "AB Volvo"))
        assertTrue(offer.lists(XtbCountry.PL, listOf("11b"), "Jedenaście"))
    }

    @Test
    fun `company without a matching ticker is found by name without legal form and Polish letters`() {
        assertTrue(offer.lists(XtbCountry.PL, emptyList(), "ZAKŁADY URZĄDZEŃ KOTŁOWYCH STĄPORKÓW SPÓŁKA AKCYJNA"))
        assertTrue(offer.lists(XtbCountry.SE, listOf("SWEC A"), "Sweco AB"))
    }

    @Test
    fun `stock offered in another country does not count`() {
        assertFalse(offer.lists(XtbCountry.SE, listOf("11B"), "11 bit studios"))
        assertFalse(offer.lists(XtbCountry.PL, listOf("VOLVB"), "Volvo"))
    }

    @Test
    fun `unknown company is not listed`() {
        assertFalse(offer.lists(XtbCountry.PL, listOf("ABC"), "Alfa Beta SA"))
        assertFalse(offer.lists(XtbCountry.PL, emptyList(), "S.A."))
    }
}
