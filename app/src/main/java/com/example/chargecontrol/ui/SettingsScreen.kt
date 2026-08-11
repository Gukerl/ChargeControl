package com.example.chargecontrol.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.chargecontrol.SettingsRepository
import com.example.chargecontrol.isValidIpv4
import com.example.chargecontrol.isValidPort

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var evccHost by rememberSaveable { mutableStateOf(SettingsRepository.evccHost) }
    var evccPort by rememberSaveable { mutableStateOf(SettingsRepository.evccPort.toString()) }
    var goeHost by rememberSaveable { mutableStateOf(SettingsRepository.goeHost) }

    val evccHostValid = isValidIpv4(evccHost)
    val evccPortValid = isValidPort(evccPort)
    val goeHostValid = isValidIpv4(goeHost)
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
                OutlinedTextField(
                    value = goeHost,
                    onValueChange = { goeHost = it.trim() },
                    label = { Text("IP-Adresse") },
                    isError = !goeHostValid,
                    supportingText = { if (!goeHostValid) Text("Ungültige oder keine private IPv4-Adresse (10.x, 172.16-31.x, 192.168.x)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Button(
                onClick = {
                    SettingsRepository.evccHost = evccHost
                    SettingsRepository.evccPort = evccPort.toInt()
                    SettingsRepository.goeHost = goeHost
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
                }
            }
        }
    }
}
