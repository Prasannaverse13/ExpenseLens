package com.expenselens.data.supabase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// These mirror the six Postgres tables defined in the schema. Field
// names match the snake_case columns — kotlinx-serialization does the
// mapping automatically (no @SerialName needed when the property name
// matches the column name after lowercase-with-underscores, but we
// add explicit @SerialName for clarity and to protect against KSP
// case-sensitivity edge cases).

@Serializable
data class BillRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    val vendor: String,
    @SerialName("bill_number") val billNumber: String? = null,
    @SerialName("bill_date") val billDate: String,                 // ISO date
    @SerialName("total_cents") val totalCents: Long,
    @SerialName("tax_cents") val taxCents: Long? = null,
    val currency: String = "INR",
    @SerialName("payment_method") val paymentMethod: String = "Cash",
    val notes: String? = null,
    @SerialName("image_path") val imagePath: String? = null,
    @SerialName("ai_extracted") val aiExtracted: Boolean = false,
    val confidence: Float = 0f,
    @SerialName("needs_review") val needsReview: Boolean = false,
    @SerialName("ocr_text") val ocrText: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class LineItemRow(
    val id: String,
    @SerialName("bill_id") val billId: String,
    val description: String,
    val quantity: Float = 1f,
    @SerialName("unit_cents") val unitCents: Long,
    @SerialName("total_cents") val totalCents: Long,
    val category: String? = null,
    @SerialName("category_confidence") val categoryConfidence: Float = 0f,
)

@Serializable
data class CategoryRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    val color: String? = null,
    val icon: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class VendorCorrectionRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("vendor_key") val vendorKey: String,
    val category: String,
    @SerialName("hit_count") val hitCount: Int = 1,
)

@Serializable
data class PremiumRow(
    @SerialName("user_id") val userId: String,
    @SerialName("is_premium") val isPremium: Boolean = false,
    @SerialName("paddle_subscription_id") val paddleSubscriptionId: String? = null,
    @SerialName("paddle_customer_id") val paddleCustomerId: String? = null,
    @SerialName("current_period_end") val currentPeriodEnd: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class UsageCounterRow(
    @SerialName("user_id") val userId: String,
    val month: String,                     // e.g. "2026-08"
    @SerialName("ai_calls") val aiCalls: Int = 0,
)
