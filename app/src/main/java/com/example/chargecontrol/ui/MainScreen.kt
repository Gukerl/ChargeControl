package com.example.chargecontrol.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun MainScreen(
    uiState: UiState,
    onModeSelected: (String) -> Unit,
    onStop: () -> Unit,
    onErrorShown: () -> Unit,
    onPhasesSelected: (Int) -> Unit,
    onMinCurrentChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            onErrorShown()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            StatusCard(uiState)

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SelectableButton(
                    "PV-Überschuss (minpv)",
                    isActive = uiState.mode == "minpv",
                    modifier = Modifier.fillMaxWidth()
                ) { onModeSelected("minpv") }
                SelectableButton(
                    "Nur PV (pv)",
                    isActive = uiState.mode == "pv",
                    modifier = Modifier.fillMaxWidth()
                ) { onModeSelected("pv") }
                SelectableButton(
                    "Sofortladen (now)",
                    isActive = uiState.mode == "now",
                    modifier = Modifier.fillMaxWidth()
                ) { onModeSelected("now") }
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text("Laden stoppen")
                }
            }

            AdvancedSettings(
                uiState = uiState,
                onPhasesSelected = onPhasesSelected,
                onMinCurrentChanged = onMinCurrentChanged
            )
        }
    }
}

@Composable
private fun StatusCard(uiState: UiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (uiState.isLoading) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text("Lade Status …")
                }
            } else if (!uiState.hasData) {
                Text("Kein Status verfügbar")
            } else {
                Text("Modus: ${uiState.mode}")
                Text("Phasen: ${phasesLabel(uiState.phasesConfigured)}")
                Text("Ladestrom: ${uiState.offeredCurrent.roundToInt()} A")
                Text(
                    when {
                        uiState.charging -> "Lädt gerade"
                        uiState.connected -> "Verbunden, lädt nicht"
                        else -> "Nicht verbunden"
                    }
                )
            }
        }
    }
}

@Composable
private fun AdvancedSettings(
    uiState: UiState,
    onPhasesSelected: (Int) -> Unit,
    onMinCurrentChanged: (Int) -> Unit
) {
    var unlocked by rememberSaveable { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Int?>(null) }
    val sliderValue = pending ?: uiState.minCurrent

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Checkbox(checked = unlocked, onCheckedChange = { unlocked = it })
            Text("Erweiterte Einstellungen entsperren")
        }

        Text("Phasen", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SelectableButton(
                "Automatisch",
                isActive = uiState.phasesConfigured == 0,
                enabled = unlocked,
                modifier = Modifier.weight(1f)
            ) { onPhasesSelected(0) }
            SelectableButton(
                "1-phasig",
                isActive = uiState.phasesConfigured == 1,
                enabled = unlocked,
                modifier = Modifier.weight(1f)
            ) { onPhasesSelected(1) }
            SelectableButton(
                "3-phasig",
                isActive = uiState.phasesConfigured == 3,
                enabled = unlocked,
                modifier = Modifier.weight(1f)
            ) { onPhasesSelected(3) }
        }

        Text("Mindest-Ladestrom: $sliderValue A", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = sliderValue.toFloat(),
            onValueChange = { pending = it.roundToInt() },
            onValueChangeFinished = {
                pending?.let(onMinCurrentChanged)
                pending = null
            },
            valueRange = 6f..16f,
            steps = 9,
            enabled = unlocked
        )
    }
}

@Composable
private fun SelectableButton(
    label: String,
    isActive: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        enabled = enabled && !isActive
    ) {
        Text(label)
    }
}

private fun phasesLabel(phasesConfigured: Int): String = when (phasesConfigured) {
    1 -> "1-phasig"
    3 -> "3-phasig"
    else -> "Automatisch"
}
