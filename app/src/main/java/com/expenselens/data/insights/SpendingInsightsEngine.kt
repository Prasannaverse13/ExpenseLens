package com.expenselens.data.insights

import com.expenselens.data.db.CategoryEntity
import com.expenselens.data.db.CategoryTotal
import com.expenselens.data.db.ExpenseEntity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Pure (no Android, no IO) function that turns a window of expenses +
 * the user's category list into a sorted, capped list of [SpendingInsight]s
 * for the dashboard carousel.
 *
 *  - Pure: same inputs → same outputs. Easy to unit test.
 *  - Side-effect free: doesn't touch Room, Drive, or Supabase.
 *  - Window-aware: caller picks [thisMonthStart, today] and
 *    [lastMonthStart, lastMonthEnd]; the engine does the rest.
 *  - Premium-aware: the caller can post-filter the [requiresPremium] field
 *    to gate the deeper insights (6-month trend, anomaly) behind
 *    a `isPremium` check. v1 returns all insights; gating is layered
 *    on at the call site so the engine stays free of business logic.
 *
 *  v2 additions — the three "viral" insight types:
 *    6) VendorMonthOverMonth  — "32% more on Swiggy this month"
 *    7) UpcomingRecurring     — "Phone bill renews in 3 days • last ₹649"
 *    8) UnusedRecurring       — "₹4,200 in subs quiet for 30+ days"
 *
 *  These three need [allExpenses] (not just this month) to detect
 *  vendor patterns. They are pure-Kotlin: group by vendor, look at
 *  gaps, predict next charge.
 */
object SpendingInsightsEngine {

    /**
     * @param thisMonth         expenses dated in the current month
     * @param thisMonthTotals   pre-aggregated per-category totals for this month
     * @param lastMonthTotals   pre-aggregated per-category totals for last month
     * @param allExpenses       ALL expenses the user has — used to detect
     *                          recurring vendors. Same data Room already
     *                          serves via `repo.observeAll()`, so no extra
     *                          queries.
     * @param categories        every category in the DB (for name lookup)
     * @param today             "now" (injected for testability)
     * @param maxInsights       cap on returned list (carousel shows 3-5 nicely)
     */
    fun generate(
        thisMonth: List<ExpenseEntity>,
        thisMonthTotals: List<CategoryTotal>,
        lastMonthTotals: List<CategoryTotal>,
        allExpenses: List<ExpenseEntity>,
        categories: List<CategoryEntity>,
        today: LocalDate,
        maxInsights: Int = 4
    ): List<SpendingInsight> {
        val byId = categories.associateBy { it.id }
        val insights = mutableListOf<SpendingInsight>()

        // 1) Month-over-month (top of the carousel, always shown)
        val thisTotal = thisMonth.sumOf { it.totalAmount }
        val lastTotal = lastMonthTotals.sumOf { it.total }
        if (lastTotal > 0.0 || thisTotal > 0.0) {
            val pct = if (lastTotal > 0.0) {
                ((thisTotal - lastTotal) / lastTotal) * 100.0
            } else 100.0
            insights += SpendingInsight.MonthOverMonth(
                pctDelta = pct,
                thisTotal = thisTotal,
                lastTotal = lastTotal
            )
        }

        // 2) Top category — only when there's a meaningful #1
        val topTotal = thisMonthTotals.maxByOrNull { it.total }
        if (topTotal != null && topTotal.total > 0.0) {
            val name = byId[topTotal.categoryId]?.name ?: "Other"
            val lastForCat = lastMonthTotals.firstOrNull { it.categoryId == topTotal.categoryId }?.total ?: 0.0
            insights += SpendingInsight.TopCategory(
                categoryName = name,
                amount = topTotal.total,
                pctDelta = if (lastForCat > 0.0) ((topTotal.total - lastForCat) / lastForCat) * 100.0 else null
            )
        }

        // 3) Daily average — only when there's at least 7 days of data
        val daysInMonth = today.dayOfMonth.coerceAtLeast(1)
        if (daysInMonth >= 7 && thisTotal > 0.0) {
            val lastMonthDayCount = daysInLastMonth(today)
            val lastAvg = if (lastMonthDayCount > 0) lastTotal / lastMonthDayCount else 0.0
            insights += SpendingInsight.DailyAverage(
                thisAvg = thisTotal / daysInMonth,
                lastAvg = lastAvg
            )
        }

        // 4) Highest-spend single day this month
        val byDay = thisMonth.groupBy { it.billDate }
        val bestDay: Pair<LocalDate, Double>? = byDay
            .map { (d, list) -> d to list.sumOf { it.totalAmount } }
            .maxByOrNull { it.second }
        if (bestDay != null && bestDay.second > 0.0) {
            insights += SpendingInsight.HighestDay(
                date = bestDay.first,
                amount = bestDay.second
            )
        }

        // 5) Quietest day-of-week — only meaningful if user has ≥ 4 weeks of data
        if (thisMonth.size >= 20) {
            val dowTotals = thisMonth.groupBy { it.billDate.dayOfWeek }
                .mapValues { (_, list) -> list.sumOf { it.totalAmount } }
            val quietest = dowTotals.minByOrNull { it.value }
            if (quietest != null && quietest.value < thisTotal * 0.05) {
                val dayName = quietest.key.getDisplayName(TextStyle.FULL, Locale.getDefault())
                insights += SpendingInsight.QuietestDay(dayName = dayName)
            }
        }

        // ---- v2: viral, vendor-aware insights ----

        // 6) Vendor month-over-month — biggest vendor swing this month
        val vendorMoM = detectVendorMonthOverMonth(thisMonth, allExpenses, lastMonthStart(today), lastMonthEnd(today))
        if (vendorMoM != null) insights += vendorMoM

        // 7) Upcoming recurring bill — predict next charge from history
        val upcoming = detectUpcomingRecurring(allExpenses, today)
        if (upcoming != null) insights += upcoming

        // 8) Unused recurring — subs that stopped charging 30+ days ago
        val unused = detectUnusedRecurring(allExpenses, today)
        if (unused != null) insights += unused

        return insights
            .sortedByDescending { it.priority }
            .take(maxInsights)
    }

    /** Number of days in the previous calendar month — for fair daily-avg compare. */
    private fun daysInLastMonth(today: LocalDate): Int {
        val firstOfThisMonth = today.withDayOfMonth(1)
        val lastOfPrevMonth = firstOfThisMonth.minusDays(1)
        return lastOfPrevMonth.lengthOfMonth()
    }

    private fun lastMonthStart(today: LocalDate): LocalDate =
        today.withDayOfMonth(1).minusMonths(1)

    private fun lastMonthEnd(today: LocalDate): LocalDate =
        today.withDayOfMonth(1).minusDays(1)

    /**
     * Find the vendor with the biggest % increase this month vs last.
     *  - Requires ≥ 2 bills this month for the same vendor (a single
     *    Swiggy order is a coincidence; two is a pattern).
     *  - Requires last-month spend > 0 (else we can't compute a %).
     *  - Ignores trivial swings (< 5%) to avoid noise.
     *  - Returns the largest positive swing, or null if none qualifies.
     */
    private fun detectVendorMonthOverMonth(
        thisMonth: List<ExpenseEntity>,
        allExpenses: List<ExpenseEntity>,
        lastMonthStart: LocalDate,
        lastMonthEnd: LocalDate
    ): SpendingInsight.VendorMonthOverMonth? {
        if (thisMonth.isEmpty()) return null

        val thisByVendor: Map<String, List<ExpenseEntity>> = thisMonth
            .groupBy { normalizeVendor(it.vendor) }
        val lastByVendor: Map<String, List<ExpenseEntity>> = allExpenses
            .filter { !it.billDate.isBefore(lastMonthStart) && !it.billDate.isAfter(lastMonthEnd) }
            .groupBy { normalizeVendor(it.vendor) }

        val candidates = thisByVendor.mapNotNull { (key, thisList) ->
            val thisTotal = thisList.sumOf { it.totalAmount }
            if (thisList.size < 2 || thisTotal < 200.0) return@mapNotNull null
            val lastTotal = lastByVendor[key]?.sumOf { it.totalAmount } ?: 0.0
            if (lastTotal <= 0.0) return@mapNotNull null
            val pct = ((thisTotal - lastTotal) / lastTotal) * 100.0
            if (pct < 5.0) return@mapNotNull null
            Triple(thisList.first().vendor, pct, thisTotal to lastTotal)
        }
        val top = candidates.maxByOrNull { it.second } ?: return null
        return SpendingInsight.VendorMonthOverMonth(
            vendor = top.first,
            pctDelta = top.second,
            thisAmount = top.third.first,
            lastAmount = top.third.second
        )
    }

    /**
     * Predict the next charge for any vendor that has been billed regularly
     * (≥ 2 prior bills, median gap 7-90 days). If the predicted date is
     * within 7 days of today (or overdue), surface it.
     *
     *  - Median gap (not average) ignores one-off outliers like a 90-day
     *    pause or a duplicate entry.
     *  - We show at most one upcoming bill — the closest. Multiple
     *    "due tomorrow" cards would feel spammy.
     */
    private fun detectUpcomingRecurring(
        allExpenses: List<ExpenseEntity>,
        today: LocalDate
    ): SpendingInsight.UpcomingRecurring? {
        if (allExpenses.size < 2) return null

        val byVendor: Map<String, List<ExpenseEntity>> = allExpenses
            .groupBy { normalizeVendor(it.vendor) }

        val candidates = byVendor.mapNotNull { (_, list) ->
            if (list.size < 2) return@mapNotNull null
            val sorted = list.sortedBy { it.billDate }
            val lastDate = sorted.last().billDate
            val daysSinceLast = ChronoUnit.DAYS.between(lastDate, today)
            // Too recent: don't predict for charges made in the last 7 days
            if (daysSinceLast < 7) return@mapNotNull null

            val gaps = sorted.zipWithNext { a, b ->
                ChronoUnit.DAYS.between(a.billDate, b.billDate)
            }.filter { it in 7..90 }
            if (gaps.isEmpty()) return@mapNotNull null

            val medianGap = gaps.sorted()[gaps.size / 2]
            val nextDate = lastDate.plusDays(medianGap)
            val daysUntil = ChronoUnit.DAYS.between(today, nextDate).toInt()
            // Surface anything in the next 7 days, or up to 3 days overdue
            if (daysUntil !in -3..7) return@mapNotNull null

            SpendingInsight.UpcomingRecurring(
                vendor = sorted.last().vendor,
                daysUntil = daysUntil,
                lastAmount = sorted.last().totalAmount,
                lastDate = lastDate
            )
        }
        // Show the closest one (could be negative — overdue)
        return candidates.minByOrNull { it.daysUntil }
    }

    /**
     * Find vendors that were being charged regularly but haven't appeared
     * in 30+ days. Sum the typical monthly amount so the user sees the
     * size of the saving (or the size of the surprise).
     *
     *  - Requires ≥ 2 charges in the prior 90-day window so we're not
     *    flagging one-off purchases as "subscriptions".
     *  - 30-day cutoff is the "you'd notice by now" line — anything
     *    shorter and we'd be crying wolf.
     */
    private fun detectUnusedRecurring(
        allExpenses: List<ExpenseEntity>,
        today: LocalDate
    ): SpendingInsight.UnusedRecurring? {
        if (allExpenses.isEmpty()) return null
        val ninetyDaysAgo = today.minusDays(90)
        val recentByVendor: Map<String, List<ExpenseEntity>> = allExpenses
            .filter { !it.billDate.isBefore(ninetyDaysAgo) }
            .groupBy { normalizeVendor(it.vendor) }

        val quiet = recentByVendor.filter { (_, list) ->
            if (list.size < 2) return@filter false
            val lastDate = list.maxBy { it.billDate }.billDate
            ChronoUnit.DAYS.between(lastDate, today) >= 30
        }
        if (quiet.isEmpty()) return null

        // Typical monthly amount = average of the recent charges.
        // Not "sum of all charges" — that would inflate the number.
        val totalMonthly = quiet.values.sumOf { list ->
            list.map { it.totalAmount }.average()
        }
        val topNames = quiet.entries
            .sortedByDescending { it.value.sumOf { e -> e.totalAmount } }
            .take(3)
            .map { it.value.first().vendor }

        return SpendingInsight.UnusedRecurring(
            totalMonthlyAmount = totalMonthly,
            vendorCount = quiet.size,
            topVendors = topNames
        )
    }

    /**
     * Vendor name normalizer. "Swiggy" and "Swiggy India Pvt Ltd" should
     * collapse to the same key. Lowercase + strip common suffixes +
     * collapse whitespace. Good enough for v1 — a real name-entity
     * model would be overkill for a personal-finance app.
     */
    private fun normalizeVendor(raw: String): String {
        val lower = raw.lowercase(Locale.getDefault()).trim()
        val stripped = lower
            .replace(Regex("\\b(pvt|private|ltd|limited|inc|incorporated|llp|llc|co|company)\\b\\.?"), "")
            .replace(Regex("\\b(india|usa|uk)\\b\\.?"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return stripped.ifBlank { lower }
    }
}

/**
 * Sealed hierarchy of all spending insights. Each variant carries the
 * data the UI needs to render its copy + a [priority] for sorting
 * (higher = more interesting = shown first). The carousel picks the
 * top-N by priority.
 *
 *  v1 returns everything regardless of premium. The dashboard
 *  composable is free to hide deeper variants (anomaly, 6-month
 *  trend) when the user isn't premium — by checking
 *  [SpendingInsight.requiresPremium].
 *
 *  v2 adds three vendor-aware, viral-friendly variants:
 *  [VendorMonthOverMonth], [UpcomingRecurring], [UnusedRecurring].
 */
sealed class SpendingInsight {

    /** Higher = more interesting; the carousel shows top-N by this. */
    abstract val priority: Int

    /** True for "deeper" insights gated to Premium. v2 shows all. */
    open val requiresPremium: Boolean = false

    /** Short headline (one line, ~5 words). */
    abstract val title: String

    /** Sub-headline with the actual number. */
    abstract val subtitle: String

    data class MonthOverMonth(
        val pctDelta: Double,
        val thisTotal: Double,
        val lastTotal: Double
    ) : SpendingInsight() {
        override val priority = 100
        override val title: String
            get() = when {
                pctDelta > 5.0 -> "Up ${pctDelta.toInt()}%"
                pctDelta < -5.0 -> "Down ${kotlin.math.abs(pctDelta.toInt())}%"
                else -> "Holding steady"
            }
        override val subtitle: String
            get() = "vs last month on every bill"
    }

    data class TopCategory(
        val categoryName: String,
        val amount: Double,
        val pctDelta: Double?
    ) : SpendingInsight() {
        override val priority = 90
        override val title: String
            get() = "$categoryName tops your list"
        override val subtitle: String
            get() {
                val delta = pctDelta
                return when {
                    delta == null -> "No spend here last month"
                    delta > 5.0 -> "Up ${delta.toInt()}% from last month"
                    delta < -5.0 -> "Down ${kotlin.math.abs(delta.toInt())}% from last month"
                    else -> "About the same as last month"
                }
            }
    }

    /** v2 — biggest vendor swing this month. "32% more on Swiggy". */
    data class VendorMonthOverMonth(
        val vendor: String,
        val pctDelta: Double,
        val thisAmount: Double,
        val lastAmount: Double
    ) : SpendingInsight() {
        override val priority = 95
        override val title: String
            get() {
                val pct = pctDelta.toInt()
                return if (pctDelta >= 0) "${pct}% more on $vendor"
                else "${kotlin.math.abs(pct)}% less on $vendor"
            }
        override val subtitle: String
            get() {
                val fmt = "%,.0f"
                return if (pctDelta >= 0) {
                    "Up from ${fmt.format(lastAmount)} last month"
                } else {
                    "Down from ${fmt.format(lastAmount)} last month"
                }
            }
    }

    /** v2 — "Phone bill renews in 3 days • last ₹649 on Aug 5". */
    data class UpcomingRecurring(
        val vendor: String,
        val daysUntil: Int,
        val lastAmount: Double,
        val lastDate: LocalDate
    ) : SpendingInsight() {
        override val priority = 85
        override val title: String
            get() = when {
                daysUntil < 0 -> "$vendor is overdue"
                daysUntil == 0 -> "$vendor renews today"
                daysUntil == 1 -> "$vendor renews tomorrow"
                else -> "$vendor renews in $daysUntil days"
            }
        override val subtitle: String
            get() {
                val fmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
                val last = "₹${"%,.0f".format(lastAmount)} on ${lastDate.format(fmt)}"
                return if (daysUntil < 0) "Expected by now • last $last"
                else "Last charge: $last"
            }
    }

    data class DailyAverage(
        val thisAvg: Double,
        val lastAvg: Double
    ) : SpendingInsight() {
        override val priority = 70
        override val title: String
            get() = "Daily avg ${"%,.0f".format(thisAvg)}"
        override val subtitle: String
            get() = if (lastAvg > 0.0) {
                val pct = ((thisAvg - lastAvg) / lastAvg) * 100.0
                if (pct > 5.0) "+${pct.toInt()}% vs ${"%,.0f".format(lastAvg)} last month"
                else if (pct < -5.0) "${pct.toInt()}% vs ${"%,.0f".format(lastAvg)} last month"
                else "About even with last month"
            } else "First full month — nothing to compare yet"
    }

    data class HighestDay(
        val date: LocalDate,
        val amount: Double
    ) : SpendingInsight() {
        override val priority = 60
        override val title: String
            get() {
                val today = LocalDate.now()
                val formatter = if (date.year == today.year) DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
                else DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
                return "${date.format(formatter)} was your biggest"
            }
        override val subtitle: String
            get() = "${"%,.0f".format(amount)} in one day"
    }

    data class QuietestDay(
        val dayName: String
    ) : SpendingInsight() {
        override val priority = 50
        override val title: String
            get() = "You skipped $dayName"
        override val subtitle: String
            get() = "Your cheapest day of the week"
    }

    /** v2 — "₹4,200 in subs quiet for 30+ days". */
    data class UnusedRecurring(
        val totalMonthlyAmount: Double,
        val vendorCount: Int,
        val topVendors: List<String>
    ) : SpendingInsight() {
        override val priority = 45
        override val title: String
            get() = "₹${"%,.0f".format(totalMonthlyAmount)} in quiet subs"
        override val subtitle: String
            get() {
                val names = topVendors.joinToString(", ")
                return if (vendorCount <= 3) "No charge in 30+ days: $names"
                else "$vendorCount subs paused (e.g. $names)"
            }
    }
}
