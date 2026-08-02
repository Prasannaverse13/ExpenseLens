package com.expenselens.data.sync

import android.content.Context
import android.util.Log
import com.expenselens.data.auth.GoogleAuthManager
import com.expenselens.data.backup.BackupManager
import com.expenselens.data.prefs.AppPreferences
import com.expenselens.data.repo.ExpenseRepository
import com.expenselens.data.storage.BillStorage
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
 * Owns the "Drive is the storage, not a backup" model.
 *
 *  - [start] is called once at app launch (from [com.expenselens.MainActivity]).
 *    It subscribes to [ExpenseRepository.dataChanges], debounces bursts of
 *    saves (so capturing three bills in a row only triggers one Drive
 *    upload), and pushes the local DB to the user's Drive.
 *  - [pullOnStart] runs once at app launch too: if the user is signed in
 *    with Google, download their latest backup and replace the local
 *    cache with it. This is what makes the app "follow the user" across
 *    devices.
 *  - [state] exposes the current sync status to the UI (e.g. the
 *    "Last synced" label in the Profile screen).
 *
 * The local Room DB is always the live working copy. The Drive file is
 * the durable mirror. The user never has to tap "Sync" — it just
 * happens a few seconds after the last save.
 */
@OptIn(FlowPreview::class)
@Singleton
class SyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: GoogleAuthManager,
    private val prefs: AppPreferences,
    private val repo: ExpenseRepository,
    private val backup: BackupManager
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
                    if (auth.isConnected()) pushToDrive(reason = "auto")
                }
        }
    }

    /**
     * Pull the user's latest backup from Drive into the local cache.
     * Called from MainActivity onCreate after sign-in. Idempotent — if
     * there's nothing on Drive yet, this is a no-op.
     */
    suspend fun pullOnStart() {
        if (!auth.isConnected()) return
        val result = backup.latestBackup()
        if (result == null) {
            Log.i(TAG, "No backup on Drive yet — local DB is authoritative for now")
            return
        }
        _state.value = State.Syncing("pulling from Drive")
        when (val r = backup.restoreFromDrive(result.id)) {
            is BackupManager.SyncResult.Success -> {
                prefs.setDriveLastSync(System.currentTimeMillis(), result.id)
                _state.value = State.Ok(System.currentTimeMillis())
            }
            is BackupManager.SyncResult.Failure -> {
                _state.value = State.Error(r.reason)
            }
        }
    }

    /** Manual push (e.g. user taps "Sync now" in Settings). */
    suspend fun pushNow(): Result<Unit> {
        return if (auth.isConnected()) pushToDrive(reason = "manual")
        else Result.failure(IllegalStateException("Not signed in to Google"))
    }

    private suspend fun pushToDrive(reason: String): Result<Unit> {
        _state.value = State.Syncing(reason)
        return when (val r = backup.syncNow()) {
            is BackupManager.SyncResult.Success -> {
                prefs.setDriveLastSync(System.currentTimeMillis(), r.driveFileId)
                _state.value = State.Ok(System.currentTimeMillis())
                Result.success(Unit)
            }
            is BackupManager.SyncResult.Failure -> {
                _state.value = State.Error(r.reason)
                Result.failure(IllegalStateException(r.reason))
            }
        }
    }

    /** Called on disconnect — wipe the local cache. */
    suspend fun clearLocalCache() {
        // Delete every expense + every line item + every receipt image.
        // The user signed out of Google so their Drive is no longer
        // "their" Drive from this device's perspective; we shouldn't
        // hold a stale copy. Re-seeding categories happens automatically
        // the next time the dashboard opens.
        repo.replaceAllExpensesWithItems(emptyList(), emptyList())
        // Best-effort: also drop the bill files in app-private storage.
        val billsDir = BillStorage.billsDir(context)
        billsDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val TAG = "SyncCoordinator"
    }
}
