package pl.investor.support

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

fun Instant.toWarsawDate(): LocalDate = atZone(WARSAW).toLocalDate()

val STOCKHOLM: ZoneId = ZoneId.of("Europe/Stockholm")

val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

val AMSTERDAM: ZoneId = ZoneId.of("Europe/Amsterdam")
