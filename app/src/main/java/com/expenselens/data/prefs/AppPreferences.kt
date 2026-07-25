package com.expenselens.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.expenselens.domain.model.PaymentMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("expense_lens_prefs")

class AppPreferences(private val context: Context) {

    private val keyCurrency = stringPreferencesKey("currency")
    private val keyPayment = stringPreferencesKey("default_payment")
    private val keyLlmEndpoint = stringPreferencesKey("llm_endpoint")
    private val keyLlmKey = stringPreferencesKey("llm_key")

    val currency: Flow<String> = context.dataStore.data.map { it[keyCurrency] ?: "INR" }
    val defaultPayment: Flow<PaymentMethod> =
        context.dataStore.data.map { PaymentMethod.fromName(it[keyPayment]) }
    val llmEndpoint: Flow<String> = context.dataStore.data.map { it[keyLlmEndpoint] ?: "" }
    val llmKey: Flow<String> = context.dataStore.data.map { it[keyLlmKey] ?: "" }

    suspend fun snapshot(): Preferences = context.dataStore.data.first()

    suspend fun setCurrency(value: String) = context.dataStore.edit { it[keyCurrency] = value }
    suspend fun setDefaultPayment(value: PaymentMethod) =
        context.dataStore.edit { it[keyPayment] = value.displayName }
    suspend fun setLlm(endpoint: String, key: String) =
        context.dataStore.edit {
            it[keyLlmEndpoint] = endpoint
            it[keyLlmKey] = key
        }
}
