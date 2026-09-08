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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.chargecontrol.R
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
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
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
                    stringResource(R.string.goe_authorize),
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
                    stringResource(R.string.mode_minpv),
                    isActive = uiState.mode == "minpv",
                    holdMs = holdConfirmMs,
                    modifier = Modifier.fillMaxWidth()
                ) { onModeSelected("minpv") }
                HoldToConfirmButton(
                    stringResource(R.string.mode_pv),
                    isActive = uiState.mode == "pv",
                    holdMs = holdConfirmMs,
                    modifier = Modifier.fillMaxWidth()
                ) { onModeSelected("pv") }
                HoldToConfirmButton(
                    stringResource(R.string.mode_now),
                    isActive = uiState.mode == "now",
                    holdMs = holdConfirmMs,
                    modifier = Modifier.fillMaxWidth()
                ) { onModeSelected("now") }
                HoldToConfirmButton(
                    stringResource(R.string.stop_charging),
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
                    Text(stringResource(R.string.loading_status))
                }
            } else if (!uiState.hasData) {
                Text(stringResource(R.string.no_status_available))
            } else {
                Text(stringResource(R.string.status_mode, modeLabel(uiState.mode)))
                Text(stringResource(R.string.status_phases, phasesLabel(uiState.phasesConfigured)))
                Text(
                    stringResource(
                        R.string.status_current,
                        uiState.offeredCurrent.roundToInt(),
                        activePhasesLabel(uiState.phasesActive)
                    )
                )
                Text(
                    stringResource(
                        R.string.status_vehicle_connected,
                        stringResource(if (uiState.connected) R.string.yes else R.string.no)
                    )
                )
                if (uiState.connected) {
                    Text(stringResource(R.string.status_battery_level, uiState.vehicleSoc))
                }
                Text(
                    when {
                        uiState.charging -> stringResource(R.string.status_charging_now)
                        uiState.connected -> stringResource(R.string.status_connected_not_charging)
                        else -> stringResource(R.string.status_not_connected)
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
            Text(stringResource(R.string.charging_current_settings))
        }

        if (unlocked) {
            Text(stringResource(R.string.phases_label), style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SelectableButton(
                    stringResource(R.string.phases_automatic),
                    isActive = uiState.phasesConfigured == 0,
                    modifier = Modifier.weight(1f)
                ) { onPhasesSelected(0) }
                SelectableButton(
                    stringResource(R.string.phases_1),
                    isActive = uiState.phasesConfigured == 1,
                    modifier = Modifier.weight(1f)
                ) { onPhasesSelected(1) }
                SelectableButton(
                    stringResource(R.string.phases_3),
                    isActive = uiState.phasesConfigured == 3,
                    modifier = Modifier.weight(1f)
                ) { onPhasesSelected(3) }
            }

            Text(stringResource(R.string.min_current_label, sliderValue), style = MaterialTheme.typography.labelLarge)
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

@Composable
private fun modeLabel(mode: String): String = when (mode) {
    "minpv" -> stringResource(R.string.mode_minpv)
    "pv" -> stringResource(R.string.mode_pv)
    "now" -> stringResource(R.string.mode_now)
    "off" -> stringResource(R.string.mode_off)
    else -> mode
}

@Composable
private fun phasesLabel(phasesConfigured: Int): String = when (phasesConfigured) {
    1 -> stringResource(R.string.phases_1)
    3 -> stringResource(R.string.phases_3)
    else -> stringResource(R.string.phases_automatic)
}

@Composable
private fun activePhasesLabel(phasesActive: Int): String = when (phasesActive) {
    1 -> stringResource(R.string.phases_1)
    3 -> stringResource(R.string.phases_3)
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
