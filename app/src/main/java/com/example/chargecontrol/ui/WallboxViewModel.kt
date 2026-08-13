package com.example.chargecontrol.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chargecontrol.Config
import com.example.chargecontrol.network.EvccApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import kotlin.math.roundToInt

data class UiState(
    val mode: String = "",
    val phasesConfigured: Int = 0,
    val offeredCurrent: Double = 0.0,
    val minCurrent: Int = 6,
    val connected: Boolean = false,
    val charging: Boolean = false,
    val phasesActive: Int = 0,
    val vehicleSoc: Int = 0,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val hasData: Boolean = false
)

class WallboxViewModel(
    private val evccApi: EvccApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null
    private var wasUnreachable = false

    fun onStart() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
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

    fun setMode(mode: String) = performLoadpointUpdate {
        evccApi.setMode(Config.LOADPOINT_ID, mode)
    }

    fun setPhases(phases: Int) = performLoadpointUpdate {
        evccApi.setPhases(Config.LOADPOINT_ID, phases.toString())
    }

    fun setMinCurrent(current: Int) = performLoadpointUpdate {
        evccApi.setMinCurrent(Config.LOADPOINT_ID, current)
    }

    fun errorShown() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun performLoadpointUpdate(action: suspend () -> Response<ResponseBody>) {
        viewModelScope.launch {
            try {
                val response = action()
                if (!response.isSuccessful) {
                    _uiState.update { it.copy(errorMessage = "evcc-Fehler (${response.code()})") }
                }
            } catch (e: IOException) {
                wasUnreachable = true
                _uiState.update { it.copy(errorMessage = "EVCC nicht erreichbar, überprüfe WLAN oder EVCC Raspi") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Unerwartete Antwort von evcc") }
            }
            fetchState()
        }
    }

    private suspend fun fetchState() {
        try {
            val state = evccApi.getState()
            val loadpoint = state.loadpoints.getOrNull(Config.LOADPOINT_ID - 1) ?: run {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Loadpoint ${Config.LOADPOINT_ID} nicht gefunden"
                    )
                }
                return
            }
            val reconnected = wasUnreachable
            wasUnreachable = false
            _uiState.update {
                it.copy(
                    mode = loadpoint.mode,
                    phasesConfigured = loadpoint.phasesConfigured,
                    offeredCurrent = loadpoint.offeredCurrent,
                    minCurrent = loadpoint.minCurrent.roundToInt(),
                    connected = loadpoint.connected,
                    charging = loadpoint.charging,
                    phasesActive = loadpoint.phasesActive,
                    vehicleSoc = loadpoint.vehicleSoc?.roundToInt() ?: 0,
                    isLoading = false,
                    hasData = true,
                    errorMessage = if (reconnected) "Verbindung mit EVCC hergestellt" else it.errorMessage
                )
            }
        } catch (e: IOException) {
            wasUnreachable = true
            _uiState.update { it.copy(isLoading = false, errorMessage = "EVCC nicht erreichbar, überprüfe WLAN oder EVCC Raspi") }
        } catch (e: HttpException) {
            _uiState.update { it.copy(isLoading = false, errorMessage = "evcc-Fehler (${e.code()})") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, errorMessage = "Unerwartete Antwort von evcc") }
        }
    }
}
