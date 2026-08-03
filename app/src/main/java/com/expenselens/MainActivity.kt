package com.expenselens

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.expenselens.data.billing.PaddleManager
import com.expenselens.data.sync.SyncCoordinator
import com.expenselens.ui.ExpenseLensRoot
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var syncCoordinator: SyncCoordinator
    @Inject lateinit var paddle: PaddleManager

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Surface the system splash screen so the window background is the
        // dark sage gradient color (no white flash while Compose is starting
        // or when the activity is being recreated after the OS killed it).
        val splash = installSplashScreen()
        // Catch any uncaught exception inside Compose and log it instead of
        // showing the white-screen-of-death the user has been hitting.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("ExpenseLens", "Uncaught on ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { false }
        try {
            enableEdgeToEdge()
            setContent { ExpenseLensRoot() }
        } catch (t: Throwable) {
            Log.e("ExpenseLens", "setContent failed", t)
            throw t
        }

        // Start the Supabase auto-sync coordinator. Idempotent — safe
        // to call again on activity recreation. Subscribes to repo
        // changes and pushes to Supabase after a 5s debounce.
        syncCoordinator.start()
        // One-time Drive → Supabase migration. Idempotent — no-op after
        // the first successful run.
        activityScope.launch { syncCoordinator.runMigrationIfNeeded() }
        // Try to pull the latest data from Supabase on launch. No-op if
        // not signed in to Supabase.
        activityScope.launch { syncCoordinator.pullOnStart() }

        // If we were cold-launched by the Paddle deep link, route the
        // return URI straight away.
        intent?.let { handlePaddleReturn(it) }
    }

    /**
     * Paddle's hosted checkout closes its Custom Tab and re-launches
     * our app via the `expenselens://premium-callback?...` deep link
     * declared in AndroidManifest. We can arrive here two ways:
     *  - `onNewIntent` (the app was already alive in the background)
     *  - `onCreate` (cold start, handled above)
     * Plus a `onResume` belt-and-braces check that re-reads the
     * latest intent in case the system re-delivered it.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePaddleReturn(intent)
    }

    override fun onResume() {
        super.onResume()
        intent?.let { handlePaddleReturn(it) }
    }

    private fun handlePaddleReturn(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_VIEW) return
        val uri: Uri = intent.data ?: return
        if (uri.scheme != "expenselens") return
        if (uri.host != "premium-callback") return
        // Found a Paddle return. Consume it (clear the action) so we
        // don't re-process on every config change.
        intent.action = null
        intent.data = null
        paddle.handleReturn(uri)
    }
}
