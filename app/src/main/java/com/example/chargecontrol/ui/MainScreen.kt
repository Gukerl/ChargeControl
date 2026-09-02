package com.example.chargecontrol.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.chargecontrol.ui.theme.ChargeControlTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: UiState,
    onModeSelected: (String) -> Unit,
    onStop: () -> Unit,
    onErrorShown: () -> Unit,
    onPhasesSelected: (Int) -> Unit,
    onMinCurrentChanged: (Int) -> Unit,
    onSettingsClick: () -> Unit,
    onGoeAuthorize: () -> Unit,
    holdConfirmMs: Long,
    goeEnabled: Boolean,
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
        topBar = {
            TopAppBar(
                title = { Text("ChargeControl") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                    }
                }
            )
        },
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

            if (goeEnabled) {
                HoldToConfirmButton(
                    "go-e Autorisierung",
                    isActive = false,
                    holdMs = holdConfirmMs,
                    outlined = true,
                    modifier = Modifier.fillMaxWidth()
                ) { onGoeAuthorize() }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HoldToConfirmButton(
                    "kW min + PV Überschuss (Min+PV)",
                    isActive = uiState.mode == "minpv",
                    holdMs = holdConfirmMs,
                    modifier = Modifier.fillMaxWidth()
                ) { onModeSelected("minpv") }
                HoldToConfirmButton(
                    "PV-Überschuss (PV)",
                    isActive = uiState.mode == "pv",
                    holdMs = holdConfirmMs,
                    modifier = Modifier.fillMaxWidth()
                ) { onModeSelected("pv") }
                HoldToConfirmButton(
                    "Schnellladen (Schnell)",
                    isActive = uiState.mode == "now",
                    holdMs = holdConfirmMs,
                    modifier = Modifier.fillMaxWidth()
                ) { onModeSelected("now") }
                HoldToConfirmButton(
                    "Laden stoppen",
                    isActive = false,
                    holdMs = holdConfirmMs,
                    outlined = true,
                    modifier = Modifier.fillMaxWidth()
                ) { onStop() }
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
                Text("Modus: ${modeLabel(uiState.mode)}")
                Text("Phasen: ${phasesLabel(uiState.phasesConfigured)}")
                Text("Ladestrom: ${uiState.offeredCurrent.roundToInt()} A - ${activePhasesLabel(uiState.phasesActive)}")
                Text("Fahrzeug verbunden: ${if (uiState.connected) "Ja" else "Nein"}")
                if (uiState.connected) {
                    Text("Ladestand: ${uiState.vehicleSoc} %")
                }
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
            Text("Ladestrom Einstellungen")
        }

        if (unlocked) {
            Text("Phasen", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SelectableButton(
                    "Automatisch",
                    isActive = uiState.phasesConfigured == 0,
                    modifier = Modifier.weight(1f)
                ) { onPhasesSelected(0) }
                SelectableButton(
                    "1-phasig",
                    isActive = uiState.phasesConfigured == 1,
                    modifier = Modifier.weight(1f)
                ) { onPhasesSelected(1) }
                SelectableButton(
                    "3-phasig",
                    isActive = uiState.phasesConfigured == 3,
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
                steps = 9
            )
        }
    }
}

/**
 * A button that fires [onConfirmed] only after being pressed and held for
 * [holdMs] — a plain tap does nothing. The background fills left-to-right
 * while held, as progress feedback; releasing early resets it. When
 * [holdMs] is 0 (the "Aus" setting), the hold gesture is skipped entirely
 * and the button fires immediately on tap release, like a normal button.
 */
@Composable
private fun HoldToConfirmButton(
    label: String,
    isActive: Boolean,
    holdMs: Long,
    enabled: Boolean = true,
    outlined: Boolean = false,
    modifier: Modifier = Modifier,
    onConfirmed: () -> Unit
) {
    val interactive = enabled && !isActive
    var progress by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()

    val backgroundColor = when {
        !interactive -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        outlined -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.primary
    }
    val contentColor = when {
        !interactive -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        outlined -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onPrimary
    }
    val fillColor = contentColor.copy(alpha = 0.25f)

    Surface(
        modifier = modifier
            .height(56.dp)
            .then(
                when {
                    !interactive -> Modifier
                    holdMs <= 0L -> Modifier.clickable(onClick = onConfirmed)
                    else -> Modifier.pointerInput(holdMs) {
                        detectTapGestures(
                            onPress = {
                                val holdJob = scope.launch {
                                    val steps = 40
                                    val stepDuration = holdMs / steps
                                    for (i in 1..steps) {
                                        delay(stepDuration)
                                        progress = i / steps.toFloat()
                                    }
                                    onConfirmed()
                                }
                                tryAwaitRelease()
                                holdJob.cancel()
                                progress = 0f
                            }
                        )
                    }
                }
            ),
        shape = RoundedCornerShape(50),
        color = backgroundColor,
        contentColor = contentColor,
        border = if (outlined) {
            BorderStroke(
                1.dp,
                if (interactive) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            )
        } else {
            null
        }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .background(fillColor)
            )
            Text(label, modifier = Modifier.padding(horizontal = 16.dp))
        }
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

private fun modeLabel(mode: String): String = when (mode) {
    "minpv" -> "kW min + PV Überschuss (Min+PV)"
    "pv" -> "PV-Überschuss (PV)"
    "now" -> "Schnellladen (Schnell)"
    "off" -> "Aus"
    else -> mode
}

private fun phasesLabel(phasesConfigured: Int): String = when (phasesConfigured) {
    1 -> "1-phasig"
    3 -> "3-phasig"
    else -> "Automatisch"
}

private fun activePhasesLabel(phasesActive: Int): String = when (phasesActive) {
    1 -> "1-phasig"
    3 -> "3-phasig"
    else -> "–"
}

@Preview(showBackground = true, name = "Lädt – PV-Überschuss")
@Composable
private fun MainScreenChargingPreview() {
    ChargeControlTheme {
        MainScreen(
            uiState = UiState(
                mode = "pv",
                phasesConfigured = 0,
                offeredCurrent = 14.0,
                minCurrent = 6,
                connected = true,
                charging = true,
                phasesActive = 3,
                vehicleSoc = 68,
                isLoading = false,
                hasData = true
            ),
            onModeSelected = {},
            onStop = {},
            onErrorShown = {},
            onPhasesSelected = {},
            onMinCurrentChanged = {},
            onSettingsClick = {},
            onGoeAuthorize = {},
            holdConfirmMs = 1000L,
            goeEnabled = true
        )
    }
}

@Preview(showBackground = true, name = "Kein Fahrzeug verbunden")
@Composable
private fun MainScreenDisconnectedPreview() {
    ChargeControlTheme {
        MainScreen(
            uiState = UiState(
                mode = "off",
                phasesConfigured = 0,
                offeredCurrent = 0.0,
                minCurrent = 6,
                connected = false,
                charging = false,
                phasesActive = 0,
                vehicleSoc = 0,
                isLoading = false,
                hasData = true
            ),
            onModeSelected = {},
            onStop = {},
            onErrorShown = {},
            onPhasesSelected = {},
            onMinCurrentChanged = {},
            onSettingsClick = {},
            onGoeAuthorize = {},
            holdConfirmMs = 1000L,
            goeEnabled = true
        )
    }
}

@Preview(showBackground = true, name = "Lade Status …")
@Composable
private fun MainScreenLoadingPreview() {
    ChargeControlTheme {
        MainScreen(
            uiState = UiState(isLoading = true, hasData = false),
            onModeSelected = {},
            onStop = {},
            onErrorShown = {},
            onPhasesSelected = {},
            onMinCurrentChanged = {},
            onSettingsClick = {},
            onGoeAuthorize = {},
            holdConfirmMs = 1000L,
            goeEnabled = true
        )
    }
}
