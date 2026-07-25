package com.expenselens.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenselens.data.prefs.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: AppPreferences
) : ViewModel() {

    fun save(currency: String, endpoint: String, key: String, onDone: () -> Unit) {
        viewModelScope.launch {
            prefs.setCurrency(currency.ifBlank { "INR" })
            prefs.setLlm(endpoint.trim(), key.trim())
            onDone()
        }
    }

    suspend fun snapshot(): Snapshot {
        val c = prefs.currency.first()
        val e = prefs.llmEndpoint.first()
        val k = prefs.llmKey.first()
        return Snapshot(c, e, k)
    }

    data class Snapshot(val currency: String, val endpoint: String, val key: String)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var currency by remember { mutableStateOf("INR") }
    var endpoint by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val snap = vm.snapshot()
        currency = snap.currency
        endpoint = snap.endpoint
        key = snap.key
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Currency", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = currency, onValueChange = { currency = it.uppercase().take(3) },
                label = { Text("Default currency (e.g. INR, USD)") },
                modifier = Modifier.fillMaxWidth()
            )
            Text("Optional cloud LLM (OpenAI-compatible)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Leave blank to use the on-device extractor only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = endpoint, onValueChange = { endpoint = it },
                label = { Text("Endpoint URL") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = key, onValueChange = { key = it },
                label = { Text("API key") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { vm.save(currency, endpoint, key) { onBack() } },
                enabled = loaded,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save") }
        }
    }
}
