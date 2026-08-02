package com.expenselens.domain.model

/**
 * Optional extras produced by smart (LLM-assisted) extraction. Stored
 * separately from the core expense so the parser-only path is unaffected.
 *
 * The "phone" field is a list because receipts often print multiple numbers
 * (landline + mobile). "source" is the internal extraction tag — never shown
 * to the user, but useful for analytics and debugging.
 */
data class ExpenseMetadata(
    val merchantPhone: List<String> = emptyList(),
    val merchantEmail: String? = null,
    val fssaiNumber: String? = null,
    val visitTime: String? = null,
    val itemCount: Int? = null,
    val source: String? = null
)
