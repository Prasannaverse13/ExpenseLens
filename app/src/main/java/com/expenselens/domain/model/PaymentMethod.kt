package com.expenselens.domain.model

enum class PaymentMethod(val displayName: String) {
    CASH("Cash"),
    UPI("UPI"),
    CARD("Card"),
    NET_BANKING("Net Banking"),
    CHEQUE("Cheque"),
    OTHER("Other");

    companion object {
        fun fromName(name: String?): PaymentMethod =
            values().firstOrNull { it.displayName.equals(name, ignoreCase = true) } ?: OTHER
    }
}
