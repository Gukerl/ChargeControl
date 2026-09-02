package com.example.chargecontrol.ui

import com.example.chargecontrol.Config
import com.example.chargecontrol.network.EvccApi
import com.example.chargecontrol.network.EvccStateResponse
import com.example.chargecontrol.network.GoeApi
import com.example.chargecontrol.network.LoadpointDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class WallboxViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun loadpoint(
        mode: String = "now",
        phasesConfigured: Int = 0,
        offeredCurrent: Double = 0.0,
        connected: Boolean = false,
        charging: Boolean = false,
        minCurrent: Double = 6.0,
        phasesActive: Int = 0,
        vehicleSoc: Double? = 0.0
    ) = LoadpointDto(mode, phasesConfigured, offeredCurrent, connected, charging, minCurrent, phasesActive, vehicleSoc)

    private class FakeEvccApi(
        var state: EvccStateResponse,
        var stateError: Throwable? = null,
        var setModeResponse: Response<ResponseBody> = Response.success(null),
        var setPhasesResponse: Response<ResponseBody> = Response.success(null),
        var setMinCurrentResponse: Response<ResponseBody> = Response.success(null)
    ) : EvccApi {
        var lastModeSet: String? = null
        var lastPhasesSet: String? = null
        var lastMinCurrentSet: Int? = null
        var fetchCount = 0

        override suspend fun getState(): EvccStateResponse {
            fetchCount++
            stateError?.let { throw it }
            return state
        }

        override suspend fun setMode(id: Int, mode: String): Response<ResponseBody> {
            lastModeSet = mode
            return setModeResponse
        }

        override suspend fun setPhases(id: Int, phases: String): Response<ResponseBody> {
            lastPhasesSet = phases
            return setPhasesResponse
        }

        override suspend fun setMinCurrent(id: Int, current: Int): Response<ResponseBody> {
            lastMinCurrentSet = current
            return setMinCurrentResponse
        }
    }

    private class FakeGoeApi(
        var authorizeResponse: Response<ResponseBody> = Response.success(null),
        var authorizeError: Throwable? = null
    ) : GoeApi {
        var lastTrx: Int? = null
        var authorizeCallCount = 0

        override suspend fun authorize(trx: Int): Response<ResponseBody> {
            authorizeCallCount++
            lastTrx = trx
            authorizeError?.let { throw it }
            return authorizeResponse
        }
    }

    @Test
    fun `onStart loads initial state without sending any control command`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint(mode = "pv", offeredCurrent = 9.5))))
        val viewModel = WallboxViewModel(
            evccApi,
            FakeGoeApi(),
            goeTrxProvider = { 1 },
            goeEnabledProvider = { false },
            goeAutoAuthorizeProvider = { false }
        )

        viewModel.onStart()
        dispatcher.scheduler.runCurrent()

        assertEquals("pv", viewModel.uiState.value.mode)
        assertEquals(9.5, viewModel.uiState.value.offeredCurrent, 0.0)
        assertEquals(false, viewModel.uiState.value.isLoading)
        assertEquals(1, evccApi.fetchCount)
        assertEquals(null, evccApi.lastModeSet)
        assertEquals(null, evccApi.lastPhasesSet)
        assertEquals(null, evccApi.lastMinCurrentSet)

        viewModel.onStop()
    }

    @Test
    fun `fetchState failure surfaces an error message`() = runTest {
        val evccApi = FakeEvccApi(
            state = EvccStateResponse(listOf(loadpoint(mode = "now"))),
            stateError = IOException("offline")
        )
        val viewModel = WallboxViewModel(
            evccApi,
            FakeGoeApi(),
            goeTrxProvider = { 1 },
            goeEnabledProvider = { false },
            goeAutoAuthorizeProvider = { false }
        )

        viewModel.onStart()
        dispatcher.scheduler.runCurrent()

        assertEquals(
            "EVCC nicht erreichbar, überprüfe WLAN oder EVCC Raspi",
            viewModel.uiState.value.errorMessage
        )

        viewModel.onStop()
    }

    @Test
    fun `fetchState surfaces a reconnected message after recovering from unreachable`() = runTest {
        val evccApi = FakeEvccApi(
            state = EvccStateResponse(listOf(loadpoint(mode = "now"))),
            stateError = IOException("offline")
        )
        val viewModel = WallboxViewModel(
            evccApi,
            FakeGoeApi(),
            goeTrxProvider = { 1 },
            goeEnabledProvider = { false },
            goeAutoAuthorizeProvider = { false }
        )

        viewModel.onStart()
        dispatcher.scheduler.runCurrent()
        assertEquals(
            "EVCC nicht erreichbar, überprüfe WLAN oder EVCC Raspi",
            viewModel.uiState.value.errorMessage
        )

        evccApi.stateError = null
        dispatcher.scheduler.advanceTimeBy(Config.POLL_INTERVAL_MS + 1)
        dispatcher.scheduler.runCurrent()

        assertEquals("Verbindung mit EVCC hergestellt", viewModel.uiState.value.errorMessage)

        viewModel.onStop()
    }

    @Test
    fun `fetchState does not show a reconnected message on a normal successful poll`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint(mode = "now"))))
        val viewModel = WallboxViewModel(
            evccApi,
            FakeGoeApi(),
            goeTrxProvider = { 1 },
            goeEnabledProvider = { false },
            goeAutoAuthorizeProvider = { false }
        )

        viewModel.onStart()
        dispatcher.scheduler.runCurrent()
        dispatcher.scheduler.advanceTimeBy(Config.POLL_INTERVAL_MS + 1)
        dispatcher.scheduler.runCurrent()

        assertEquals(null, viewModel.uiState.value.errorMessage)

        viewModel.onStop()
    }

    @Test
    fun `setMode posts the new mode and refreshes state`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint(mode = "minpv"))))
        val viewModel = WallboxViewModel(
            evccApi,
            FakeGoeApi(),
            goeTrxProvider = { 1 },
            goeEnabledProvider = { false },
            goeAutoAuthorizeProvider = { false }
        )

        viewModel.setMode("minpv")
        dispatcher.scheduler.runCurrent()

        assertEquals("minpv", evccApi.lastModeSet)
        assertEquals("minpv", viewModel.uiState.value.mode)
    }

    @Test
    fun `setMode failure response surfaces an error message`() = runTest {
        val evccApi = FakeEvccApi(
            state = EvccStateResponse(listOf(loadpoint(mode = "now"))),
            setModeResponse = Response.error(500, "".toResponseBody(null))
        )
        val viewModel = WallboxViewModel(
            evccApi,
            FakeGoeApi(),
            goeTrxProvider = { 1 },
            goeEnabledProvider = { false },
            goeAutoAuthorizeProvider = { false }
        )

        viewModel.setMode("now")
        dispatcher.scheduler.runCurrent()

        assertEquals("evcc-Fehler (500)", viewModel.uiState.value.errorMessage)
        assertEquals(1, evccApi.fetchCount)
    }

    @Test
    fun `onStop cancels polling so no further state fetches happen`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint())))
        val viewModel = WallboxViewModel(
            evccApi,
            FakeGoeApi(),
            goeTrxProvider = { 1 },
            goeEnabledProvider = { false },
            goeAutoAuthorizeProvider = { false }
        )

        viewModel.onStart()
        dispatcher.scheduler.runCurrent()
        viewModel.onStop()

        val fetchesBeforeAdvance = evccApi.fetchCount
        dispatcher.scheduler.advanceTimeBy(20_000)
        dispatcher.scheduler.runCurrent()

        assertEquals(fetchesBeforeAdvance, evccApi.fetchCount)
    }

    @Test
    fun `failed first fetchState leaves hasData false and stops loading`() = runTest {
        val evccApi = FakeEvccApi(
            state = EvccStateResponse(listOf(loadpoint())),
            stateError = IOException("offline")
        )
        val viewModel = WallboxViewModel(
            evccApi,
            FakeGoeApi(),
            goeTrxProvider = { 1 },
            goeEnabledProvider = { false },
            goeAutoAuthorizeProvider = { false }
        )

        viewModel.onStart()
        dispatcher.scheduler.runCurrent()

        assertEquals(false, viewModel.uiState.value.hasData)
        assertEquals(false, viewModel.uiState.value.isLoading)

        viewModel.onStop()
    }

    @Test
    fun `empty loadpoints list surfaces an error instead of hanging`() = runTest {
        val evccApi = FakeEvccApi(state = EvccStateResponse(emptyList()))
        val viewModel = WallboxViewModel(
            evccApi,
            FakeGoeApi(),
            goeTrxProvider = { 1 },
            goeEnabledProvider = { false },
            goeAutoAuthorizeProvider = { false }
        )

        viewModel.onStart()
        dispatcher.scheduler.runCurrent()

        assertEquals(false, viewModel.uiState.value.isLoading)
        assertEquals(
            "Loadpoint ${Config.LOADPOINT_ID} nicht gefunden",
            viewModel.uiState.value.errorMessage
        )

        viewModel.onStop()
    }

    @Test
    fun `unexpected exception during fetchState is caught, not propagated`() = runTest {
        val evccApi = FakeEvccApi(
            state = EvccStateResponse(listOf(loadpoint())),
            stateError = IllegalStateException("bad json")
        )
        val viewModel = WallboxViewModel(
            evccApi,
            FakeGoeApi(),
            goeTrxProvider = { 1 },
            goeEnabledProvider = { false },
            goeAutoAuthorizeProvider = { false }
        )

        viewModel.onStart()
        dispatcher.scheduler.runCurrent()

        assertEquals(false, viewModel.uiState.value.isLoading)
        assertEquals("Unerwartete Antwort von evcc", viewModel.uiState.value.errorMessage)

        viewModel.onStop()
    }

    @Test
    fun `poll loop fetches state again after the poll interval elapses`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint())))
        val viewModel = WallboxViewModel(
            evccApi,
            FakeGoeApi(),
            goeTrxProvider = { 1 },
            goeEnabledProvider = { false },
            goeAutoAuthorizeProvider = { false }
        )

        viewModel.onStart()
        dispatcher.scheduler.runCurrent()
        dispatcher.scheduler.advanceTimeBy(Config.POLL_INTERVAL_MS + 1)
        dispatcher.scheduler.runCurrent()

        assertEquals(2, evccApi.fetchCount)

        viewModel.onStop()
    }

    @Test
    fun `setPhases posts the new phase mode and refreshes state`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint(phasesConfigured = 3))))
        val viewModel = WallboxViewModel(
            evccApi,
            FakeGoeApi(),
            goeTrxProvider = { 1 },
            goeEnabledProvider = { false },
            goeAutoAuthorizeProvider = { false }
        )

        viewModel.setPhases(3)
        dispatcher.scheduler.runCurrent()

        assertEquals("3", evccApi.lastPhasesSet)
        assertEquals(3, viewModel.uiState.value.phasesConfigured)
    }

    @Test
    fun `setMinCurrent posts the new current and refreshes state`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint(minCurrent = 10.0))))
        val viewModel = WallboxViewModel(
            evccApi,
            FakeGoeApi(),
            goeTrxProvider = { 1 },
            goeEnabledProvider = { false },
            goeAutoAuthorizeProvider = { false }
        )

        viewModel.setMinCurrent(10)
        dispatcher.scheduler.runCurrent()

        assertEquals(10, evccApi.lastMinCurrentSet)
        assertEquals(10, viewModel.uiState.value.minCurrent)
    }

    @Test
    fun `fetchState populates vehicleSoc rounded to the nearest percent`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint(vehicleSoc = 82.6))))
        val viewModel = WallboxViewModel(
            evccApi,
            FakeGoeApi(),
            goeTrxProvider = { 1 },
            goeEnabledProvider = { false },
            goeAutoAuthorizeProvider = { false }
        )

        viewModel.onStart()
        dispatcher.scheduler.runCurrent()

        assertEquals(83, viewModel.uiState.value.vehicleSoc)

        viewModel.onStop()
    }

    @Test
    fun `fetchState defaults vehicleSoc to 0 when evcc omits it`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint(vehicleSoc = null))))
        val viewModel = WallboxViewModel(
            evccApi,
            FakeGoeApi(),
            goeTrxProvider = { 1 },
            goeEnabledProvider = { false },
            goeAutoAuthorizeProvider = { false }
        )

        viewModel.onStart()
        dispatcher.scheduler.runCurrent()

        assertEquals(true, viewModel.uiState.value.hasData)
        assertEquals(0, viewModel.uiState.value.vehicleSoc)

        viewModel.onStop()
    }

    @Test
    fun `fetchState populates phasesActive`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint(phasesActive = 1))))
        val viewModel = WallboxViewModel(
            evccApi,
            FakeGoeApi(),
            goeTrxProvider = { 1 },
            goeEnabledProvider = { false },
            goeAutoAuthorizeProvider = { false }
        )

        viewModel.onStart()
        dispatcher.scheduler.runCurrent()

        assertEquals(1, viewModel.uiState.value.phasesActive)

        viewModel.onStop()
    }

    @Test
    fun `authorizeGoe calls the go-e API with the configured trx and shows a confirmation`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint())))
        val goeApi = FakeGoeApi()
        val viewModel = WallboxViewModel(
            evccApi,
            goeApi,
            goeTrxProvider = { 7 },
            goeEnabledProvider = { false },
            goeAutoAuthorizeProvider = { false }
        )

        viewModel.authorizeGoe()
        dispatcher.scheduler.runCurrent()

        assertEquals(7, goeApi.lastTrx)
        assertEquals("go-e Autorisierung gesendet", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `authorizeGoe failure response surfaces an error message`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint())))
        val goeApi = FakeGoeApi(authorizeResponse = Response.error(503, "".toResponseBody(null)))
        val viewModel = WallboxViewModel(
            evccApi,
            goeApi,
            goeTrxProvider = { 1 },
            goeEnabledProvider = { false },
            goeAutoAuthorizeProvider = { false }
        )

        viewModel.authorizeGoe()
        dispatcher.scheduler.runCurrent()

        assertEquals("go-e-Fehler (503)", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `authorizeGoe treats a 500 response as already-authorized, not an error`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint())))
        val goeApi = FakeGoeApi(authorizeResponse = Response.error(500, "".toResponseBody(null)))
        val viewModel = WallboxViewModel(
            evccApi,
            goeApi,
            goeTrxProvider = { 1 },
            goeEnabledProvider = { false },
            goeAutoAuthorizeProvider = { false }
        )

        viewModel.authorizeGoe()
        dispatcher.scheduler.runCurrent()

        assertEquals("Wallbox bereits autorisiert", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `authorizeGoe unreachable surfaces the go-e unreachable message`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint())))
        val goeApi = FakeGoeApi(authorizeError = IOException("offline"))
        val viewModel = WallboxViewModel(
            evccApi,
            goeApi,
            goeTrxProvider = { 1 },
            goeEnabledProvider = { false },
            goeAutoAuthorizeProvider = { false }
        )

        viewModel.authorizeGoe()
        dispatcher.scheduler.runCurrent()

        assertEquals(
            "go-e nicht erreichbar, überprüfe WLAN oder go-e IP",
            viewModel.uiState.value.errorMessage
        )
    }

    @Test
    fun `onStart auto-authorizes go-e when enabled, auto-authorize on, and vehicle connected`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint(connected = true))))
        val goeApi = FakeGoeApi()
        val viewModel = WallboxViewModel(
            evccApi,
            goeApi,
            goeTrxProvider = { 3 },
            goeEnabledProvider = { true },
            goeAutoAuthorizeProvider = { true }
        )

        viewModel.onStart()
        dispatcher.scheduler.runCurrent()

        assertEquals(3, goeApi.lastTrx)
        assertEquals("Autorisierung erfolgreich", viewModel.uiState.value.errorMessage)

        viewModel.onStop()
    }

    @Test
    fun `onStart does not auto-authorize when vehicle is not connected`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint(connected = false))))
        val goeApi = FakeGoeApi()
        val viewModel = WallboxViewModel(
            evccApi,
            goeApi,
            goeTrxProvider = { 1 },
            goeEnabledProvider = { true },
            goeAutoAuthorizeProvider = { true }
        )

        viewModel.onStart()
        dispatcher.scheduler.runCurrent()

        assertEquals(null, goeApi.lastTrx)
        assertEquals(null, viewModel.uiState.value.errorMessage)

        viewModel.onStop()
    }

    @Test
    fun `onStart does not auto-authorize when go-e is disabled`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint(connected = true))))
        val goeApi = FakeGoeApi()
        val viewModel = WallboxViewModel(
            evccApi,
            goeApi,
            goeTrxProvider = { 1 },
            goeEnabledProvider = { false },
            goeAutoAuthorizeProvider = { true }
        )

        viewModel.onStart()
        dispatcher.scheduler.runCurrent()

        assertEquals(null, goeApi.lastTrx)

        viewModel.onStop()
    }

    @Test
    fun `onStart does not auto-authorize when the auto-authorize setting is off`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint(connected = true))))
        val goeApi = FakeGoeApi()
        val viewModel = WallboxViewModel(
            evccApi,
            goeApi,
            goeTrxProvider = { 1 },
            goeEnabledProvider = { true },
            goeAutoAuthorizeProvider = { false }
        )

        viewModel.onStart()
        dispatcher.scheduler.runCurrent()

        assertEquals(null, goeApi.lastTrx)

        viewModel.onStop()
    }

    @Test
    fun `auto-authorize surfaces the already-authorized message instead of go-e Fehler 500`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint(connected = true))))
        val goeApi = FakeGoeApi(authorizeResponse = Response.error(500, "".toResponseBody(null)))
        val viewModel = WallboxViewModel(
            evccApi,
            goeApi,
            goeTrxProvider = { 1 },
            goeEnabledProvider = { true },
            goeAutoAuthorizeProvider = { true }
        )

        viewModel.onStart()
        dispatcher.scheduler.runCurrent()

        assertEquals("Wallbox bereits autorisiert", viewModel.uiState.value.errorMessage)

        viewModel.onStop()
    }

    @Test
    fun `auto-authorize only fires once per app-open, not on every poll`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint(connected = true))))
        val goeApi = FakeGoeApi()
        val viewModel = WallboxViewModel(
            evccApi,
            goeApi,
            goeTrxProvider = { 1 },
            goeEnabledProvider = { true },
            goeAutoAuthorizeProvider = { true }
        )

        viewModel.onStart()
        dispatcher.scheduler.runCurrent()
        dispatcher.scheduler.advanceTimeBy(Config.POLL_INTERVAL_MS + 1)
        dispatcher.scheduler.runCurrent()
        dispatcher.scheduler.advanceTimeBy(Config.POLL_INTERVAL_MS + 1)
        dispatcher.scheduler.runCurrent()

        assertEquals(1, goeApi.authorizeCallCount)

        viewModel.onStop()
    }
}
