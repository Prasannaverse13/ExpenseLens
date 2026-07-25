package com.expenselens.ui.common

import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

object Format {
    private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)

    fun money(amount: Double, currency: String = "INR"): String {
        val fmt = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
        try { fmt.currency = Currency.getInstance(currency) } catch (_: Throwable) {}
        return fmt.format(amount)
    }

    fun date(d: LocalDate): String = d.format(dateFmt)
    fun dateShort(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH))
}
