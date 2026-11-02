package pl.investor.pipeline

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResultsKeywordFilterTest {

    @Test
    fun `monthly sales title matches regardless of case and diacritics`() {
        assertTrue(ResultsKeywordFilter.matches("Informacja o szacunkowych miesięcznych skonsolidowanych przychodach za sierpień 2026 r."))
        assertTrue(ResultsKeywordFilter.matches("SPRZEDAŻ w III kwartale"))
        assertTrue(ResultsKeywordFilter.matches("Wstępne wybrane dane finansowe za 1 kwartał 2026 r."))
        assertTrue(ResultsKeywordFilter.matches("Prognoza EBITDA"))
        assertTrue(ResultsKeywordFilter.matches("Wstępne wyniki finansowe"))
    }

    @Test
    fun `unrelated titles do not match`() {
        assertFalse(ResultsKeywordFilter.matches("Zwołanie Nadzwyczajnego Walnego Zgromadzenia"))
        assertFalse(ResultsKeywordFilter.matches("Powołanie nowego dyrektora niewykonawczego"))
    }

    @Test
    fun `English results titles match`() {
        listOf(
            "INTERIM CONDENSED INDIVIDUAL REPORT_ 30-09-2026",
            "Preliminary sales revenue data for August 2026",
            "Consolidated financial statements for the first half-year of 2026",
            "Half year report 2026",
            "Quarterly report for Q3 2026",
            "Annual report 2025",
            "Preliminary results for Q3 2026",
            "Net profit for the nine months of 2026",
            "Earnings release",
            "Financial report for the third quarter",
        ).forEach { assertTrue(ResultsKeywordFilter.matches(it), it) }
    }

    @Test
    fun `English titles without results keywords do not match`() {
        listOf(
            "Notification under Art. 148 b of the POSA",
            "Sale of shares by a related person",
            "Notification for sale of treasury shares",
            "Eurohold Bulgaria launched a procedure to list its shares on the Frankfurt Stock Exchange",
            "Extraordinary Shareholders' Meeting",
            "Acquisition of treasury shares",
        ).forEach { assertFalse(ResultsKeywordFilter.matches(it), it) }
    }

    @Test
    fun `correction is recognised by its leading word`() {
        assertTrue(ResultsKeywordFilter.isCorrection("Korekta raportu bieżącego nr 7/2026"))
        assertTrue(ResultsKeywordFilter.isCorrection("  KOREKTA raportu"))
        assertTrue(ResultsKeywordFilter.isCorrection("Correction of current report no 12/2026"))
        assertFalse(ResultsKeywordFilter.isCorrection("Raport z korektą"))
        assertFalse(ResultsKeywordFilter.isCorrection("Report correction"))
    }
}
