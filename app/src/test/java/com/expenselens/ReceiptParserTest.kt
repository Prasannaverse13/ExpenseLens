package com.expenselens

import com.expenselens.categorize.KeywordCategoryClassifier
import com.expenselens.domain.model.CategoryType
import com.expenselens.extract.ReceiptParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReceiptParserTest {

    private val parser = ReceiptParser(KeywordCategoryClassifier())

    @Test fun `parses vendor, total and date`() {
        val text = """
            Daily Fresh Mart
            12 MG Road, Bengaluru
            Bill no: INV-99821
            Date: 18/07/2026
            Milk 1 L        2 x 28.00    56.00
            Bread           1 x 45.00    45.00
            Sugar 1kg       1 x 55.00    55.00
            Subtotal                       156.00
            GST 5%                          7.80
            Grand Total                   163.80
        """.trimIndent()
        val r = parser.parse(text)
        assertEquals("Daily Fresh Mart", r.vendor)
        assertEquals("INV-99821", r.billNumber)
        assertEquals(LocalDate.of(2026, 7, 18), r.billDate)
        assertEquals(163.80, r.totalAmount, 0.001)
        assertEquals(7.80, r.taxAmount!!, 0.001)
        assertTrue(r.lineItems.isNotEmpty())
    }

    @Test fun `classifies milk into food cost`() {
        val (cat, conf) = KeywordCategoryClassifier()
            .classify("Milk 1 L", "Daily Fresh Mart")
        assertEquals(CategoryType.FOOD_COST, cat)
        assertTrue(conf > 0.5f)
    }

    @Test fun `classifies paper bag into packaging cost`() {
        val (cat, _) = KeywordCategoryClassifier()
            .classify("Paper bag 12x10", "Local store")
        assertEquals(CategoryType.PACKAGING_COST, cat)
    }

    @Test fun `classifies electricity bill into electricity`() {
        val (cat, _) = KeywordCategoryClassifier()
            .classify("EB bill 220 units", "BESCOM")
        assertEquals(CategoryType.ELECTRICITY, cat)
    }

    @Test fun `classifies staff salary into staff salary`() {
        val (cat, _) = KeywordCategoryClassifier()
            .classify("Salary advance", "")
        assertEquals(CategoryType.STAFF_SALARY, cat)
    }

    @Test fun `classifies shop rent into shop rent`() {
        val (cat, _) = KeywordCategoryClassifier()
            .classify("Shop rent July", "")
        assertEquals(CategoryType.SHOP_RENT, cat)
    }

    @Test fun `falls back to miscellaneous for unknown items`() {
        val (cat, conf) = KeywordCategoryClassifier()
            .classify("Mystery widget", "")
        assertEquals(CategoryType.MISCELLANEOUS, cat)
        assertTrue(conf < 0.5f)
    }

    @Test fun `parses indian rupee totals`() {
        val r = parser.parse("""
            Chai Point
            Date: 18-07-2026
            Masala Chai 2 x 30.00 60.00
            Total Rs. 60.00
        """.trimIndent())
        assertNotNull(r)
        assertEquals(60.0, r.totalAmount, 0.001)
    }
}
