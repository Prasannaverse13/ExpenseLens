package com.expenselens.di

import android.content.Context
import androidx.room.Room
import com.expenselens.data.db.CategoryDao
import com.expenselens.data.db.ExpenseDao
import com.expenselens.data.db.ExpenseLensDatabase
import com.expenselens.data.db.LineItemDao
import com.expenselens.data.db.VendorCorrectionDao
import com.expenselens.data.prefs.AppPreferences
import com.expenselens.data.repo.ExpenseRepository
import com.expenselens.export.ExportService
import com.expenselens.extract.ExtractionPipeline
import com.expenselens.ocr.FallbackOcrEngine
import com.expenselens.ocr.MlKitOcrEngine
import com.expenselens.ocr.OcrEngine
import com.expenselens.ocr.TesseractOcrEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ExpenseLensDatabase =
        Room.databaseBuilder(context, ExpenseLensDatabase::class.java, "expense_lens.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideExpenseDao(db: ExpenseLensDatabase): ExpenseDao = db.expenses()
    @Provides fun provideLineItemDao(db: ExpenseLensDatabase): LineItemDao = db.lineItems()
    @Provides fun provideCategoryDao(db: ExpenseLensDatabase): CategoryDao = db.categories()
    @Provides fun provideVendorCorrectionDao(db: ExpenseLensDatabase): VendorCorrectionDao =
        db.vendorCorrections()

    @Provides @Singleton
    fun providePreferences(@ApplicationContext context: Context): AppPreferences =
        AppPreferences(context)

    @Provides @Singleton
    fun provideExpenseRepository(
        expenseDao: ExpenseDao,
        lineItemDao: LineItemDao,
        categoryDao: CategoryDao,
        vendorCorrectionDao: VendorCorrectionDao,
        preferences: AppPreferences
    ): ExpenseRepository = ExpenseRepository(
        expenseDao, lineItemDao, categoryDao, vendorCorrectionDao, preferences
    )

    @Provides @Singleton
    fun provideOcrEngine(@ApplicationContext context: Context): OcrEngine {
        val primary: OcrEngine = MlKitOcrEngine(context)
        val tesseract: OcrEngine = TesseractOcrEngine.fallbackFrom(primary, context)
        return FallbackOcrEngine(primary, tesseract)
    }

    @Provides @Singleton
    fun provideExtractionPipeline(
        @ApplicationContext context: Context,
        ocr: OcrEngine
    ): ExtractionPipeline = ExtractionPipeline(context, ocr)

    @Provides @Singleton
    fun provideExportService(): ExportService = ExportService()
}
