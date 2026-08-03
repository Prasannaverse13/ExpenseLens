package com.expenselens.data.supabase

import android.content.Context
import android.util.Log
import com.expenselens.data.auth.SupabaseAuthStore
import com.expenselens.data.backup.BackupManager
import com.expenselens.data.prefs.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time Drive → Supabase migration. Runs the first time the user
 * opens the app after the Supabase upgrade, IF:
 *   - `hasMigratedToSupabase` flag is false
 *   - A Drive backup exists (old format)
 *   - The user is signed in to Supabase
 *
 * The flow:
 *   1. Read the existing Drive backup JSON via [BackupManager] (still
 *      wired — we keep it for one more cycle as a one-shot reader)
 *   2. Restore everything to the local Room DB (existing logic)
 *   3. Push the local DB up to Supabase via [SupabaseSync.pushNow]
 *   4. Set `hasMigratedToSupabase = true`
 *
 * After this runs once, the Drive permission can be safely dropped
 * from AndroidManifest in a future release. Until then Drive is read-
 * only (no further auto-push, no manual push).
 */
@Singleton
class MigrationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
    private val auth: SupabaseAuthStore,
    private val backup: BackupManager,
    private val sync: SupabaseSync
) {

    suspend fun migrateIfNeeded(): MigrationOutcome = withContext(Dispatchers.IO) {
        if (prefs.hasMigratedToSupabase.first()) {
            return@withContext MigrationOutcome.AlreadyMigrated
        }
        if (!auth.isSignedIn()) {
            return@withContext MigrationOutcome.NotSignedIn
        }
        // Pull the existing Drive backup (if any) into the local DB.
        val driveFile = try {
            backup.latestBackup()
        } catch (t: Throwable) {
            Log.w(TAG, "Drive read failed during migration: ${t.message}")
            null
        }
        if (driveFile != null) {
            Log.i(TAG, "Migrating from Drive file: ${driveFile.id}")
            when (val r = backup.restoreFromDrive(driveFile.id)) {
                is BackupManager.SyncResult.Failure -> {
                    Log.w(TAG, "Drive restore failed: ${r.reason} — proceeding with local-only push")
                }
                is BackupManager.SyncResult.Success -> {
                    Log.i(TAG, "Drive restore ok (${r.bytes} bytes)")
                }
            }
        } else {
            Log.i(TAG, "No Drive backup — pushing whatever's in the local DB")
        }
        // Push everything to Supabase.
        val pushResult = sync.pushNow()
        return@withContext when (pushResult) {
            is SupabaseSync.SyncResult.Success -> {
                prefs.setMigratedToSupabase(true)
                Log.i(TAG, "Migration push succeeded: ${pushResult.rowsWritten} rows")
                MigrationOutcome.Done(pushResult.rowsWritten)
            }
            is SupabaseSync.SyncResult.Failure -> {
                Log.w(TAG, "Migration push failed: ${pushResult.reason} — will retry next launch")
                MigrationOutcome.PushFailed(pushResult.reason)
            }
            SupabaseSync.SyncResult.NotConfigured -> MigrationOutcome.SupabaseNotConfigured
            SupabaseSync.SyncResult.NotSignedIn -> MigrationOutcome.NotSignedIn
        }
    }

    sealed class MigrationOutcome {
        data object AlreadyMigrated : MigrationOutcome()
        data object NotSignedIn : MigrationOutcome()
        data object SupabaseNotConfigured : MigrationOutcome()
        data class PushFailed(val reason: String) : MigrationOutcome()
        data class Done(val rowsWritten: Int) : MigrationOutcome()
    }

    companion object {
        private const val TAG = "MigrationManager"
    }
}
