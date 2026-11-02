package pl.investor.pipeline

import org.junit.jupiter.api.Test
import pl.investor.report.Market
import pl.investor.report.ReportKind
import pl.investor.source.gpw.EspiListingParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutineNewsFilterTest {

    @Test
    fun `formalities from real listings are routine`() {
        listOf(
            "Informacja o transakcji na akcjach ASBISc Enterprises Plc",
            "Zawiadomienia o transakcjach w trybie art. 19 MAR",
            "Powiadomienie o transkacji osoby pełniącej obowiązki zarządcze",
            "Zbycie akcji emitenta przez osobę blisko związaną z osobą pełniącą obowiązki zarządcze",
            "Notification from \"Kaliman RT\" AD in accordance with Regulation 596/2014 for transactions with shares of \"Sopharma\" AD on 18.09.2026",
            "Notification under Art. 19 MAR",
            "Otrzymanie zawiadomienia na podstawie art. 69 Ustawy o ofercie publicznej",
            "Zawiadomienie o zmianie stanu posiadania głosów na walnym zgromadzeniu ASBISc Enterprises PLC.",
            "Zawiadomienie Agencji Rozwoju Przemysłu S.A. o zmianie udziału w ogólnej liczbie głosów RAFAMET S.A. w restrukturyzacji.",
            "Notification under Art. 148 b of the POSA",
            "Notification of change in voting rights",
            "Zwołanie ZWZ ORLEN S.A. na dzień 09.06.2026 roku",
            "Wykaz akcjonariuszy posiadających co najmniej 5% głosów na Zwyczajnym Walnym Zgromadzeniu Akcjonariuszy ASBISc Enterprises Plc w dniu 06 maja 2026 r.",
            "Uchwały podjęte przez Nadzwyczajne Walne Zgromadzenie Stalexport Autostrady S.A.",
            "Regarding the supplement of the agenda of the Annual General Meeting of Shareholders of AB \"Novaturas\"",
            "Notice of call and Shareholders' meeting documentation",
            "Zmiana terminu przekazania raportu okresowego",
            "Zmiana harmonogramu publikacji raportów okresowych",
            "Terminy publikacji raportów okresowych w 2026 roku oraz informacja o przekazywaniu raportów skonsolidowanych",
            "Rejestracja przez Sąd podwyższenia kapitału zakładowego Spółki oraz rejestracja zmian w Statucie Spółki.",
            "Rezygnacja członka Rady Nadzorczej Emitenta.",
            "Powołanie Rady Nadzorczej VIII kadencji",
            "Wybór firmy audytorskiej do przeprowadzania przeglądu śródrocznych sprawozdań finansowych",
            "Przyjęcie polityki wynagrodzeń członków Zarządu i Rady Nadzorczej",
            "Raport dotyczący stosowania Dobrych Praktyk Spółek Notowanych na GPW 2021",
            "Zawarcie umowy z Animatorem Rynku",
            "Raport miesięczny za wrzesień 2026",
            "Wykaz informacji przekazanych do publicznej wiadomości w 2025 roku",
            "Nabycie akcji własnych",
            "Informacje dotyczące transakcji w ramach nabywania akcji własnych",
            "Banco Santander communicates the transactions over its own shares which it has carried out between 30 July and 5 August 2026 under the buyback programme",
            "Oświadczenie KDPW w sprawie rejestracji akcji serii L w depozycie papierów wartościowych",
            "Informacja o działaniach stabilizujących podejmowanych w dniu 28 lipca 2026 r.",
            "Dopuszczenie i warunkowe wprowadzenie praw do akcji zwykłych na okaziciela serii G Spółki do obrotu giełdowego na głównym rynku GPW",
            "Informacja w zakresie terminu wprowadzenia akcji do obrotu na GPW",
            "Podsumowanie subskrypcji akcji serii O",
            "Notification for sale of treasury shares",
            "Wartość aktywów pod zarządzaniem Skarbiec TFI S.A.",
            "Publikacja raportu za dwa kwartały 2026 roku przez spółkę zależną",
            "Udostępnienie dokumentów sporządzonych na podstawie art. 6a ustawy o ofercie publicznej",
            "Ponowny wybór członków do Rady Dyrektorów ASBISc Enterprises Plc.",
            "Zakończenie kadencji Dyrektorów.",
            "Powołanie nowego dyrektora niewykonawczego",
            "Powołanie Dyrektora Wykonawczego",
        ).forEach { assertTrue(RoutineNewsFilter.isRoutine(it), it) }
    }

    @Test
    fun `material events from real listings reach the AI`() {
        listOf(
            "Eurohold Bulgaria launched a procedure to list its shares on the Frankfurt Stock Exchange",
            "Nabycie sieci sklepów Samsung na terenie Polski",
            "Autoryzowana umowa dystrybucyjna z Apple na sprzedaż produktów marki Apple w Afryce Zachodniej i Północnej",
            "Stanowisko Zarządu Wirtualna Polska Holding S.A. dotyczące wezwania do zapisywania się na sprzedaż akcji Wirtualna Polska Holding S.A.",
            "Skup akcji własnych",
            "Zaproszenie do składania ofert sprzedaży akcji",
            "Rekomendacja Rady Dyrektorów w sprawie dywidendy za 2025 r.",
            "Uchwała Zwyczajnego Walnego Zgromadzenia w sprawie wypłaty dywidendy za 2025 rok",
            "Podwyższenie prognozy wyników finansowych na 2022 r.",
            "Podjęcie przez zarząd emitenta decyzji o podwyższeniu kapitału zakładowego emitenta w granicach kapitału docelowego",
            "Emisja obligacji serii R",
            "Rezygnacja Członka Zarządu Enea S.A.",
            "Zmiany w składzie Rady Nadzorczej i Zarządu Emitenta.",
            "Decyzja KNF w sprawie nałożenia kar pieniężnych na BOŚ S.A.",
            "Zawarcie istotnej umowy na dostawy stacji transformatorowych",
            "Zmiana firmy (nazwy) Spółki na Bumech Defense Partners S.A., rejestracja przez sąd zmiany statutu Spółki",
            "Allegro informuje o zakończeniu Fazy I programu skupu akcji własnych",
            "UniCredit announces a tender offer for one series of Notes",
        ).forEach { assertFalse(RoutineNewsFilter.isRoutine(it), it) }
    }

    @Test
    fun `about half of the non-results titles in the real listings are routine`() {
        val parser = EspiListingParser()
        val fixtures = Path.of("src/test/resources/fixtures")
        val listings = fixtures.resolve("gpw").listDirectoryEntries("*.html").map { it to Market.GPW } +
            fixtures.resolve("nc").listDirectoryEntries("listing-*.html").map { it to Market.NC }
        val titles = listings.flatMap { (file, market) -> parser.parse(Files.readString(file), market).entries }
            .filter { it.kind == ReportKind.CURRENT }
            .mapNotNull { it.title }
            .distinct()
            .filterNot { ResultsKeywordFilter.matches(it) || ResultsKeywordFilter.isCorrection(it) }

        val routineShare = titles.count(RoutineNewsFilter::isRoutine).toDouble() / titles.size

        assertTrue(titles.size > 250, "only ${titles.size} titles")
        assertTrue(routineShare in 0.5..0.7, "routine share $routineShare")
    }

    @Test
    fun `general meeting resolutions on buyback, share issue or merger reach the AI`() {
        listOf(
            "Zwołanie Nadzwyczajnego Walnego Zgromadzenia w sprawie upoważnienia Zarządu do nabywania akcji własnych",
            "Uchwała NWZ w sprawie skupu akcji własnych",
            "Projekty uchwał Nadzwyczajnego Walnego Zgromadzenia w sprawie emisji akcji serii K",
            "Zwołanie Nadzwyczajnego Walnego Zgromadzenia w sprawie połączenia ze spółką zależną",
        ).forEach { assertFalse(RoutineNewsFilter.isRoutine(it), it) }
        assertTrue(RoutineNewsFilter.isRoutine("Zwołanie Nadzwyczajnego Walnego Zgromadzenia na dzień 8 czerwca 2026 roku"))
    }

    @Test
    fun `supervisory board titles that also name the chief executive reach the AI`() {
        assertFalse(RoutineNewsFilter.isRoutine("Powołanie członka Rady Nadzorczej delegowanego do czasowego pełnienia funkcji Prezesa"))
        assertFalse(RoutineNewsFilter.isRoutine("Rezygnacja członka Rady Nadzorczej, nowy CEO spółki"))
        assertTrue(RoutineNewsFilter.isRoutine("Rezygnacja członka rady nadzorczej"))
    }

    @Test
    fun `change of control and acquisition of a stake in another company reach the AI`() {
        assertFalse(RoutineNewsFilter.isRoutine("Zmiana udziałowca większościowego"))
        assertFalse(RoutineNewsFilter.isRoutine("Nabycie znacznego pakietu akcji spółki Mostostal Zabrze S.A."))
        assertTrue(RoutineNewsFilter.isRoutine("Zawiadomienie w sprawie znacznego pakietu akcji."))
    }
}
