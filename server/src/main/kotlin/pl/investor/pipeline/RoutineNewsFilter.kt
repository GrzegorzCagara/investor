package pl.investor.pipeline

object RoutineNewsFilter {

    private val MATERIAL = patterns(
        "dywidend",
        "dividend",
        "wezwani",
        "tender offer",
        "zmian\\p{L}* (firmy|nazwy)",
        "zmian\\p{L}* (udzialowca|akcjonariusza) wiekszosciow",
        "^nabycie znacznego pakietu akcji spolki",
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
    )

    private val GENERAL_MEETING = patterns(
        "waln\\p{L}* zgromadz",
        "\\b(zwz|nwz|wz)\\b",
        "general meeting",
        "shareholders'? meeting",
        "co najmniej 5 ?%",
        "porzadk\\p{L}* obrad",
    )
    private val MATERIAL_RESOLUTION = Regex("skup|nabywani\\p{L}* akcji wlasnych|emisj|polaczen")

    private val SUPERVISORY_BOARD = Regex("rad\\p{L}* nadzorcz")
    private val BOARD_CHANGE = Regex("powolan|rezygnac|odwolan|sklad|kandydat|czlonk|kadencj")
    private val MANAGEMENT_BOARD = Regex("zarzad|prezes|\\bceo\\b")

    private val BOARD_OF_DIRECTORS = Regex("rad\\p{L}* dyrektorow")
    private val DIRECTORS_TERM = Regex("ponown|wybor|kadencj")

    fun isRoutine(title: String): Boolean {
        val text = title.foldedTitle()
        if (MATERIAL.any { it.containsMatchIn(text) }) return false
        return ROUTINE.any { it.containsMatchIn(text) } ||
            text.isGeneralMeetingFormality() ||
            text.isSupervisoryBoardChange() ||
            text.isDirectorsReelection()
    }

    private fun String.isGeneralMeetingFormality(): Boolean =
        GENERAL_MEETING.any { it.containsMatchIn(this) } && !MATERIAL_RESOLUTION.containsMatchIn(this)

    private fun String.isSupervisoryBoardChange(): Boolean =
        SUPERVISORY_BOARD.containsMatchIn(this) && BOARD_CHANGE.containsMatchIn(this) && !MANAGEMENT_BOARD.containsMatchIn(this)

    private fun String.isDirectorsReelection(): Boolean =
        BOARD_OF_DIRECTORS.containsMatchIn(this) && DIRECTORS_TERM.containsMatchIn(this)

    private fun patterns(vararg regexes: String): List<Regex> = regexes.map(::Regex)
}
