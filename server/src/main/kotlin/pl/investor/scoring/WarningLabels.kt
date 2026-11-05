package pl.investor.scoring

object WarningLabels {

    fun label(warning: Warning): String = when (warning) {
        Warning.NO_CONSENSUS -> "Brak konsensusu analityków, porównanie z własnym trendem spółki"
        Warning.ONE_OFF_QUARTER -> "Jednorazowy kwartał: niespodzianka liczona najwyżej neutralnie"
        Warning.CYCLICAL_INDUSTRY -> "Branża cykliczna: niska wycena może oznaczać szczyt cyklu"
        Warning.LOW_LIQUIDITY -> "Niska płynność"
        Warning.MISSING_DATA -> "Brak części danych, składowe neutralne"
        Warning.NO_AI -> "Ocena bez AI"
        Warning.UNCERTAIN_NUMBERS -> "Część liczb niepewna"
        Warning.RUN_UP -> "Kurs mocno wzrósł przed raportem: o ponad 15 pkt proc. więcej niż indeks w 10 sesjach"
        Warning.EARLY_STAGE -> "Strata większa niż przychody: wzrost, marża i niespodzianka liczone najwyżej neutralnie"
        Warning.PENNY_STOCK -> "Akcja groszowa (kurs poniżej 1 zł lub 1 SEK): ruchy w ciągu dnia to w dużej mierze szum"
        Warning.REVENUE_ALREADY_KNOWN -> "Przychody za ten kwartał spółka podała już wcześniej (sprzedaż miesięczna lub szacunki): niespodzianka liczona najwyżej neutralnie"
        Warning.PROFIT_WITHOUT_CASH -> "Zysk bez gotówki: przepływy operacyjne ujemne mimo zysku netto, jakość liczona najwyżej neutralnie"
    }
}
