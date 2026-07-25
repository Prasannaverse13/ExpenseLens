package com.expenselens.domain.model

/**
 * The seven expense categories required by the spec, plus an UNKNOWN bucket
 * the AI uses while it is still unsure.
 */
enum class CategoryType(val displayName: String) {
    FOOD_COST("Food Cost"),
    PACKAGING_COST("Packaging Cost"),
    ELECTRICITY("Electricity"),
    STAFF_SALARY("Staff Salary"),
    STAFF_RENT("Staff Rent"),
    SHOP_RENT("Shop Rent"),
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
                MISCELLANEOUS
            )
    }
}
