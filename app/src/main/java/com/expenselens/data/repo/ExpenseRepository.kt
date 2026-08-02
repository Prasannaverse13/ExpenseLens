package com.expenselens.data.repo

import com.expenselens.categorize.KeywordCategoryClassifier
import com.expenselens.data.db.CategoryDao
import com.expenselens.data.db.CategoryEntity
import com.expenselens.data.db.ExpenseDao
import com.expenselens.data.db.ExpenseEntity
import com.expenselens.data.db.ExpenseLensDatabase
import com.expenselens.data.db.ExpenseMetadataDao
import com.expenselens.data.db.ExpenseMetadataEntity
import com.expenselens.data.db.ExpenseWithLineItems
import com.expenselens.data.db.LineItemDao
import com.expenselens.data.db.LineItemEntity
import com.expenselens.data.db.VendorCorrectionDao
import com.expenselens.data.db.VendorCorrectionEntity
import com.expenselens.data.prefs.AppPreferences
import com.expenselens.domain.model.CategoryType
import com.expenselens.domain.model.Expense
import com.expenselens.domain.model.LineItem
import com.expenselens.domain.model.PaymentMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime

class ExpenseRepository(
    private val expenseDao: ExpenseDao,
    private val lineItemDao: LineItemDao,
    private val categoryDao: CategoryDao,
    private val vendorCorrectionDao: VendorCorrectionDao,
    private val metadataDao: ExpenseMetadataDao? = null,
    private val preferences: AppPreferences
) {

    private val classifier = KeywordCategoryClassifier()

    /**
     * Emits on every write to the expense / line_item / category /
     * vendor_correction tables. The [com.expenselens.data.sync.SyncCoordinator]
     * subscribes to this, debounces a few seconds, then pushes the full
     * state of the local DB to the user's Google Drive. The local DB is
     * the live working copy; Drive is the durable mirror.
     */
    private val _dataChanges = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 16
    )
    val dataChanges: SharedFlow<Unit> = _dataChanges.asSharedFlow()

    private suspend fun notifyChanged() {
        _dataChanges.tryEmit(Unit)
    }

    suspend fun seedCategoriesIfEmpty() = withContext(Dispatchers.IO) {
        if (categoryDao.getAll().isEmpty()) {
            val items = CategoryType.seedList.map { CategoryEntity(name = it.displayName) }
            categoryDao.insertAll(items)
        }
    }

    /**
     * Back-fill any seed categories that don't yet exist in the DB. Runs on
     * every app launch so newly-added categories (e.g. Maintenance) appear
     * for existing installs without forcing a destructive migration.
     */
    suspend fun seedMissingCategories() = withContext(Dispatchers.IO) {
        val existing = categoryDao.getAll().map { it.name.lowercase() }.toSet()
        val missing = CategoryType.seedList
            .filter { it.displayName.lowercase() !in existing }
            .map { CategoryEntity(name = it.displayName) }
        if (missing.isNotEmpty()) categoryDao.insertAll(missing)
    }

    suspend fun categories(): List<CategoryEntity> = withContext(Dispatchers.IO) {
        categoryDao.getAll()
    }

    fun categoryFlow(): Flow<List<CategoryEntity>> = categoryDao.observeAll()

    suspend fun categoryByName(name: String): CategoryEntity? =
        withContext(Dispatchers.IO) { categoryDao.byName(name) }

    suspend fun categoryIdFor(name: String): Long? =
        withContext(Dispatchers.IO) { categoryDao.byName(name)?.id }

    suspend fun save(expense: Expense): Long = withContext(Dispatchers.IO) {
        val allCategories = categories()
        val fallbackCategoryId = allCategories.firstOrNull()?.id ?: 1L
        val resolvedCategoryId = categoryIdFor(majorCategoryName(expense)) ?: fallbackCategoryId
        val now = LocalDateTime.now()
        val entity = ExpenseEntity(
            id = expense.id,
            vendor = expense.vendor,
            billNumber = expense.billNumber,
            billDate = expense.billDate,
            totalAmount = expense.totalAmount,
            taxAmount = expense.taxAmount,
            currency = expense.currency,
            paymentMethod = expense.paymentMethod.displayName,
            notes = expense.notes,
            createdAt = expense.createdAt,
            updatedAt = now,
            confidence = expense.confidence,
            needsReview = expense.needsReview,
            categoryId = resolvedCategoryId,
            billFileUri = expense.billFileUri,
            billMime = expense.billMime,
            ocrText = expense.ocrText
        )
        val id = if (entity.id == 0L) expenseDao.insertExpense(entity)
        else { expenseDao.updateExpense(entity); entity.id }
        val items = expense.lineItems.mapIndexed { idx, li ->
            val catId = categoryIdFor(li.category.displayName) ?: resolvedCategoryId
            LineItemEntity(
                id = 0L,
                expenseId = id,
                description = li.description.ifBlank { "Item ${idx + 1}" },
                quantity = li.quantity,
                unitPrice = li.unitPrice,
                lineTotal = li.lineTotal,
                categoryId = catId,
                categoryConfidence = li.categoryConfidence
            )
        }
        expenseDao.replaceLineItems(id, items)
        // Learn from the user's final categorization.
        recordVendorCorrection(expense.vendor, majorCategoryName(expense))
        // Persist any AI-extracted extras alongside the expense.
        expense.metadata?.let { meta ->
            metadataDao?.upsert(
                ExpenseMetadataEntity(
                    expenseId = id,
                    merchantPhones = meta.merchantPhone.joinToString(",").ifBlank { null },
                    merchantEmail = meta.merchantEmail,
                    fssaiNumber = meta.fssaiNumber,
                    visitTime = meta.visitTime,
                    itemCount = meta.itemCount,
                    source = meta.source
                )
            )
        }
        // Notify the Drive sync coordinator. The actual push is debounced
        // (5s) so a burst of saves only triggers one Drive upload.
        notifyChanged()
        id
    }

    fun observeAll(): Flow<List<ExpenseEntity>> = expenseDao.observeAll()
    fun observeAllWithItems(): Flow<List<ExpenseWithLineItems>> = expenseDao.observeAllWithItems()
    fun observeTotalForDate(date: LocalDate): Flow<Double> = expenseDao.observeTotalForDate(date)
    fun observeCategoryTotals(from: LocalDate, to: LocalDate) =
        expenseDao.observeCategoryTotals(from, to)
    fun observeDailyTotals(from: LocalDate, to: LocalDate) =
        expenseDao.observeDailyTotals(from, to)

    suspend fun vendorCorrections(): List<VendorCorrectionEntity> = withContext(Dispatchers.IO) {
        vendorCorrectionDao.getAll()
    }

    /**
     * Drop + re-insert the full category table. Used by the Drive restore
     * path; not a user-facing operation.
     */
    suspend fun replaceAllCategories(items: List<CategoryEntity>) = withContext(Dispatchers.IO) {
        categoryDao.deleteAll()
        if (items.isNotEmpty()) categoryDao.replaceAll(items)
        notifyChanged()
    }

    /**
     * Drop + re-insert every expense and its line items. [expenses] is
     * the list of rows to write; [bundles] pairs each expense with its
     * line items (lineItem.expenseId is patched in after insert).
     */
    suspend fun replaceAllExpensesWithItems(
        expenses: List<ExpenseEntity>,
        bundles: List<Pair<ExpenseEntity, List<LineItemEntity>>>
    ) = withContext(Dispatchers.IO) {
        expenseDao.deleteAllLineItems()
        expenseDao.deleteAllExpenses()
        for (e in expenses) {
            val newId = expenseDao.insertExpense(e)
            val items = bundles.firstOrNull { it.first === e || it.first.id == e.id }?.second ?: emptyList()
            if (items.isNotEmpty()) {
                val patched = items.map { it.copy(expenseId = newId) }
                expenseDao.insertLineItems(patched)
            }
        }
        // No notifyChanged() here — this is the *restore* path, callers
        // don't want it to immediately push back the same data we just
        // downloaded.
    }

    suspend fun replaceAllVendorCorrections(items: List<VendorCorrectionEntity>) = withContext(Dispatchers.IO) {
        vendorCorrectionDao.deleteAll()
        if (items.isNotEmpty()) vendorCorrectionDao.insertAll(items)
        notifyChanged()
    }

    fun search(
        from: LocalDate?, to: LocalDate?, vendor: String,
        minAmount: Double?, maxAmount: Double?, categoryId: Long?
    ): Flow<List<ExpenseEntity>> =
        expenseDao.search(from, to, vendor, minAmount, maxAmount, categoryId)

    suspend fun byIdWithItems(id: Long): ExpenseWithLineItems? = withContext(Dispatchers.IO) {
        expenseDao.byIdWithItems(id)
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        expenseDao.delete(id)
        notifyChanged()
    }

    suspend fun recordVendorCorrection(vendor: String, categoryName: String) =
        withContext(Dispatchers.IO) {
            val key = vendor.lowercase().trim().take(80)
            if (key.isBlank()) return@withContext
            val catId = categoryIdFor(categoryName) ?: return@withContext
            val existing = vendorCorrectionDao.bestFor(key)
            if (existing != null && existing.categoryId == catId) {
                vendorCorrectionDao.bump(existing.id, System.currentTimeMillis())
            } else {
                vendorCorrectionDao.upsert(
                    VendorCorrectionEntity(
                        vendorKey = key, categoryId = catId
                    )
                )
            }
        }

    suspend fun suggestCategoryFor(vendor: String): CategoryType? = withContext(Dispatchers.IO) {
        val key = vendor.lowercase().trim().take(80)
        if (key.isBlank()) return@withContext null
        val match = vendorCorrectionDao.bestFor(key) ?: return@withContext null
        categoryDao.byId(match.categoryId)?.name?.let { CategoryType.fromName(it) }
    }

    suspend fun toDomain(entity: ExpenseWithLineItems): Expense {
        val cat = categoryDao.byId(entity.expense.categoryId)
        val category = CategoryType.fromName(cat?.name)
        return Expense(
            id = entity.expense.id,
            vendor = entity.expense.vendor,
            billNumber = entity.expense.billNumber,
            billDate = entity.expense.billDate,
            totalAmount = entity.expense.totalAmount,
            taxAmount = entity.expense.taxAmount,
            currency = entity.expense.currency,
            paymentMethod = PaymentMethod.fromName(entity.expense.paymentMethod),
            notes = entity.expense.notes,
            createdAt = entity.expense.createdAt,
            updatedAt = entity.expense.updatedAt,
            confidence = entity.expense.confidence,
            needsReview = entity.expense.needsReview,
            billFileUri = entity.expense.billFileUri,
            billMime = entity.expense.billMime,
            ocrText = entity.expense.ocrText,
            lineItems = entity.lineItems.map { li ->
                val liCat = categoryDao.byId(li.categoryId)
                LineItem(
                    id = li.id, expenseId = li.expenseId,
                    description = li.description,
                    quantity = li.quantity, unitPrice = li.unitPrice, lineTotal = li.lineTotal,
                    category = CategoryType.fromName(liCat?.name),
                    categoryConfidence = li.categoryConfidence
                )
            },
            metadata = metadataDao?.forExpense(entity.expense.id)?.toMetadata()
        )
    }

    private fun ExpenseMetadataEntity.toMetadata(): com.expenselens.domain.model.ExpenseMetadata =
        com.expenselens.domain.model.ExpenseMetadata(
            merchantPhone = merchantPhones
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList(),
            merchantEmail = merchantEmail,
            fssaiNumber = fssaiNumber,
            visitTime = visitTime,
            itemCount = itemCount,
            source = source
        )

    private fun majorCategoryName(expense: Expense): String {
        // If any line items exist, use the most common category. Otherwise, use the
        // category the classifier suggested.
        if (expense.lineItems.isNotEmpty()) {
            val grouped = expense.lineItems.groupingBy { it.category }.eachCount()
            return grouped.maxBy { it.value }.key.displayName
        }
        return expense.lineItems.firstOrNull()?.category?.displayName
            ?: CategoryType.MISCELLANEOUS.displayName
    }

    suspend fun defaultCurrency(): String = preferences.currency.first()
    suspend fun defaultPayment(): PaymentMethod = preferences.defaultPayment.first()
}
