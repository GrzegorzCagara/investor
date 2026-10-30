package pl.investor.scoring

object WarningLabels {

    fun label(warning: Warning): String = when (warning) {
        Warning.NO_CONSENSUS -> "Brak konsensusu analityków, porównanie z własnym trendem spółki"
        Warning.ONE_OFF_QUARTER -> "Jednorazowy kwartał"
        Warning.CYCLICAL_INDUSTRY -> "Branża cykliczna: niska wycena może oznaczać szczyt cyklu"
        Warning.LOW_LIQUIDITY -> "Niska płynność"
        Warning.MISSING_DATA -> "Brak części danych, składowe neutralne"
        Warning.NO_AI -> "Ocena bez AI"
        Warning.UNCERTAIN_NUMBERS -> "Część liczb niepewna"
    }
}
