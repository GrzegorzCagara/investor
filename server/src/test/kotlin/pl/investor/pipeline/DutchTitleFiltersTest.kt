package pl.investor.pipeline

import org.junit.jupiter.api.Test
import pl.investor.report.Market
import pl.investor.source.euronext.EuronextDuplicates
import pl.investor.source.euronext.EuronextListingParser
import kotlin.test.assertEquals

class DutchTitleFiltersTest {

    private val listing = (0..4).flatMap { page ->
        EuronextListingParser.parse(checkNotNull(javaClass.getResource("/fixtures/nl/euronext-listing-p$page.json")).readText())
    }

    private fun failing(titles: List<String>, expected: Boolean, check: (String) -> Boolean) = titles.filter { check(it) != expected }

    @Test
    fun `every results title of the Euronext sample goes to results extraction`() {
        val results = listOf(
            "BAM delivered strong performance in the first nine months 2026",
            "IMCD reports EBITA of EUR 425 million (+8%) in the first nine months of 2026",
            "Vopak reports strong YTD performance, raises FY 2026 outlook",
            "Philips delivers strong order intake growth, comparable sales growth and strong margin expansion in Q3; 2026 outlook reiterated",
            "KPN delivers healthy service revenue growth; on track to reach FY outlook",
            "ING shows continued commercial momentum in 3Q2026 and is well on track to deliver on 2026 outlook",
            "HEINEKEN N.V. REPORTS ON 2026 THIRD QUARTER TRADING",
            "Flow Traders 3Q 2026 Trading Update",
            "CTP N.V. Q3-2026 Results",
            "BE Semiconductor Industries N.V. Announces Q3-2026 Results",
            "Cabka Reports Continued Revenue Growth and Strong Profitability in Q3 2026",
            "ASML reports €11.7 billion total net sales and €5.0 billion net income in Q3 2026",
            "CVC Capital Partners plc - Q3 2026 activity update",
            "Press Release Holland Colours NV Half Year Results 2026/2027",
            "Van Lanschot Kempen trading update for the third quarter of 2026",
            "Van Lanschot Kempen trading update over het derde kwartaal van 2026",
            "TRADING UPDATE - October 2026",
            "ArcelorMittal reports third quarter 2026 results",
            "ForFarmers N.V.: Trading update Q3 2026",
            "Wolters Kluwer 2026 Nine-Month Trading Update",
            "EUROCOMMERCIAL PROPERTIES N.V.: NINE-MONTH RESULTS 2026",
            "SHELL PLC 3rd QUARTER 2026 AND NINE MONTHS UNAUDITED RESULTS",
            "Q3 2026 Trading Update",
            "Corbion delivers strong volume-led sales growth and significant margin improvement in Q3; full-year guidance maintained",
            "ACOMO SALES TRENDS IMPROVE IN Q3 2026 AS FOUR OUT OF FIVE SEGMENTS REPORT GROWTH",
            "Aperam update on Q3 2026 market & financial trends",
            "Vivoryon Therapeutics N.V. Reports Q3 2026 Financial Results and Provides Business Update",
        )
        assertEquals(emptyList(), failing(results, expected = true) { ResultsKeywordFilter.matches(it, Market.NL) })
        assertEquals(emptyList(), failing(results, expected = false) { RoutineNewsFilter.isRoutine(it, Market.NL) && !ResultsKeywordFilter.matches(it, Market.NL) })
    }

    @Test
    fun `announcements of results never go to AI`() {
        val announcements = listOf(
            "Third Quarter 2026 Results Release and Earnings Call",
            "AMG Schedule for Third Quarter 2026 Earnings Release",
            "Vivoryon Therapeutics N.V. to Report Q3 2026 Financial Results on November 5, 2026",
            "ASM announces details of the Q3 2026 conference call and webcast",
            "BE Semiconductor Industries N.V. Announces 2026 Third Quarter Conference Call and Audio Webcast Details",
            "IMCD 2026 First Nine Months results webcast invitation",
            "ArcelorMittal announces the publication of its third quarter 2026 sell-side analyst consensus figures",
        )
        assertEquals(emptyList(), failing(announcements, expected = false) { ResultsKeywordFilter.matches(it, Market.NL) })
        assertEquals(emptyList(), failing(announcements, expected = true) { RoutineNewsFilter.isRoutine(it, Market.NL) })
    }

    @Test
    fun `routine Dutch market notices are skipped without AI`() {
        val routine = listOf(
            "Transaction in Own Shares",
            "Transactions in Own Shares",
            "Share Buyback Transaction Details October 29 – November 4, 2026",
            "Weekly Share Buyback Programme Transaction Details",
            "Weekly share repurchase program transaction details",
            "BE Semiconductor Industries N.V. Announces Transactions Under Share Repurchase Program",
            "Update of repurchase programme",
            "Director/PDMR Shareholding",
            "Progress on share buyback programme",
            "Banijay Group: weekly share transactions",
            "Total Voting Rights",
            "Voting Rights and Capital",
            "Heineken N.V. reports the progress of transactions under its current share buyback programme",
            "Havas Reports the Progress of Transactions Under Its Liquidity Program",
            "Exor Press Release - Periodic Report on the Buyback Program",
            "Notification of share transactions by Lumen Investments S.à r.l",
            "Notifications of transaction in ArcelorMittal shares – BlackRock",
            "Holding(s) in Company",
            "Volta Finance Limited Net Asset Value(s) as at 30 September 2026",
            "Publication of Final Terms",
            "SHELL PLC 2027 INTERIM DIVIDEND TIMETABLE",
            "CTP N.V. announces scrip take-up and conversion ratio of 2026 interim dividend",
            "Lakefront Biotherapeutics Announces Poster Presentation at the 2026 American Society of Hematology Meeting",
            "Lakefront Biotherapeutics kondigt posterpresentatie aan op het congres van de American Society of Hematology 2026",
            "Acquisition of Own Shares",
            "Inkoop van eigen aandelen",
            "Lakefront Receives Transparency Notification from Bank of America",
            "Lakefront ontvangt transparantieverklaring van Bank of America",
            "Corbion convenes Extraordinary General Meeting of Shareholders to appoint new member of Supervisory Board",
            "Aalberts reports the completion of its share buyback 2026 programme",
        )
        assertEquals(emptyList(), failing(routine, expected = true) { RoutineNewsFilter.isRoutine(it, Market.NL) })
        assertEquals(emptyList(), failing(routine, expected = false) { ResultsKeywordFilter.matches(it, Market.NL) })
    }

    @Test
    fun `significant news without results words is assessed as news`() {
        val news = listOf(
            "Applied Materials to Increase its Strategic Investment in BE Semiconductor Industries",
            "THEON awarded Night Vision contracts worth c.€45 million from two European NATO member states",
            "Wolters Kluwer to divest German consumer tax business",
            "BAM takes leading position in Dutch energy and water infrastructure with A.Hak acquisition",
            "Envipco appointed exclusive supplier to major UK retailer",
            "EUROCOMMERCIAL PROPERTIES N.V.: SUCCESSFULLY PLACES 5.2 MILLION NEW SHARES, RAISING EUR 128.4 MILLION",
            "ING announces new distribution programme of up to €1.9 billion",
            "OCI Announces EGM Approval and Updated Indicative Timetable for Orascom Construction Combination",
            "Aegon EGM approves Redomiciliation and Omnibus Incentive Plan",
            "Shell announces commencement of a share buyback programme",
            "BE Semiconductor Industries N.V. Completes € 60 Million Share Repurchase Program. New € 100 Million Share Repurchase Program Initiated",
            "CTP announces share buyback programme of up to €100 million",
            "Invitation to EGM on the merger with X",
        )
        assertEquals(emptyList(), failing(news, expected = false) { ResultsKeywordFilter.matches(it, Market.NL) || RoutineNewsFilter.isRoutine(it, Market.NL) })
    }

    @Test
    fun `profit warnings reach AI even when they say they expect to report`() {
        listOf("Signify expects to report lower than expected EBITA for 2026", "Fugro expects 2026 revenue below guidance").forEach {
            assertEquals(true, ResultsKeywordFilter.matches(it, Market.NL), it)
            assertEquals(false, ResultsKeywordFilter.isDutchResultsAnnouncement(it), it)
        }
    }

    @Test
    fun `NL keywords do not change other markets`() {
        assertEquals(false, ResultsKeywordFilter.matches("Flow Traders 3Q 2026 Trading Update", Market.GPW))
        assertEquals(false, RoutineNewsFilter.isRoutine("SHELL PLC 2027 INTERIM DIVIDEND TIMETABLE", Market.SE))
        assertEquals(true, ResultsKeywordFilter.matches("Third Quarter 2026 Results Release and Earnings Call", Market.SE))
    }

    @Test
    fun `only the Dutch titles of a month of Euronext Amsterdam news are detected as Dutch`() {
        val dutch = listing.filter { EuronextDuplicates.isDutch(it.title) }.map { it.nid }.toSet()

        assertEquals(
            setOf("12921299", "12921114", "12920692", "12919846", "12918666", "12917506", "12916425", "12916182", "12915387", "12914780", "12914775"),
            dutch,
        )
        assertEquals(false, EuronextDuplicates.isDutch("Appointment of Jan van de Berg as CFO completes de-risking of the balance sheet"))
        assertEquals(false, EuronextDuplicates.isDutch("Wolters Kluwer in de-risking deal with Banco de Sabadell"))
    }
}
