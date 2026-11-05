package pl.investor.pipeline

import pl.investor.report.Market

object RoutineNewsFilter {

    private val MATERIAL = patterns(
        "dywidend",
        "dividend",
        "wezwani",
        "tender offer",
        "zmian\\p{L}* (firmy|nazwy)",
        "zmian\\p{L}* (udzialowca|akcjonariusza) wiekszosciow",
        "^nabycie znacznego pakietu akcji spolki",
        "uppkopserbjudande",
        "budpliktsbud",
        "public (cash )?offer",
        "utdelning",
    )

    private val ROUTINE = patterns(
        "art\\.? ?19(?!\\d)",
        "596/2014",
        "obowiazki zarzadcze",
        "blisko zwiazan",
        "osoby zobowiazane",
        "transakcj\\p{L}*( \\p{L}+)? na (akcjach|papierach)",
        "nabycie akcji przez (prezesa|wiceprezesa|czlonka)",
        "manager\\p{L}*'? transaction",
        "pdmr",
        "art\\.? ?69(?!\\d)",
        "art\\.? ?148 ?b",
        "art\\.? ?6a(?!\\p{L})",
        "stan\\p{L}* posiadania",
        "zmian\\p{L}* (w )?udzial",
        "przekroczeni\\p{L}* progu",
        "znaczn\\p{L}* pakiet",
        "zawiadomieni\\p{L}* dotyczac\\p{L}* nabycia akcji",
        "voting rights",
        "major holding",
        "(termin|dat)\\p{L}* (przekazania|publikacji)",
        "harmonogram\\p{L}* publikacji",
        "publikacj\\p{L}* raport\\p{L}* okresow",
        "publikacj\\p{L}* raport\\p{L}* .*spolk\\p{L}* zalezn",
        "statu[tc]",
        "tekst\\p{L}* jednolit",
        "adres\\p{L}* siedziby",
        "firm\\p{L}* audytorsk",
        "bieglego rewidenta",
        "\\bauditor",
        "polityk\\p{L}* wynagrodzen",
        "sprawozdani\\p{L}* o wynagrodzeniach",
        "remuneration",
        "dobr\\p{L}* praktyk",
        "corporate governance",
        "animator",
        "market maker",
        "autoryzowan\\p{L}* doradc",
        "authori[sz]ed adviser",
        "raport\\p{L}* miesieczn",
        "monthly report",
        "wykaz\\p{L}* informacji",
        "zestawieni\\p{L}* informacji",
        "^nabycie akcji wlasnych\\.?$",
        "transakcj.*(akcji wlasnych|skupu)",
        "transactions over its own shares",
        "acquisition of treasury shares",
        "notification for sale of treasury shares",
        "podsumowanie (programu )?skupu",
        "podsumowanie subskrypcji",
        "wprowadzeni\\p{L}* .*do obrotu",
        "kdpw",
        "krajow\\p{L}* depozyt",
        "asymilacj",
        "stabiliz(ac|uj)",
        "metadanych",
        "wartosc aktywow pod zarzadzaniem",
        "dyrektor\\p{L}* (nie)?wykonawcz",
        "kadencji dyrektorow",
        "flaggningsmeddelande",
        "flagging",
        "insynsperson",
        "insider transaction",
        "antal aktier och roster",
        "number of shares and votes",
        "aterkop av (egna )?aktier.*vecka",
        "(repurchase|buy-?back)\\p{L}*.*week",
        "(transactions|repurchases?) (in|of) own shares",
        "valberedning",
        "nomination committee",
        "likviditetsgarant",
        "liquidity provider",
        "certified adviser",
        "finansiell kalender",
        "financial calendar",
        "(andrat|nytt|tidigarelagt|senarelagt) (rapport)?datum",
        "(change|changed|new) (of )?(reporting |report )?date",
        "inbjudan till (presentation|webbsandning|telefonkonferens|kapitalmarknadsdag)",
        "invitation to (the |a )?(presentation|webcast|conference call|audiocast|capital markets day)",
        "invites to (the |a )?(presentation|webcast|conference call)",
    )

    private val GENERAL_MEETING = patterns(
        "waln\\p{L}* zgromadz",
        "\\b(zwz|nwz|wz)\\b",
        "general meeting",
        "shareholders'? meeting",
        "co najmniej 5 ?%",
        "porzadk\\p{L}* obrad",
        "bolagsstamma",
        "arsstamma",
        "kallelse till",
        "notice to (the )?(annual|extraordinary)",
    )
    private val GERMAN_ROUTINE = patterns(
        "directors'? dealings",
        "stimmrechtsmitteilung",
        "veroffentlichung von finanzberichten",
    )
    private val GERMAN_GENERAL_MEETING = patterns(
        "(einberufung|einladung) (der|zur) (ordentlichen )?hauptversammlung",
        "(abstimmungs|ergebnisse?)\\p{L}* der (ordentlichen )?hauptversammlung",
        "hauptversammlung .*(beschlossen|abstimmungsergebnis)",
        "convocation (of the )?(annual general meeting|agm)",
        "voting results of the (annual )?general meeting",
    )
    private val DUTCH_ROUTINE = patterns(
        "transactions? (in|of) own shares",
        "acquisition of own shares",
        "inkoop van eigen aandelen",
        "(buy-?back|repurchase)\\p{L}*( program\\p{L}*)? transaction",
        "progress (on|of)\\b.*(buy-?back|repurchase|transactions)",
        "update (of|on) (the |its )?(share )?(buy-?back|repurchase)",
        "periodic report on the (share )?(buy-?back|repurchase)",
        "(completes?|completion of)( its| the)? (share )?(buy-?back|repurchase)",
        "(completes?|completion of)\\b.*\\btranche",
        "transactions under",
        "(weekly )?share transactions",
        "notifications? of (share )?transactions?",
        "\\bvoting rights",
        "holding\\(?s?\\)? in company",
        "transparency notification",
        "transparantieverklaring",
        "director/pdmr",
        "liquidity program",
        "net asset value",
        "final terms",
        "dividend timetable",
        "\\bscrip\\b",
        "conversion ratio",
        "(convenes|convocation of)( an?| the)? (extraordinary |annual )?(general meeting|agm|egm)",
        "convenes (an )?(agm|egm)",
        "\\b(agm|egm) agenda",
        "poster presentation",
        "posterpresentatie",
    )
    private val MEETING_OUTCOME = Regex("\\b(approv\\p{L}*|results? of|voting results|resolutions? adopted)\\b")
    private val EXTRAORDINARY_MEETING = Regex("au(ß|ss)erordentlich|extraordinary|\\begm\\b")
    private val GERMAN_MATERIAL = Regex(
        "aktienruckkauf|verschmelzung|abspaltung|ubernahme|verlust der halfte|§ ?92|insolvenz|delisting|squeeze-?out|beherrschungs|kapitalerhohung|capital increase",
    )

    private val MATERIAL_RESOLUTION = Regex("skup|nabywani\\p{L}* akcji wlasnych|emisj|polaczen|aterkop|emission|fusion|buy-?back|repurchase|share issue|merger")

    private val SUPERVISORY_BOARD = Regex("rad\\p{L}* nadzorcz")
    private val BOARD_CHANGE = Regex("powolan|rezygnac|odwolan|sklad|kandydat|czlonk|kadencj")
    private val MANAGEMENT_BOARD = Regex("zarzad|prezes|\\bceo\\b")

    private val BOARD_OF_DIRECTORS = Regex("rad\\p{L}* dyrektorow")
    private val DIRECTORS_TERM = Regex("ponown|wybor|kadencj")

    fun isRoutine(title: String, market: Market): Boolean {
        val text = title.foldedTitle()
        if (market == Market.NL && text.isDutchRoutine()) return true
        if (MATERIAL.any { it.containsMatchIn(text) }) return false
        if (market == Market.DE && GERMAN_MATERIAL.containsMatchIn(text)) return false
        return ROUTINE.any { it.containsMatchIn(text) } ||
            (text.isGeneralMeetingFormality() && !(market == Market.NL && MEETING_OUTCOME.containsMatchIn(text))) ||
            text.isSupervisoryBoardChange() ||
            text.isDirectorsReelection() ||
            (market == Market.DE && text.isGermanRoutine())
    }

    private fun String.isDutchRoutine(): Boolean =
        DUTCH_ROUTINE.any { it.containsMatchIn(this) } || ResultsKeywordFilter.isDutchResultsAnnouncement(this)

    private fun String.isGermanRoutine(): Boolean =
        GERMAN_ROUTINE.any { it.containsMatchIn(this) } ||
            (GERMAN_GENERAL_MEETING.any { it.containsMatchIn(this) } && !EXTRAORDINARY_MEETING.containsMatchIn(this) && !MATERIAL_RESOLUTION.containsMatchIn(this))

    private fun String.isGeneralMeetingFormality(): Boolean =
        GENERAL_MEETING.any { it.containsMatchIn(this) } && !MATERIAL_RESOLUTION.containsMatchIn(this)

    private fun String.isSupervisoryBoardChange(): Boolean =
        SUPERVISORY_BOARD.containsMatchIn(this) && BOARD_CHANGE.containsMatchIn(this) && !MANAGEMENT_BOARD.containsMatchIn(this)

    private fun String.isDirectorsReelection(): Boolean =
        BOARD_OF_DIRECTORS.containsMatchIn(this) && DIRECTORS_TERM.containsMatchIn(this)

    private fun patterns(vararg regexes: String): List<Regex> = regexes.map(::Regex)
}
