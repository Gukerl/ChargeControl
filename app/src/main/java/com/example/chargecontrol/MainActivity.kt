package com.example.chargecontrol

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.chargecontrol.network.NetworkModule
import com.example.chargecontrol.ui.MainScreen
import com.example.chargecontrol.ui.SettingsScreen
import com.example.chargecontrol.ui.WallboxViewModel
import com.example.chargecontrol.ui.theme.ChargeControlTheme

private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsRepository.init(this)
        enableEdgeToEdge()
        setContent {
            ChargeControlTheme {
                ChargeControlApp()
            }
        }
    }
}

private fun hasLocalNetworkPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED

@Composable
fun ChargeControlApp() {
    val context = LocalContext.current
    val activity = context as Activity

    var hasPermission by remember { mutableStateOf(hasLocalNetworkPermission(context)) }
    var permissionRequestedOnce by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        permissionRequestedOnce = true
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(LOCAL_NETWORK_PERMISSION)
        }
    }

    if (!hasPermission) {
        val showSettingsButton = permissionRequestedOnce &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, LOCAL_NETWORK_PERMISSION)

        LocalNetworkPermissionScreen(
            showSettingsButton = showSettingsButton,
            onRequestPermission = { permissionLauncher.launch(LOCAL_NETWORK_PERMISSION) },
            onOpenSettings = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", activity.packageName, null)
                }
                context.startActivity(intent)
            }
        )
        return
    }

    val viewModel: WallboxViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                WallboxViewModel(
                    NetworkModule.evccApi,
                    NetworkModule.goeApi,
                    goeTrxProvider = { SettingsRepository.goeTrx },
                    goeEnabledProvider = { SettingsRepository.goeEnabled },
                    goeAutoAuthorizeProvider = { SettingsRepository.goeAutoAuthorize }
                )
            }
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(hasPermission) {
        viewModel.onStart()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onStart()
                Lifecycle.Event.ON_STOP -> viewModel.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var showSettings by rememberSaveable { mutableStateOf(false) }

    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(onBack = { showSettings = false })
    } else {
        MainScreen(
            uiState = uiState,
            onModeSelected = viewModel::setMode,
            onStop = { viewModel.setMode("off") },
            onErrorShown = viewModel::errorShown,
            onPhasesSelected = viewModel::setPhases,
            onMinCurrentChanged = viewModel::setMinCurrent,
            onSettingsClick = {
                viewModel.errorShown()
                showSettings = true
            },
            onGoeAuthorize = viewModel::authorizeGoe,
            holdConfirmMs = SettingsRepository.holdConfirmMs.toLong(),
            goeEnabled = SettingsRepository.goeEnabled
        )
    }
}

@Composable
private fun LocalNetworkPermissionScreen(
    showSettingsButton: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Diese App braucht Zugriff auf dein lokales Netzwerk, um evcc und die Wallbox zu erreichen.")
            if (showSettingsButton) {
                Text("Die Berechtigung wurde abgelehnt. Bitte in den App-Einstellungen manuell aktivieren.")
                Button(onClick = onOpenSettings) {
                    Text("Einstellungen öffnen")
                }
            } else {
                Button(onClick = onRequestPermission) {
                    Text("Berechtigung erteilen")
                }
            }
        }
    }
}
