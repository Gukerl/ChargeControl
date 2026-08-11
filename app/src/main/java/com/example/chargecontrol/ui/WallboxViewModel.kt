package com.example.chargecontrol.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chargecontrol.Config
import com.example.chargecontrol.network.EvccApi
import com.example.chargecontrol.network.GoeApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

data class UiState(
    val mode: String = "",
    val phasesConfigured: Int = 0,
    val offeredCurrent: Double = 0.0,
    val connected: Boolean = false,
    val charging: Boolean = false,
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

class WallboxViewModel(
    private val evccApi: EvccApi,
    private val goeApi: GoeApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    fun onStart() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            releaseWallbox()
            while (isActive) {
                fetchState()
                delay(Config.POLL_INTERVAL_MS)
            }
        }
    }

    fun onStop() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun setMode(mode: String) {
        viewModelScope.launch {
            try {
                val response = evccApi.setMode(Config.LOADPOINT_ID, mode)
                if (!response.isSuccessful) {
                    _uiState.update { it.copy(errorMessage = "evcc-Fehler (${response.code()})") }
                }
            } catch (e: IOException) {
                _uiState.update { it.copy(errorMessage = "evcc nicht erreichbar") }
            }
            fetchState()
        }
    }

    fun errorShown() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private suspend fun releaseWallbox() {
        try {
            val response = goeApi.release(frc = 0)
            if (!response.isSuccessful) {
                _uiState.update { it.copy(errorMessage = "go-e Box-Fehler (${response.code()})") }
            }
        } catch (e: IOException) {
            _uiState.update { it.copy(errorMessage = "go-e Box nicht erreichbar") }
        }
    }

    private suspend fun fetchState() {
        try {
            val state = evccApi.getState()
            val loadpoint = state.loadpoints.getOrNull(Config.LOADPOINT_ID - 1) ?: return
            _uiState.update {
                it.copy(
                    mode = loadpoint.mode,
                    phasesConfigured = loadpoint.phasesConfigured,
                    offeredCurrent = loadpoint.offeredCurrent,
                    connected = loadpoint.connected,
                    charging = loadpoint.charging,
                    isLoading = false
                )
            }
        } catch (e: IOException) {
            _uiState.update { it.copy(isLoading = false, errorMessage = "evcc nicht erreichbar") }
        } catch (e: HttpException) {
            _uiState.update { it.copy(isLoading = false, errorMessage = "evcc-Fehler (${e.code()})") }
        }
    }
}
