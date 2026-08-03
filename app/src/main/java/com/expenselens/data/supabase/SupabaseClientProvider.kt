package com.expenselens.data.supabase

import android.util.Log
import com.expenselens.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the singleton [SupabaseClient] used by every Supabase-touching class
 * in the app.
 *
 *  - URL and anon key are baked into BuildConfig at compile time from
 *    `local.properties` (gitignored). The anon key is a **public** key
 *    (designed to be embedded in client apps) — the real security
 *    boundary is the user's authenticated session JWT, which RLS uses
 *    to enforce `user_id = auth.uid()` isolation.
 *  - Three plugins are wired: [gotrue] for auth, [postgrest] for the
 *    six tables, [storage] for the `bills` image bucket.
 *  - The client is intentionally **not** created until both URL and
 *    anon key are present. If either is missing we return a stub that
 *    every SupabaseSync call short-circuits on, so the app still builds
 *    and runs in dev environments without Supabase configured.
 *
 * @Singleton because we want exactly one SupabaseClient per process;
 * creating multiple is wasteful and can leak auth state.
 */
@Singleton
class SupabaseClientProvider @Inject constructor() {

    val client: SupabaseClient? by lazy {
        if (!isConfigured()) {
            Log.w(TAG, "Supabase not configured (missing URL or anon key) — sync disabled")
            return@lazy null
        }
        try {
            createSupabaseClient(
                supabaseUrl = BuildConfig.SUPABASE_URL,
                supabaseKey = BuildConfig.SUPABASE_ANON_KEY
            ) {
                install(Postgrest)
                install(Storage)
                install(Auth)
            }.also {
                Log.i(TAG, "Supabase client ready: ${BuildConfig.SUPABASE_URL}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create Supabase client", t)
            null
        }
    }

    /** True iff the BuildConfig fields are both non-blank. */
    fun isConfigured(): Boolean =
        BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    /**
     * Convenience accessor that throws if Supabase is unconfigured. Use
     * this in code paths that have already gated on [isConfigured]
     * (e.g. inside `SupabaseSync.syncNow()`).
     */
    fun require(): SupabaseClient = client
        ?: throw IllegalStateException("Supabase is not configured")

    companion object {
        private const val TAG = "SupabaseClient"

        /** Storage bucket name (matches the one you created in the dashboard). */
        const val BILLS_BUCKET = "bills"
    }
}
