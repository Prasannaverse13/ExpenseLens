package com.expenselens.extract

import com.expenselens.categorize.CategoryClassifier
import com.expenselens.domain.model.ExtractionResult
import com.expenselens.domain.model.LineItem
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Heuristic parser that converts raw OCR text into a structured [ExtractionResult].
 *
 * Intentionally tolerant — receipts are noisy. Anything we can't parse stays
 * blank and the user fills it in on the Review screen.
 */
class ReceiptParser(private val classifier: CategoryClassifier) {

    fun parse(rawText: String): ExtractionResult {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val joined = lines.joinToString("\n")

        val vendor = guessVendor(lines)
        val billNumber = guessBillNumber(joined)
        val billDate = guessDate(joined) ?: LocalDate.now()
        val currency = guessCurrency(joined)
        val total = guessTotal(lines, joined)
        val tax = guessTax(lines)
        val lineItems = guessLineItems(lines, vendor)

        val conf = mutableMapOf(
            "vendor" to if (vendor.isNotBlank()) 0.85f else 0.2f,
            "billNumber" to if (billNumber != null) 0.8f else 0.2f,
            "billDate" to if (billDate != LocalDate.now()) 0.85f else 0.3f,
            "totalAmount" to if (total > 0.0) 0.9f else 0.1f,
            "tax" to if (tax != null) 0.7f else 0.5f,
            "lineItems" to if (lineItems.isNotEmpty()) 0.8f else 0.3f
        )

        val categorized = lineItems.map { item ->
            val (cat, c) = classifier.classify(item.description, vendor)
            item.copy(category = cat, categoryConfidence = c)
        }

        return ExtractionResult(
            vendor = vendor,
            billNumber = billNumber,
            billDate = billDate,
            totalAmount = total,
            taxAmount = tax,
            currency = currency,
            rawText = rawText,
            lineItems = categorized,
            fieldConfidences = conf
        )
    }

    private fun guessVendor(lines: List<String>): String {
        // The vendor is almost always in the first 3 non-trivial lines.
        return lines
            .asSequence()
            .filter { it.length in 3..40 }
            .filter { !it.matches(Regex("^[\\d\\W]+$")) }
            .take(3)
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
            ?: lines.firstOrNull().orEmpty()
    }

    private fun guessBillNumber(text: String): String? {
        val patterns = listOf(
            Regex("(?i)\\bbill\\s*(?:no|number|#)\\s*[:\\-]?\\s*([A-Z0-9\\-]{3,})"),
            Regex("(?i)\\binvoice\\s*(?:no|number|#)\\s*[:\\-]?\\s*([A-Z0-9\\-]{3,})"),
            Regex("(?i)\\bbill\\s*[:\\-]\\s*([A-Z0-9\\-]{3,})")
        )
        return patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) }
    }

    private val dateFormats: List<DateTimeFormatter> = listOf(
        DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d/M/yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d-M-yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
    )

    private fun guessDate(text: String): LocalDate? {
        val regex = Regex("(?i)(?:date|dated|invoice date|bill date)?\\s*[:\\-]?\\s*([0-9]{1,2}[\\-/.\\s][A-Za-z0-9]{1,9}[\\-/.\\s][0-9]{2,4})")
        val candidate = regex.find(text)?.groupValues?.getOrNull(1) ?: return null
        for (fmt in dateFormats) {
            try {
                return LocalDate.parse(candidate.trim(), fmt)
            } catch (_: Throwable) { /* try next */ }
        }
        return null
    }

    private fun guessCurrency(text: String): String = when {
        text.contains("₹") || text.containsAny("INR", "Rs.", "Rs ") -> "INR"
        text.contains("$") -> "USD"
        text.contains("€") -> "EUR"
        text.contains("£") -> "GBP"
        else -> "INR"
    }

    private fun guessTotal(lines: List<String>, joined: String): Double {
        val totals = listOf("total", "grand total", "amount due", "net amount", "total payable", "amount")
        val amounts = lines.mapNotNull { extractAmount(it) }
        if (amounts.isEmpty()) return 0.0

        // 1) Prefer the largest "total" line.
        lines.forEach { line ->
            val low = line.lowercase(Locale.ENGLISH)
            if (totals.any { low.contains(it) }) {
                extractAmount(line)?.let { return it }
            }
        }
        // 2) Fall back to the largest number on the receipt.
        return amounts.max()
    }

    private fun guessTax(lines: List<String>): Double? {
        val keys = listOf("tax", "gst", "vat", "cgst", "sgst", "igst")
        for (line in lines) {
            val low = line.lowercase(Locale.ENGLISH)
            if (keys.any { low.contains(it) }) {
                extractAmount(line)?.let { return it }
            }
        }
        return null
    }

    private fun guessLineItems(lines: List<String>, vendor: String): List<LineItem> {
        // Match: <text> <qty?> x <unit price> ... <line total>
        // Common pattern: "Item name 2 x 50.00 100.00"
        val pattern = Regex(
            "^(?<desc>[A-Za-z][\\w\\s/.,'&()\\-]{1,40}?)" +
                "\\s+(?:(?<qty>\\d+(?:\\.\\d+)?)\\s*[xX*])?\\s*" +
                "(?:Rs\\.?|INR|\\$|€|£)?\\s*(?<price>\\d{1,7}(?:[,.]\\d{1,2})?)" +
                "(?:\\s+(?:Rs\\.?|INR|\\$|€|£)?\\s*(?<total>\\d{1,7}(?:[,.]\\d{1,2})?))?\\s*$"
        )
        return lines.mapNotNull { line ->
            val m = pattern.find(line) ?: return@mapNotNull null
            val desc = m.groups["desc"]?.value?.trim().orEmpty()
            if (desc.length < 2) return@mapNotNull null
            if (desc.equals(vendor, ignoreCase = true)) return@mapNotNull null
            val price = parseNumber(m.groups["price"]?.value) ?: return@mapNotNull null
            val totalRaw = m.groups["total"]?.value
            val total = parseNumber(totalRaw) ?: price
            val qty = m.groups["qty"]?.value?.toDoubleOrNull() ?: 1.0
            // Reject near-equal qty+price (we caught the totals row, not a line).
            if (abs(qty - price) < 0.001 && totalRaw == null) return@mapNotNull null
            LineItem(
                description = desc,
                quantity = qty,
                unitPrice = price,
                lineTotal = total
            )
        }
    }

    private fun extractAmount(line: String): Double? {
        val regex = Regex("(?:Rs\\.?|INR|\\$|€|£)?\\s*(\\d{1,7}(?:[,.]\\d{1,2})?)")
        return regex.findAll(line)
            .mapNotNull { parseNumber(it.groupValues[1]) }
            .filter { it > 0 }
            .maxOrNull()
    }

    private fun parseNumber(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.replace(",", "")
        return cleaned.toDoubleOrNull()
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { this.contains(it, ignoreCase = true) }
}
