package com.example.chargecontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.chargecontrol.network.NetworkModule
import com.example.chargecontrol.ui.MainScreen
import com.example.chargecontrol.ui.WallboxViewModel
import com.example.chargecontrol.ui.theme.ChargeControlTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChargeControlTheme {
                ChargeControlApp()
            }
        }
    }
}

@Composable
fun ChargeControlApp() {
    val viewModel: WallboxViewModel = viewModel(
        factory = viewModelFactory {
            initializer { WallboxViewModel(NetworkModule.evccApi, NetworkModule.goeApi) }
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

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

    MainScreen(
        uiState = uiState,
        onModeSelected = viewModel::setMode,
        onStop = { viewModel.setMode("off") },
        onErrorShown = viewModel::errorShown
    )
}
