# Phase/Current Controls Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the previously-deferred phase (0/1/3) and min-current (6–16A) controls to ChargeControl, gated behind a single "unlock" checkbox, per [docs/superpowers/specs/2026-08-11-phase-current-controls-design.md](../specs/2026-08-11-phase-current-controls-design.md).

**Architecture:** Extends the existing MVVM structure — no new files, no new architectural concepts. `EvccApi` gets two new endpoints, `WallboxViewModel` gets two new public methods (refactored to share the existing post-and-refetch logic with `setMode`), `MainScreen` gets a new "advanced settings" section with local-only unlock state.

**Tech Stack:** Same as the existing project (Kotlin, Compose, Retrofit, kotlinx.coroutines) — no new dependencies.

## Global Constraints

- evcc endpoints (verified during the original design against the live instance and official docs): `POST /api/loadpoints/{id}/phases/{phases}` with `phases` ∈ {"0","1","3"}; `POST /api/loadpoints/{id}/mincurrent/{current}` with an integer 6–16.
- evcc's state JSON already includes `minCurrent` per loadpoint (previously unparsed).
- The unlock checkbox is pure local Compose UI state — never part of `UiState`/`WallboxViewModel`. It stays unlocked until manually toggled off or the app restarts (no auto-relock after a change).
- Both the phase buttons and the current slider are always visible, just `enabled = false` (grayed out, not hidden) while locked.
- The current slider only calls the API on release (`onValueChangeFinished`), never on every intermediate drag position.
- Same error-handling philosophy as the rest of the app: no crash, no retry, short error message via the existing Snackbar, last known state stays visible.
- Project root: `/home/andi/AndroidStudioProjects/ChargeControl`, package `com.example.chargecontrol`, on branch `master`.

---

### Task 1: Network layer — `minCurrent` field and two new evcc endpoints

**Files:**
- Modify: `app/src/main/java/com/example/chargecontrol/network/EvccApi.kt` (replace entire file)
- Modify: `app/src/test/java/com/example/chargecontrol/network/EvccStateResponseTest.kt` (replace entire file — both existing payloads need a `minCurrent` field now that it's required on `LoadpointDto`)
- Modify: `app/src/test/java/com/example/chargecontrol/ui/WallboxViewModelTest.kt` (one-line change only — see Step 3)

**Interfaces:**
- Consumes: nothing new.
- Produces: `LoadpointDto` gains a required `minCurrent: Int` field; `EvccApi` gains `suspend fun setPhases(id: Int, phases: String): Response<ResponseBody>` and `suspend fun setMinCurrent(id: Int, current: Int): Response<ResponseBody>` — both consumed by Task 2.

Note: `LoadpointDto` gaining a required 6th constructor parameter breaks the positional call in `WallboxViewModelTest.kt`'s `loadpoint()` helper, which this task does not otherwise touch. Step 3 makes the minimal one-line fix needed to keep the whole module compiling — Task 2 replaces this same file wholesale anyway (adding the `minCurrent` parameter properly, updating `FakeEvccApi`, adding new tests), so this is a throwaway patch, not a design decision.

- [ ] **Step 1: Replace `EvccApi.kt`**

```kotlin
package com.example.chargecontrol.network

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

@Serializable
data class LoadpointDto(
    val mode: String,
    val phasesConfigured: Int,
    val offeredCurrent: Double,
    val connected: Boolean,
    val charging: Boolean,
    val minCurrent: Int
)

@Serializable
data class EvccStateResponse(
    val loadpoints: List<LoadpointDto> = emptyList()
)

interface EvccApi {
    @GET("state")
    suspend fun getState(): EvccStateResponse

    @POST("loadpoints/{id}/mode/{mode}")
    suspend fun setMode(@Path("id") id: Int, @Path("mode") mode: String): Response<ResponseBody>

    @POST("loadpoints/{id}/phases/{phases}")
    suspend fun setPhases(@Path("id") id: Int, @Path("phases") phases: String): Response<ResponseBody>

    @POST("loadpoints/{id}/mincurrent/{current}")
    suspend fun setMinCurrent(@Path("id") id: Int, @Path("current") current: Int): Response<ResponseBody>
}
```

- [ ] **Step 2: Replace `EvccStateResponseTest.kt`** (adds `minCurrent` to both payloads and one assertion for it)

```kotlin
package com.example.chargecontrol.network

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EvccStateResponseTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses loadpoint fields from a real evcc state payload`() {
        val payload = """
            {
              "loadpoints": [
                {
                  "mode": "pv",
                  "phasesConfigured": 3,
                  "offeredCurrent": 9.5,
                  "connected": true,
                  "charging": true,
                  "minCurrent": 6,
                  "title": "go-e Box",
                  "chargePower": 2185
                }
              ],
              "version": "0.313.2",
              "siteTitle": "Zuhause"
            }
        """.trimIndent()

        val result = json.decodeFromString<EvccStateResponse>(payload)

        val loadpoint = result.loadpoints.single()
        assertEquals("pv", loadpoint.mode)
        assertEquals(3, loadpoint.phasesConfigured)
        assertEquals(9.5, loadpoint.offeredCurrent, 0.0)
        assertEquals(true, loadpoint.connected)
        assertEquals(true, loadpoint.charging)
        assertEquals(6, loadpoint.minCurrent)
    }

    @Test
    fun `ignores unknown top-level and nested fields`() {
        val payload = """
            {
              "loadpoints": [
                {
                  "mode": "off",
                  "phasesConfigured": 0,
                  "offeredCurrent": 0.0,
                  "connected": false,
                  "charging": false,
                  "minCurrent": 6,
                  "chargeVoltages": [227.2, 233.4, 234.9],
                  "vehicleTitle": "BYDSurf"
                }
              ],
              "battery": [],
              "pv": []
            }
        """.trimIndent()

        val result = json.decodeFromString<EvccStateResponse>(payload)

        assertEquals("off", result.loadpoints.single().mode)
        assertFalse(result.loadpoints.single().connected)
    }
}
```

- [ ] **Step 3: Keep `WallboxViewModelTest.kt` compiling**

In `app/src/test/java/com/example/chargecontrol/ui/WallboxViewModelTest.kt`, find the `loadpoint()` helper:

```kotlin
    private fun loadpoint(
        mode: String = "now",
        phasesConfigured: Int = 0,
        offeredCurrent: Double = 0.0,
        connected: Boolean = false,
        charging: Boolean = false
    ) = LoadpointDto(mode, phasesConfigured, offeredCurrent, connected, charging)
```

Replace only the final line with:

```kotlin
    ) = LoadpointDto(mode, phasesConfigured, offeredCurrent, connected, charging, minCurrent = 6)
```

Do not change anything else in this file — Task 2 replaces it wholesale.

- [ ] **Step 4: Run the tests and verify they pass**

Run: `cd /home/andi/AndroidStudioProjects/ChargeControl/.worktrees/phase-current-controls && ./gradlew testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, the full existing suite passes (the 2 `EvccStateResponseTest` tests plus all pre-existing `WallboxViewModelTest` tests), with no compile errors anywhere in the module.

- [ ] **Step 5: Commit**

```bash
cd /home/andi/AndroidStudioProjects/ChargeControl/.worktrees/phase-current-controls
git add app/src/main/java/com/example/chargecontrol/network/EvccApi.kt app/src/test/java/com/example/chargecontrol/network/EvccStateResponseTest.kt app/src/test/java/com/example/chargecontrol/ui/WallboxViewModelTest.kt
git commit -m "feat: add minCurrent field and phase/current evcc endpoints"
```

---

### Task 2: WallboxViewModel — `setPhases`/`setMinCurrent`, shared update logic

**Files:**
- Modify: `app/src/main/java/com/example/chargecontrol/ui/WallboxViewModel.kt` (replace entire file)
- Modify: `app/src/test/java/com/example/chargecontrol/ui/WallboxViewModelTest.kt` (replace entire file)

**Interfaces:**
- Consumes: `EvccApi.setPhases`/`setMinCurrent` (Task 1).
- Produces: `UiState` gains `minCurrent: Int = 6`; `WallboxViewModel` gains `fun setPhases(phases: Int)` and `fun setMinCurrent(current: Int)` — both consumed by Task 3 (`MainActivity`).

This task also refactors `setMode` to share its post-and-refetch logic with the two new methods via a private `performLoadpointUpdate` helper, since three near-identical bodies would otherwise be a verbatim-duplication defect. The error-handling behavior (IOException/HttpException/generic-Exception messages, unconditional refetch afterward) is unchanged — only the code structure changes.

- [ ] **Step 1: Replace `WallboxViewModel.kt`**

```kotlin
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

data class UiState(
    val mode: String = "",
    val phasesConfigured: Int = 0,
    val offeredCurrent: Double = 0.0,
    val minCurrent: Int = 6,
    val connected: Boolean = false,
    val charging: Boolean = false,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val hasData: Boolean = false
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
                _uiState.update { it.copy(errorMessage = "evcc nicht erreichbar") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Unerwartete Antwort von evcc") }
            }
            fetchState()
        }
    }

    private suspend fun releaseWallbox() {
        try {
            val response = goeApi.release(frc = 0)
            if (!response.isSuccessful) {
                _uiState.update { it.copy(errorMessage = "go-e Box-Fehler (${response.code()})") }
            }
        } catch (e: IOException) {
            _uiState.update { it.copy(errorMessage = "go-e Box nicht erreichbar") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = "Unerwartete Antwort von go-e Box") }
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
            _uiState.update {
                it.copy(
                    mode = loadpoint.mode,
                    phasesConfigured = loadpoint.phasesConfigured,
                    offeredCurrent = loadpoint.offeredCurrent,
                    minCurrent = loadpoint.minCurrent,
                    connected = loadpoint.connected,
                    charging = loadpoint.charging,
                    isLoading = false,
                    hasData = true
                )
            }
        } catch (e: IOException) {
            _uiState.update { it.copy(isLoading = false, errorMessage = "evcc nicht erreichbar") }
        } catch (e: HttpException) {
            _uiState.update { it.copy(isLoading = false, errorMessage = "evcc-Fehler (${e.code()})") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, errorMessage = "Unerwartete Antwort von evcc") }
        }
    }
}
```

- [ ] **Step 2: Replace `WallboxViewModelTest.kt`** (adds `minCurrent` to the `loadpoint()` helper and `FakeEvccApi`, plus two new tests; all 8 existing tests are otherwise unchanged)

```kotlin
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
        minCurrent: Int = 6
    ) = LoadpointDto(mode, phasesConfigured, offeredCurrent, connected, charging, minCurrent)

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
        var releaseResponse: Response<ResponseBody> = Response.success(null)
    ) : GoeApi {
        var releaseCalled = false

        override suspend fun release(frc: Int): Response<ResponseBody> {
            releaseCalled = true
            return releaseResponse
        }
    }

    @Test
    fun `onStart releases the wallbox and loads initial state`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint(mode = "pv", offeredCurrent = 9.5))))
        val goeApi = FakeGoeApi()
        val viewModel = WallboxViewModel(evccApi, goeApi)

        viewModel.onStart()
        dispatcher.scheduler.runCurrent()

        assertEquals(true, goeApi.releaseCalled)
        assertEquals("pv", viewModel.uiState.value.mode)
        assertEquals(9.5, viewModel.uiState.value.offeredCurrent, 0.0)
        assertEquals(false, viewModel.uiState.value.isLoading)

        viewModel.onStop()
    }

    @Test
    fun `fetchState failure surfaces an error message`() = runTest {
        val evccApi = FakeEvccApi(
            state = EvccStateResponse(listOf(loadpoint(mode = "now"))),
            stateError = IOException("offline")
        )
        val goeApi = FakeGoeApi()
        val viewModel = WallboxViewModel(evccApi, goeApi)

        viewModel.onStart()
        dispatcher.scheduler.runCurrent()

        assertEquals("evcc nicht erreichbar", viewModel.uiState.value.errorMessage)

        viewModel.onStop()
    }

    @Test
    fun `setMode posts the new mode and refreshes state`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint(mode = "minpv"))))
        val goeApi = FakeGoeApi()
        val viewModel = WallboxViewModel(evccApi, goeApi)

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
        val goeApi = FakeGoeApi()
        val viewModel = WallboxViewModel(evccApi, goeApi)

        viewModel.setMode("now")
        dispatcher.scheduler.runCurrent()

        assertEquals("evcc-Fehler (500)", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `onStop cancels polling so no further state fetches happen`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint())))
        val goeApi = FakeGoeApi()
        val viewModel = WallboxViewModel(evccApi, goeApi)

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
        val goeApi = FakeGoeApi()
        val viewModel = WallboxViewModel(evccApi, goeApi)

        viewModel.onStart()
        dispatcher.scheduler.runCurrent()

        assertEquals(false, viewModel.uiState.value.hasData)
        assertEquals(false, viewModel.uiState.value.isLoading)

        viewModel.onStop()
    }

    @Test
    fun `empty loadpoints list surfaces an error instead of hanging`() = runTest {
        val evccApi = FakeEvccApi(state = EvccStateResponse(emptyList()))
        val goeApi = FakeGoeApi()
        val viewModel = WallboxViewModel(evccApi, goeApi)

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
        val goeApi = FakeGoeApi()
        val viewModel = WallboxViewModel(evccApi, goeApi)

        viewModel.onStart()
        dispatcher.scheduler.runCurrent()

        assertEquals(false, viewModel.uiState.value.isLoading)
        assertEquals("Unerwartete Antwort von evcc", viewModel.uiState.value.errorMessage)

        viewModel.onStop()
    }

    @Test
    fun `poll loop fetches state again after the poll interval elapses`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint())))
        val goeApi = FakeGoeApi()
        val viewModel = WallboxViewModel(evccApi, goeApi)

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
        val goeApi = FakeGoeApi()
        val viewModel = WallboxViewModel(evccApi, goeApi)

        viewModel.setPhases(3)
        dispatcher.scheduler.runCurrent()

        assertEquals("3", evccApi.lastPhasesSet)
        assertEquals(3, viewModel.uiState.value.phasesConfigured)
    }

    @Test
    fun `setMinCurrent posts the new current and refreshes state`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint(minCurrent = 10))))
        val goeApi = FakeGoeApi()
        val viewModel = WallboxViewModel(evccApi, goeApi)

        viewModel.setMinCurrent(10)
        dispatcher.scheduler.runCurrent()

        assertEquals(10, evccApi.lastMinCurrentSet)
        assertEquals(10, viewModel.uiState.value.minCurrent)
    }
}
```

Note: no separate failure-path tests for `setPhases`/`setMinCurrent` — the error handling they use is the exact same `performLoadpointUpdate` code path already covered by the `setMode failure response` test above, so a duplicate test would assert the same code twice under a different name.

- [ ] **Step 3: Run the tests and verify they pass**

Run: `cd /home/andi/AndroidStudioProjects/ChargeControl/.worktrees/phase-current-controls && ./gradlew testDebugUnitTest --tests "com.example.chargecontrol.ui.WallboxViewModelTest" --tests "com.example.chargecontrol.network.EvccStateResponseTest"`
Expected: `BUILD SUCCESSFUL`, all 10 `WallboxViewModelTest` tests and both `EvccStateResponseTest` tests pass. This also confirms Task 1's test compiles cleanly now that this task's `LoadpointDto` usage is consistent everywhere.

- [ ] **Step 4: Commit**

```bash
cd /home/andi/AndroidStudioProjects/ChargeControl/.worktrees/phase-current-controls
git add app/src/main/java/com/example/chargecontrol/ui/WallboxViewModel.kt app/src/test/java/com/example/chargecontrol/ui/WallboxViewModelTest.kt
git commit -m "feat: add setPhases/setMinCurrent, share update logic via performLoadpointUpdate"
```

---

### Task 3: UI — unlock checkbox, phase buttons, current slider

**Files:**
- Modify: `app/src/main/java/com/example/chargecontrol/ui/MainScreen.kt` (replace entire file)
- Modify: `app/src/main/java/com/example/chargecontrol/MainActivity.kt` (small targeted edit — add two parameters to the existing `MainScreen(...)` call)

**Interfaces:**
- Consumes: `WallboxViewModel.setPhases`/`setMinCurrent` (Task 2), `UiState.minCurrent`/`phasesConfigured` (Task 2).
- Produces: `MainScreen` gains two new required parameters `onPhasesSelected: (Int) -> Unit` and `onMinCurrentChanged: (Int) -> Unit` — nothing downstream consumes these further, this is the last task.

This task also generalizes the existing private `ModeButton` composable into `SelectableButton` (adds an `enabled` and a caller-supplied `modifier` param) so the phase buttons reuse it instead of duplicating a near-identical composable — a straight rename plus two new optional parameters, no behavior change for the three existing charge-mode buttons.

- [ ] **Step 1: Replace `MainScreen.kt`**

```kotlin
package com.example.chargecontrol.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
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
    var unlocked by remember { mutableStateOf(false) }
    // Deliberately re-synced from the live value whenever it changes (poll refresh,
    // or the refetch after a successful change) rather than tracked as fully
    // independent drag state — simplest correct behavior for this app's scope.
    // A mid-drag jump is possible only if a poll lands during the ~1-2s a drag
    // takes, which is rare enough at a 7s poll interval not to warrant more
    // machinery (e.g. tracking isDragging separately) here.
    var sliderValue by remember(uiState.minCurrent) { mutableStateOf(uiState.minCurrent) }

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

        Text("Ladestrom: $sliderValue A", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = sliderValue.toFloat(),
            onValueChange = { sliderValue = it.roundToInt() },
            onValueChangeFinished = { onMinCurrentChanged(sliderValue) },
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
        modifier = modifier.height(56.dp),
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
```

- [ ] **Step 2: Update the `MainScreen(...)` call in `MainActivity.kt`**

In `app/src/main/java/com/example/chargecontrol/MainActivity.kt`, find:

```kotlin
    MainScreen(
        uiState = uiState,
        onModeSelected = viewModel::setMode,
        onStop = { viewModel.setMode("off") },
        onErrorShown = viewModel::errorShown
    )
```

Replace with:

```kotlin
    MainScreen(
        uiState = uiState,
        onModeSelected = viewModel::setMode,
        onStop = { viewModel.setMode("off") },
        onErrorShown = viewModel::errorShown,
        onPhasesSelected = viewModel::setPhases,
        onMinCurrentChanged = viewModel::setMinCurrent
    )
```

No other change to `MainActivity.kt` is needed — the permission gate, lifecycle wiring, and ViewModel construction from the previous plan are untouched.

- [ ] **Step 3: Full build and test verification**

Run: `cd /home/andi/AndroidStudioProjects/ChargeControl/.worktrees/phase-current-controls && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

Run: `cd /home/andi/AndroidStudioProjects/ChargeControl/.worktrees/phase-current-controls && ./gradlew testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests (Task 1, Task 2, and the pre-existing suite) pass.

- [ ] **Step 4: Commit**

```bash
cd /home/andi/AndroidStudioProjects/ChargeControl/.worktrees/phase-current-controls
git add app/src/main/java/com/example/chargecontrol/ui/MainScreen.kt app/src/main/java/com/example/chargecontrol/MainActivity.kt
git commit -m "feat: add phase and min-current controls behind an unlock checkbox"
```

- [ ] **Step 5: On-device manual verification**

Build and install: `./gradlew installDebug` (device connected via `adb devices`, on the same WLAN as evcc).

Checklist:
- Phase buttons and slider are visible but grayed out/non-interactive when the checkbox is unchecked.
- Checking the checkbox enables both; the active phase (matching evcc's current `phasesConfigured`) is visually highlighted the same way the charge-mode buttons highlight the active mode.
- Tapping a phase button updates evcc (check the evcc web UI or watch the app's own status card update within ~1 poll cycle) and the button's highlighted state updates accordingly.
- Dragging the slider does NOT call the API on every tick (no rapid-fire evcc requests while dragging) — only releasing it does.
- Releasing the slider updates evcc's `mincurrent` and the status area continues to reflect real state as before.
- Unchecking the checkbox re-disables both controls without reverting any value already sent to evcc.
- Restarting the app resets the checkbox to unchecked (no persistence), as decided.
