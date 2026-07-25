package com.expenselens.categorize

import com.expenselens.domain.model.CategoryType

interface CategoryClassifier {
    fun classify(description: String, vendor: String): Pair<CategoryType, Float>
}

/**
 * On-device keyword-based classifier. Cheap, deterministic, works offline.
 * Real ML would be a nice upgrade but this gets the app functional on day one.
 */
class KeywordCategoryClassifier : CategoryClassifier {

    private val rules: List<Pair<CategoryType, List<String>>> = listOf(
        CategoryType.FOOD_COST to listOf(
            "milk", "curd", "yogurt", "paneer", "butter", "cheese", "ghee",
            "rice", "wheat", "flour", "atta", "maida", "sugar", "salt",
            "oil", "masala", "spice", "tea", "coffee", "biscuit", "bread",
            "vegetable", "veg ", "onion", "potato", "tomato", "carrot",
            "garlic", "ginger", "green chilli", "fruit", "banana", "apple",
            "egg", "chicken", "mutton", "fish", "dal", "pulses"
        ),
        CategoryType.PACKAGING_COST to listOf(
            "paper bag", "polythene", "packaging", "container", "box",
            "carton", "tape", "label", "sticker", "disposable", "cup",
            "glass", "fork", "spoon", "straw", "wrapper", "cling film",
            "thermo", "insulated"
        ),
        CategoryType.ELECTRICITY to listOf(
            "electricity", "kwh", "units", "eb bill", "power bill",
            "electric bill", "tneb", "bescom", "kseb", "tata power",
            "current bill", "light bill"
        ),
        CategoryType.STAFF_SALARY to listOf(
            "salary", "wages", "payroll", "incentive", "bonus",
            "overtime", "ot ", "staff payment", "advance to staff"
        ),
        CategoryType.STAFF_RENT to listOf(
            "staff rent", "staff accomodation", "staff accommodation",
            "pg rent", "hostel rent", "staff quarter", "staff quarters"
        ),
        CategoryType.SHOP_RENT to listOf(
            "shop rent", "office rent", "godown rent", "warehouse rent",
            "rental", "lease", "rent"
        )
    )

    override fun classify(description: String, vendor: String): Pair<CategoryType, Float> {
        val haystack = "$vendor $description".lowercase()

        for ((cat, keywords) in rules) {
            val hits = keywords.count { haystack.contains(it) }
            if (hits > 0) {
                // Higher hit-count => higher confidence, capped at 0.95.
                val conf = (0.6f + 0.1f * hits).coerceAtMost(0.95f)
                return cat to conf
            }
        }
        return CategoryType.MISCELLANEOUS to 0.4f
    }
}

class CompositeCategoryClassifier(
    private val primary: CategoryClassifier,
    private val secondary: CategoryClassifier
) : CategoryClassifier {
    override fun classify(description: String, vendor: String): Pair<CategoryType, Float> {
        val (a, ca) = primary.classify(description, vendor)
        val (b, cb) = secondary.classify(description, vendor)
        return if (cb > ca) b to cb else a to ca
    }
}
