package com.expenselens.domain.model

/**
 * The expense categories the app ships with, plus an UNKNOWN bucket the AI
 * uses while it is still unsure.
 *
 * Add new categories here AND to [seedList] so they show up in the Expenses
 * filter chips and get inserted into a fresh database. The
 * [ExpenseRepository.seedMissingCategories] helper back-fills any new ones
 * onto existing installs.
 */
enum class CategoryType(val displayName: String) {
    FOOD_COST("Food Cost"),
    PACKAGING_COST("Packaging Cost"),
    ELECTRICITY("Electricity"),
    STAFF_SALARY("Salary"),
    STAFF_RENT("Staff Rent"),
    SHOP_RENT("Shop Rent"),
    RENT("Rent"),
    MAINTENANCE("Maintenance"),
    MISCELLANEOUS("Miscellaneous"),
    UNKNOWN("Uncategorized");

    companion object {
        fun fromName(name: String?): CategoryType =
            values().firstOrNull { it.displayName.equals(name, ignoreCase = true) } ?: UNKNOWN

        val seedList: List<CategoryType>
            get() = listOf(
                FOOD_COST,
                PACKAGING_COST,
                ELECTRICITY,
                STAFF_SALARY,
                STAFF_RENT,
                SHOP_RENT,
                RENT,
                MAINTENANCE,
                MISCELLANEOUS
            )
    }
}
