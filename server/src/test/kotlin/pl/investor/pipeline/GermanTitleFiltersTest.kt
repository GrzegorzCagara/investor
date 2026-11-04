package pl.investor.pipeline

import org.junit.jupiter.api.Test
import pl.investor.report.Market
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GermanTitleFiltersTest {

    private val resultsTitles = listOf(
        "Masterflex increases revenue and achieves record earnings in the first nine months of 2026 – outlook for the 2026 financial year reaffirmed",
        "Masterflex steigert Umsatz und erreicht Rekordergebnis in den ersten neun Monaten 2026",
        "Continental Raises Outlook After Good Third Quarter",
        "Continental erhöht Ausblick nach gutem dritten Quartal",
        "ZEAL increases nine-month revenues by 22% and successfully diversifies into new markets",
        "ZEAL steigert Neunmonatsumsatz um 22 %",
        "Scout24 sustains revenue momentum in Q3 and accelerates earnings growth",
        "AUTO1 Group reports record Q3 2026 profitability with adjusted EBITDA up 46%",
        "Vonovia 9M Results: Robust Operating Business in a Challenging Macro Environment",
        "Siltronic slightly raises sales guidance for 2026",
        "9-month result for 2026: Basler reports significant growth in sales and earnings; 2026 forecast raised",
        "Basler 9-Monatsergebnis 2026",
        "Fresenius Q3/2026",
        "Schaeffler with solid nine-months results",
        "Q3 2026: TeamViewer sees strong new business momentum",
        "TeamViewer publishes Q3 2026 results and adjusts guidance",
        "Uniper SE: Raises 2026 Adjusted Net Income outlook",
        "Uniper SE: Erhöht Prognose für das Adjusted Net Income 2026",
        "POB AG bestätigt EBITDA-Prognose und passt Umsatzprognose für das Geschäftsjahr 2026 an",
        "FRIWO AG: Vorläufige Zahlen für das dritte Quartal",
    )

    @Test
    fun `German and English results titles of EQS releases match for DE`() {
        resultsTitles.forEach { assertTrue(ResultsKeywordFilter.matches(it, Market.DE), it) }
    }

    @Test
    fun `German keywords do not change routing of Polish and Swedish titles`() {
        assertFalse(ResultsKeywordFilter.matches("X höjer prognosen för helåret", Market.SE))
        assertFalse(ResultsKeywordFilter.matches("Continental Raises Outlook After Good Third Quarter", Market.SE))
        assertFalse(ResultsKeywordFilter.matches("Fresenius Q3/2026", Market.GPW))
    }

    @Test
    fun `Corporate results releases exclude announcements of results`() {
        assertTrue(ResultsKeywordFilter.isResultsRelease("Masterflex increases revenue and achieves record earnings in the first nine months of 2026"))
        assertTrue(ResultsKeywordFilter.isResultsRelease("Continental Raises Outlook After Good Third Quarter"))
        listOf(
            "AUTODOC to publish 9M 2026 report and host a conference call on Q3 results on 12 November 2026",
            "Pentixapharm Announces Progress of Pivotal Phase 3 PANDA Study; Will Host Third Quarter Webcast on November 12",
            "Einladung zur Telefonkonferenz zum Quartalsergebnis",
            "Save the date: Q3 2026 earnings call",
            "Veröffentlichung der Zahlen zum dritten Quartal am 12. November",
            "Termin für die Q3-Zahlen",
            "OHB awarded EUR 554 million contract for new Copernicus satellite",
        ).forEach { assertFalse(ResultsKeywordFilter.isResultsRelease(it), it) }
    }

    @Test
    fun `German routine Ad-hoc titles are filtered only for DE`() {
        listOf(
            "Directors' Dealings: Kauf von Aktien",
            "Stimmrechtsmitteilung nach § 33 WpHG",
            "Einberufung der ordentlichen Hauptversammlung 2026",
            "Veröffentlichung von Finanzberichten",
        ).forEach { assertTrue(RoutineNewsFilter.isRoutine(it, Market.DE), it) }
        assertFalse(RoutineNewsFilter.isRoutine("fox e-mobility AG: Convocation AGM 2026 with proposed resolutions for capital increases", Market.DE))
        assertFalse(RoutineNewsFilter.isRoutine("FamiCord AG: beschließt Bezugspreis und Anzahl neuer Aktien", Market.DE))
        assertFalse(RoutineNewsFilter.isRoutine("Stimmrechtsmitteilung nach § 33 WpHG", Market.GPW))
    }

    @Test
    fun `material German general meetings and corporate actions are never routine`() {
        assertTrue(RoutineNewsFilter.isRoutine("Abstimmungsergebnisse der ordentlichen Hauptversammlung 2026", Market.DE))
        listOf(
            "Einberufung einer außerordentlichen Hauptversammlung nach § 92 AktG",
            "Verlust der Hälfte des Grundkapitals",
            "Einberufung der Hauptversammlung zur Beschlussfassung über die Verschmelzung",
            "Einladung zur Hauptversammlung: Squeeze-out der Minderheitsaktionäre",
            "Directors' Dealings im Rahmen des Aktienrückkaufs",
            "Stimmrechtsmitteilung im Zusammenhang mit dem Übernahmeangebot",
            "Einberufung der ordentlichen Hauptversammlung mit Beschluss über einen Beherrschungsvertrag",
            "Antrag auf Eröffnung eines Insolvenzverfahrens",
            "Abspaltung des Geschäftsbereichs",
            "Delisting der Aktien",
        ).forEach { assertFalse(RoutineNewsFilter.isRoutine(it, Market.DE), it) }
    }
}
