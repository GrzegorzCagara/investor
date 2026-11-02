package pl.investor.pipeline

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwedishTitleFiltersTest {

    @Test
    fun `Swedish and English formalities are routine`() {
        listOf(
            "Kallelse till extra bolagsstämma i Case Group AB (publ)",
            "Kallelse till årsstämma i Diamyd Medical AB",
            "Notice to attend an extraordinary general meeting in ExpreS2ion Biotech Holding AB (publ)",
            "Kommuniké från extra bolagsstämma i BrightBid Group",
            "Nytt antal aktier och röster i Systemair",
            "Change in the number of shares and votes in Hexatronic",
            "Återköp av aktier av serie B i Essity under vecka 44, 2026",
            "Repurchases of shares in Neobo during week 44, 2026",
            "Buyback of Class B shares in Essity during week 44, 2026",
            "Valberedning inför årsstämman 2027 i Opter AB",
            "Nomination committee appointed for the 2027 AGM",
            "Flaggningsmeddelande avseende aktier i Mips AB",
            "Byte av likviditetsgarant",
            "Inbjudan till presentation av Mips delårsrapport för tredje kvartalet 2026",
            "Invitation to webcast presentation of the Q3 2026 report",
            "Ändrat datum för publicering av bokslutskommuniké",
        ).forEach { assertTrue(RoutineNewsFilter.isRoutine(it), it) }
    }

    @Test
    fun `Swedish inside information with business impact is not routine`() {
        listOf(
            "Ortoma erhåller orders om cirka 173 MSEK avseende 125 OTS™ Hip-system med tillhörande komponenter",
            "Terranor vinner drift- och underhållskontrakt i Göteborg värt 113 miljoner kronor",
            "SenzaGen acquires Alcyomics, a leading UK-based provider of non-animal immunotoxicology solutions for drug development",
            "Klövern säljer fastighet i Huvudsta Centrum",
            "Kallelse till extra bolagsstämma med anledning av riktad emission",
            "Offentligt uppköpserbjudande till aktieägarna i Opter AB",
            "Styrelsen föreslår extra utdelning",
        ).forEach { assertFalse(RoutineNewsFilter.isRoutine(it), it) }
    }

    @Test
    fun `real English GPW titles keep their classification with the shared patterns`() {
        listOf(
            "Number of voting rights and size of share capital at MOL Plc",
            "Banco Santander communicates the transactions over its own shares which it has carried out between 30 July and 5 August 2026 under the buyback programme",
            "Regarding the supplement of the agenda of the Annual General Meeting of Shareholders of AB \"Novaturas\"",
        ).forEach { assertTrue(RoutineNewsFilter.isRoutine(it), it) }
        listOf(
            "Banco Santander share capital reduction following completion of buy-back programme",
            "Completion of the acquisition of O_GD's Hungarian assets",
            "UniCredit announces a tender offer for one series of Notes and the issue of new Euro-denominated Tier 2 Fixed Rate Subordinated Callable Notes",
        ).forEach { assertFalse(RoutineNewsFilter.isRoutine(it), it) }
        assertTrue(ResultsKeywordFilter.matches("Preliminary sales revenue data for August 2026"))
        assertFalse(ResultsKeywordFilter.matches("MOL's reaction to today's press reports regarding the NIS transaction"))
    }

    @Test
    fun `Swedish results titles go to results extraction`() {
        listOf(
            "MacGregors delårsrapport för tredje kvartalet 2026: Rekordhög lönsamhet",
            "Opter kvartalsrapport Q3 2026",
            "Bokslutskommuniké 2026",
            "Bolaget bedömer att rörelseresultatet för tredje kvartalet överstiger marknadens förväntningar",
            "Preliminär nettoomsättning för oktober",
            "MilDef estimates that sales and earnings for the third quarter will significantly exceed",
        ).forEach { assertTrue(ResultsKeywordFilter.matches(it), it) }
        listOf(
            "Positiva borrresultat i Vargbäcken stärker underlaget för fortsatt utveckling",
            "Terranor vinner drift- och underhållskontrakt i Göteborg",
        ).forEach { assertFalse(ResultsKeywordFilter.matches(it), it) }
        assertTrue(ResultsKeywordFilter.isCorrection("Rättelse: Opter kvartalsrapport Q3 2026"))
    }
}
