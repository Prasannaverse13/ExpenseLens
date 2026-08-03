package com.expenselens.data.sync

import android.content.Context
import android.util.Log
import com.expenselens.data.auth.GoogleAuthManager
import com.expenselens.data.prefs.AppPreferences
import com.expenselens.data.repo.ExpenseRepository
import com.expenselens.data.storage.BillStorage
import com.expenselens.data.supabase.MigrationManager
import com.expenselens.data.supabase.SupabaseSync
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the "Supabase is the storage, Drive is one-time migration" model.
 *
 *  - [start] is called once at app launch (from [com.expenselens.MainActivity]).
 *    It subscribes to [ExpenseRepository.dataChanges], debounces bursts of
 *    saves (so capturing three bills in a row only triggers one Supabase
 *    upload), and pushes the local DB to Supabase.
 *  - [pullOnStart] runs once at app launch too: if the user is signed in
 *    with Supabase, download their latest data and replace the local
 *    cache with it. This is what makes the app "follow the user" across
 *    devices.
 *  - [runMigrationIfNeeded] runs once at app launch too: one-time
 *    Drive → Supabase import for users coming from the v1.0 era.
 *  - [state] exposes the current sync status to the UI (e.g. the
 *    "Last synced" label in the Profile screen).
 *
 * The local Room DB is always the live working copy. Supabase is the
 * durable mirror. The user never has to tap "Sync" — it just happens
 * a few seconds after the last save.
 */
@OptIn(FlowPreview::class)
@Singleton
class SyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: GoogleAuthManager,
    private val prefs: AppPreferences,
    private val repo: ExpenseRepository,
    private val sync: SupabaseSync,
    private val migration: MigrationManager
) {

    sealed class State {
        object Idle : State()
        data class Syncing(val reason: String) : State()
        data class Error(val message: String) : State()
        data class Ok(val lastSyncMs: Long) : State()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Wire the auto-push subscription. Safe to call once at app start.
     * Returns immediately; the work runs on [scope] (an IO SupervisorJob
     * scope that outlives any single Activity / ViewModel).
     */
    fun start() {
        scope.launch {
            repo.dataChanges
                .debounce(5_000L) // 5s grace — burst saves collapse to one push
                .distinctUntilChanged()
                .collect {
                    if (auth.isSupabaseReady()) pushToSupabase(reason = "auto")
                }
        }
    }

    /**
     * Pull the user's latest data from Supabase into the local cache.
     * Called from MainActivity onCreate after sign-in. Idempotent —
     * if the user has no Supabase data yet, this is a no-op.
     */
    suspend fun pullOnStart() {
        if (!auth.isSupabaseReady()) return
        _state.value = State.Syncing("pulling from Supabase")
        when (val r = sync.pullOnStart()) {
            is SupabaseSync.SyncResult.Success -> {
                prefs.setSupabaseLastSync(System.currentTimeMillis())
                _state.value = State.Ok(System.currentTimeMillis())
            }
            is SupabaseSync.SyncResult.Failure -> {
                _state.value = State.Error(r.reason)
            }
            SupabaseSync.SyncResult.NotConfigured,
            SupabaseSync.SyncResult.NotSignedIn -> {
                Log.i(TAG, "Pull skipped: Supabase not ready")
                _state.value = State.Idle
            }
        }
    }

    /**
     * One-time Drive → Supabase migration. Runs the first time after the
     * v1.1 upgrade. Idempotent — no-op after the first run.
     */
    suspend fun runMigrationIfNeeded() {
        try {
            when (val r = migration.migrateIfNeeded()) {
                MigrationManager.MigrationOutcome.AlreadyMigrated -> Unit
                MigrationManager.MigrationOutcome.NotSignedIn,
                MigrationManager.MigrationOutcome.SupabaseNotConfigured -> {
                    Log.i(TAG, "Migration skipped: ${r::class.simpleName}")
                }
                is MigrationManager.MigrationOutcome.Done -> {
                    Log.i(TAG, "Migration done: ${r.rowsWritten} rows")
                }
                is MigrationManager.MigrationOutcome.PushFailed -> {
                    Log.w(TAG, "Migration push failed (will retry): ${r.reason}")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Migration threw", t)
        }
    }

    /** Manual push (e.g. user taps "Sync now" in Settings). */
    suspend fun pushNow(): Result<Unit> {
        return if (auth.isSupabaseReady()) pushToSupabase(reason = "manual")
        else Result.failure(IllegalStateException("Not signed in to Supabase"))
    }

    private suspend fun pushToSupabase(reason: String): Result<Unit> {
        _state.value = State.Syncing(reason)
        return when (val r = sync.pushNow()) {
            is SupabaseSync.SyncResult.Success -> {
                prefs.setSupabaseLastSync(System.currentTimeMillis())
                _state.value = State.Ok(System.currentTimeMillis())
                Result.success(Unit)
            }
            is SupabaseSync.SyncResult.Failure -> {
                _state.value = State.Error(r.reason)
                Result.failure(IllegalStateException(r.reason))
            }
            SupabaseSync.SyncResult.NotConfigured,
            SupabaseSync.SyncResult.NotSignedIn -> {
                _state.value = State.Idle
                Result.failure(IllegalStateException("Supabase not ready"))
            }
        }
    }

    /** Called on disconnect — wipe the local cache. */
    suspend fun clearLocalCache() {
        // Delete every expense + every line item + every receipt image.
        // The user signed out of Google so we shouldn't hold a stale copy.
        // Re-seeding categories happens automatically next dashboard open.
        repo.replaceAllExpensesWithItems(emptyList(), emptyList())
        val billsDir = BillStorage.billsDir(context)
        billsDir.listFiles()?.forEach { it.delete() }
        prefs.setPremium(false)
    }

    companion object {
        private const val TAG = "SyncCoordinator"
    }
}
