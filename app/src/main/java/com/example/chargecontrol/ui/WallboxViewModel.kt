package com.example.chargecontrol.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chargecontrol.Config
import com.example.chargecontrol.network.EvccApi
import com.example.chargecontrol.network.GoeApi
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
    val alwaysCharge: String = "off",
    val phasesConfigured: Int = 0,
    val offeredCurrent: Double = 0.0,
    val minCurrent: Int = 6,
    val connected: Boolean = false,
    val charging: Boolean = false,
    val phasesActive: Int = 0,
    val vehicleSoc: Int = 0,
    val goeAuthorized: Boolean = false,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val hasData: Boolean = false
)

class WallboxViewModel(
    private val evccApi: EvccApi,
    private val goeApi: GoeApi,
    private val goeTrxProvider: () -> Int,
    private val goeEnabledProvider: () -> Boolean,
    private val goeAutoAuthorizeProvider: () -> Boolean
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null
    private var wasUnreachable = false
    private var autoAuthorizeChecked = false

    fun onStart() {
        if (pollingJob?.isActive == true) return
        autoAuthorizeChecked = false
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

    /**
     * evcc does not reset the `alwaysCharge` overlay when the mode is changed
     * via this REST endpoint (only evcc's own web UI does that) — so every
     * plain mode change explicitly turns it off, to guarantee a clean,
     * unambiguous state for "off"/"now"/plain "smart". [setAlwaysChargeOn] is
     * the one deliberate exception that turns it on instead.
     */
    fun setMode(mode: String) = performLoadpointUpdate {
        val modeResponse = evccApi.setMode(Config.LOADPOINT_ID, mode)
        if (!modeResponse.isSuccessful) modeResponse else evccApi.setAlwaysCharge(Config.LOADPOINT_ID, "off")
    }

    /**
     * The old "min + PV" mode no longer exists as a `mode` value in evcc —
     * it's now smart mode with the always-charge overlay enabled. Replicates
     * the old behavior with two calls; if setting the mode fails, the
     * always-charge call is skipped and that failure is surfaced instead.
     */
    fun setAlwaysChargeOn() = performLoadpointUpdate {
        val modeResponse = evccApi.setMode(Config.LOADPOINT_ID, "smart")
        if (!modeResponse.isSuccessful) modeResponse else evccApi.setAlwaysCharge(Config.LOADPOINT_ID, "on")
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

    fun authorizeGoe() {
        viewModelScope.launch {
            performGoeAuthorize(successMessage = "go-e Autorisierung gesendet")
        }
    }

    private suspend fun performGoeAuthorize(successMessage: String) {
        try {
            val response = goeApi.authorize(trx = goeTrxProvider())
            if (response.isSuccessful) {
                _uiState.update { it.copy(errorMessage = successMessage) }
            } else if (response.code() == 500) {
                // go-e answers 500 when the wallbox is already authorized for this trx —
                // not a real error, so show it as informational rather than a failure.
                _uiState.update { it.copy(errorMessage = "Wallbox bereits autorisiert") }
            } else {
                _uiState.update { it.copy(errorMessage = "go-e-Fehler (${response.code()})") }
            }
        } catch (e: IOException) {
            _uiState.update { it.copy(errorMessage = "go-e nicht erreichbar, überprüfe WLAN oder go-e IP") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = "Unerwartete Antwort von go-e") }
        }
    }

    /**
     * Runs at most once per app-open (reset in [onStart]): if go-e is enabled,
     * auto-authorize is turned on, and the vehicle was connected on the first
     * successful evcc fetch since opening the app, authorize automatically.
     */
    private suspend fun maybeAutoAuthorizeGoe(connected: Boolean) {
        if (autoAuthorizeChecked) return
        autoAuthorizeChecked = true
        if (goeEnabledProvider() && goeAutoAuthorizeProvider() && connected) {
            performGoeAuthorize(successMessage = "Autorisierung erfolgreich")
        }
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
                    alwaysCharge = loadpoint.alwaysCharge,
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
            maybeAutoAuthorizeGoe(loadpoint.connected)
            fetchGoeAuthorized()
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

    /**
     * Reads whether the wallbox currently has an active go-e authorization
     * (`trx` non-null), only when go-e is enabled. Failures are swallowed —
     * this is a display-only detail, it must never disrupt the evcc status
     * poll it's called from.
     */
    private suspend fun fetchGoeAuthorized() {
        if (!goeEnabledProvider()) return
        try {
            val status = goeApi.getStatus()
            _uiState.update { it.copy(goeAuthorized = status.trx != null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Display-only; leave the last known value rather than surfacing an error here.
        }
    }
}
