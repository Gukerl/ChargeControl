package com.example.chargecontrol.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.chargecontrol.KeyValueStore
import com.example.chargecontrol.SettingsRepository
import com.example.chargecontrol.isValidIpv4
import com.example.chargecontrol.isValidPort
import com.example.chargecontrol.ui.theme.ChargeControlTheme

private const val DONATION_URL = "https://paypal.me/AndreasHutter?locale.x=de_DE&country.x=AT"

private val HOLD_CONFIRM_OPTIONS = listOf(
    "Aus" to 0,
    "0,2 s" to 200,
    "0,5 s" to 500,
    "1 s" to 1000,
    "2 s" to 2000
)

private fun holdConfirmLabel(ms: Int): String =
    HOLD_CONFIRM_OPTIONS.firstOrNull { it.second == ms }?.first ?: "$ms ms"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var evccHost by rememberSaveable { mutableStateOf(SettingsRepository.evccHost) }
    var evccPort by rememberSaveable { mutableStateOf(SettingsRepository.evccPort.toString()) }
    var goeEnabled by rememberSaveable { mutableStateOf(SettingsRepository.goeEnabled) }
    var goeHost by rememberSaveable { mutableStateOf(SettingsRepository.goeHost) }
    var goeTrx by rememberSaveable { mutableStateOf(SettingsRepository.goeTrx) }
    var goeTrxMenuExpanded by remember { mutableStateOf(false) }
    var goeAutoAuthorize by rememberSaveable { mutableStateOf(SettingsRepository.goeAutoAuthorize) }
    var holdConfirmMs by rememberSaveable { mutableStateOf(SettingsRepository.holdConfirmMs) }
    var holdConfirmMenuExpanded by remember { mutableStateOf(false) }

    val evccHostValid = isValidIpv4(evccHost)
    val evccPortValid = isValidPort(evccPort)
    val goeHostValid = !goeEnabled || isValidIpv4(goeHost)
    val canSave = evccHostValid && evccPortValid && goeHostValid

    val context = LocalContext.current
    val appVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("evcc", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = evccHost,
                    onValueChange = { evccHost = it.trim() },
                    label = { Text("IP-Adresse") },
                    isError = !evccHostValid,
                    supportingText = { if (!evccHostValid) Text("Ungültige oder keine private IPv4-Adresse (10.x, 172.16-31.x, 192.168.x)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = evccPort,
                    onValueChange = { evccPort = it.trim() },
                    label = { Text("Port") },
                    isError = !evccPortValid,
                    supportingText = { if (!evccPortValid) Text("Port muss zwischen 1 und 65535 liegen") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("go-e Wallbox", style = MaterialTheme.typography.titleMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(checked = goeEnabled, onCheckedChange = { goeEnabled = it })
                    Text("go-e Wallbox aktivieren")
                }

                if (goeEnabled) {
                    OutlinedTextField(
                        value = goeHost,
                        onValueChange = { goeHost = it.trim() },
                        label = { Text("IP-Adresse") },
                        isError = !goeHostValid,
                        supportingText = { if (!goeHostValid) Text("Ungültige oder keine private IPv4-Adresse (10.x, 172.16-31.x, 192.168.x)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    ExposedDropdownMenuBox(
                        expanded = goeTrxMenuExpanded,
                        onExpandedChange = { goeTrxMenuExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = goeTrx.toString(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Authentifizierung auf RFID") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = goeTrxMenuExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                        )
                        ExposedDropdownMenu(
                            expanded = goeTrxMenuExpanded,
                            onDismissRequest = { goeTrxMenuExpanded = false }
                        ) {
                            (0..9).forEach { value ->
                                DropdownMenuItem(
                                    text = { Text(value.toString()) },
                                    onClick = {
                                        goeTrx = value
                                        goeTrxMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(checked = goeAutoAuthorize, onCheckedChange = { goeAutoAuthorize = it })
                        Text("automatisch autorisieren")
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Bedienung", style = MaterialTheme.typography.titleMedium)
                ExposedDropdownMenuBox(
                    expanded = holdConfirmMenuExpanded,
                    onExpandedChange = { holdConfirmMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = holdConfirmLabel(holdConfirmMs),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Haltezeit zum Bestätigen:") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = holdConfirmMenuExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                    )
                    ExposedDropdownMenu(
                        expanded = holdConfirmMenuExpanded,
                        onDismissRequest = { holdConfirmMenuExpanded = false }
                    ) {
                        HOLD_CONFIRM_OPTIONS.forEach { (label, ms) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    holdConfirmMs = ms
                                    holdConfirmMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Button(
                onClick = {
                    SettingsRepository.evccHost = evccHost
                    SettingsRepository.evccPort = evccPort.toInt()
                    SettingsRepository.goeEnabled = goeEnabled
                    SettingsRepository.goeHost = goeHost
                    SettingsRepository.goeTrx = goeTrx
                    SettingsRepository.goeAutoAuthorize = goeAutoAuthorize
                    SettingsRepository.holdConfirmMs = holdConfirmMs
                    onBack()
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Speichern")
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Über", style = MaterialTheme.typography.titleMedium)
                    Text("ChargeControl" + (appVersion?.let { " $it" } ?: ""))
                    Text("Entwickelt von Andreas Hutter")
                    TextButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DONATION_URL)))
                        }
                    ) {
                        Text("Spenden")
                    }
                }
            }
        }
    }
}

private class PreviewKeyValueStore : KeyValueStore {
    private val strings = mutableMapOf<String, String>()
    private val ints = mutableMapOf<String, Int>()
    private val booleans = mutableMapOf<String, Boolean>()
    override fun getString(key: String, default: String) = strings[key] ?: default
    override fun putString(key: String, value: String) { strings[key] = value }
    override fun getInt(key: String, default: Int) = ints[key] ?: default
    override fun putInt(key: String, value: Int) { ints[key] = value }
    override fun getBoolean(key: String, default: Boolean) = booleans[key] ?: default
    override fun putBoolean(key: String, value: Boolean) { booleans[key] = value }
}

@Preview(showBackground = true, name = "Einstellungen")
@Composable
private fun SettingsScreenPreview() {
    SettingsRepository.initWithStore(PreviewKeyValueStore())
    ChargeControlTheme {
        SettingsScreen(onBack = {})
    }
}
