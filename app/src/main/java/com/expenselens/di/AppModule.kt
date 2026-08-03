package com.expenselens.di

import android.content.Context
import androidx.room.Room
import com.expenselens.BuildConfig
import com.expenselens.data.db.CategoryDao
import com.expenselens.data.db.ExpenseDao
import com.expenselens.data.db.ExpenseLensDatabase
import com.expenselens.data.db.ExpenseMetadataDao
import com.expenselens.data.db.LineItemDao
import com.expenselens.data.db.VendorCorrectionDao
import com.expenselens.data.prefs.AppPreferences
import com.expenselens.data.repo.ExpenseRepository
import com.expenselens.export.ExportService
import com.expenselens.extract.ExtractionPipeline
import com.expenselens.extract.LlmExtractor
import com.expenselens.ocr.FallbackOcrEngine
import com.expenselens.ocr.MlKitOcrEngine
import com.expenselens.ocr.OcrEngine
import com.expenselens.ocr.TesseractOcrEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ExpenseLensDatabase =
        Room.databaseBuilder(context, ExpenseLensDatabase::class.java, "expense_lens.db")
            // DB is currently at v3 (added expense_metadata in v2, then
            // merchantPhones in v3). Hard-reset on any future schema change
            // so dev/test users don't get stuck.
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideExpenseDao(db: ExpenseLensDatabase): ExpenseDao = db.expenses()
    @Provides fun provideLineItemDao(db: ExpenseLensDatabase): LineItemDao = db.lineItems()
    @Provides fun provideCategoryDao(db: ExpenseLensDatabase): CategoryDao = db.categories()
    @Provides fun provideVendorCorrectionDao(db: ExpenseLensDatabase): VendorCorrectionDao =
        db.vendorCorrections()
    @Provides fun provideExpenseMetadataDao(db: ExpenseLensDatabase): ExpenseMetadataDao =
        db.expenseMetadata()

    @Provides @Singleton
    fun providePreferences(@ApplicationContext context: Context): AppPreferences =
        AppPreferences(context)

    @Provides @Singleton
    fun provideExpenseRepository(
        expenseDao: ExpenseDao,
        lineItemDao: LineItemDao,
        categoryDao: CategoryDao,
        vendorCorrectionDao: VendorCorrectionDao,
        metadataDao: ExpenseMetadataDao,
        preferences: AppPreferences
    ): ExpenseRepository = ExpenseRepository(
        expenseDao, lineItemDao, categoryDao, vendorCorrectionDao, metadataDao, preferences
    )

    @Provides @Singleton
    fun provideOcrEngine(@ApplicationContext context: Context): OcrEngine {
        val primary: OcrEngine = MlKitOcrEngine(context)
        val tesseract: OcrEngine = TesseractOcrEngine.fallbackFrom(primary, context)
        return FallbackOcrEngine(primary, tesseract)
    }

    @Provides @Singleton
    fun provideLlmExtractor(
        @ApplicationContext context: Context
    ): LlmExtractor? {
        // BuildConfig is populated from local.properties at build time. If the
        // developer hasn't set a key we still ship the app — the smart
        // extraction just falls back to the on-device parser.
        val key = BuildConfig.OPENAI_API_KEY
        val model = BuildConfig.OPENAI_MODEL.ifBlank { "gpt-4o" }
        if (key.isBlank()) return null
        return LlmExtractor(
            context = context,
            endpoint = "https://api.openai.com/v1/chat/completions",
            apiKey = key,
            model = model
        )
    }

    @Provides @Singleton
    fun provideExtractionPipeline(
        @ApplicationContext context: Context,
        ocr: OcrEngine,
        llm: LlmExtractor?,
        preferences: AppPreferences
    ): ExtractionPipeline = ExtractionPipeline(
        context = context,
        ocr = ocr,
        llm = llm,
        preferences = preferences,
        // Read premium state from DataStore on every call so mid-session
        // upgrades take effect immediately.
        isPremium = { kotlinx.coroutines.runBlocking { preferences.isPremium.first() } }
    )

    @Provides @Singleton
    fun provideExportService(): ExportService = ExportService()

    // ── Google Drive backup plumbing (kept read-only for the one-time
    //     Drive → Supabase migration; auto-push is disabled) ────────
    @Provides @Singleton
    fun provideTokenStore(@ApplicationContext context: Context): com.expenselens.data.auth.TokenStore =
        com.expenselens.data.auth.TokenStore(context)

    @Provides @Singleton
    fun provideSupabaseAuthStore(
        @ApplicationContext context: Context
    ): com.expenselens.data.auth.SupabaseAuthStore =
        com.expenselens.data.auth.SupabaseAuthStore(context)

    @Provides @Singleton
    fun provideGoogleAuthManager(
        @ApplicationContext context: Context,
        tokenStore: com.expenselens.data.auth.TokenStore,
        supabaseAuth: com.expenselens.data.auth.SupabaseAuthStore,
        supabase: com.expenselens.data.supabase.SupabaseClientProvider
    ): com.expenselens.data.auth.GoogleAuthManager =
        com.expenselens.data.auth.GoogleAuthManager(
            context, tokenStore, supabaseAuth, supabase
        )

    @Provides @Singleton
    fun provideGoogleDriveManager(
        auth: com.expenselens.data.auth.GoogleAuthManager
    ): com.expenselens.data.drive.GoogleDriveManager =
        com.expenselens.data.drive.GoogleDriveManager(auth)

    @Provides @Singleton
    fun provideBackupManager(
        @ApplicationContext context: Context,
        repo: ExpenseRepository,
        drive: com.expenselens.data.drive.GoogleDriveManager,
        prefs: AppPreferences
    ): com.expenselens.data.backup.BackupManager =
        com.expenselens.data.backup.BackupManager(context, repo, drive, prefs)

    // ── Supabase data layer (replaces Drive for daily sync) ──────────
    @Provides @Singleton
    fun provideSupabaseClient(): com.expenselens.data.supabase.SupabaseClientProvider =
        com.expenselens.data.supabase.SupabaseClientProvider()

    @Provides @Singleton
    fun provideSupabaseSync(
        @ApplicationContext context: Context,
        client: com.expenselens.data.supabase.SupabaseClientProvider,
        auth: com.expenselens.data.auth.SupabaseAuthStore,
        repo: ExpenseRepository,
        prefs: AppPreferences
    ): com.expenselens.data.supabase.SupabaseSync =
        com.expenselens.data.supabase.SupabaseSync(context, client, auth, repo, prefs)

    @Provides @Singleton
    fun provideSignInThrottle(
        @ApplicationContext context: Context
    ): com.expenselens.data.auth.SignInThrottle =
        com.expenselens.data.auth.SignInThrottle(context)

    // ── Paddle Premium billing ──────────────────────────────────────────
    // PaddleManager is @Singleton with an @Inject constructor, so Hilt
    // creates it automatically — no @Provides needed. MainActivity and
    // SettingsViewModel inject it directly.

    // SyncCoordinator is a @Singleton with an @Inject constructor, so
    // Hilt creates it automatically — no @Provides needed. MainActivity
    // injects it and calls start() in onCreate.
}
