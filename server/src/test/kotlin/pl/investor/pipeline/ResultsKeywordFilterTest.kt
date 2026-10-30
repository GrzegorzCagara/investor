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
    fun `correction is recognised by its leading word`() {
        assertTrue(ResultsKeywordFilter.isCorrection("Korekta raportu bieżącego nr 7/2026"))
        assertTrue(ResultsKeywordFilter.isCorrection("  KOREKTA raportu"))
        assertFalse(ResultsKeywordFilter.isCorrection("Raport z korektą"))
    }
}
