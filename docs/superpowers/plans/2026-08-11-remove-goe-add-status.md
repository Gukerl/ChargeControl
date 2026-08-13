# Remove go-e, Add Status Fields, Passive App Start — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove go-e entirely from ChargeControl (making app start purely read-only in the process), and add "Fahrzeug verbunden" and "Ladestand" status lines, per [docs/superpowers/specs/2026-08-11-remove-goe-add-status-design.md](../specs/2026-08-11-remove-goe-add-status-design.md).

**Architecture:** No new architectural concepts. Task 1 removes an entire subsystem (go-e) consistently across all 8 files that touch it in one pass — a partial removal would leave the app in a broken/inconsistent state, so it isn't split further. Task 2 is a purely additive change (one new DTO field, one new `UiState` field, two new status lines) layered on top of Task 1's already-cleaned-up files.

**Tech Stack:** No changes — same as the existing project (Kotlin, Compose, Retrofit, OkHttp, kotlinx.coroutines).

## Global Constraints

- go-e is removed completely: no `GoeApi`, no go-e Retrofit client, no go-e IP setting, no go-e-specific error messages. The app controls exclusively via evcc.
- `WallboxViewModel.onStart()` sends zero control commands — it only reads state (`fetchState()`) before starting the poll loop. No release call, no mode change, nothing beyond a GET.
- `vehicleSoc` (battery state of charge) is parsed as `Double` from evcc (like `minCurrent`/`offeredCurrent` — evcc's wire format is floating point), rounded to `Int` only at the `UiState` boundary.
- The existing charging-status line (`"Lädt gerade"` / `"Verbunden, lädt nicht"` / `"Nicht verbunden"`) stays unchanged; the new `"Fahrzeug verbunden: Ja/Nein"` line is additional, not a replacement.
- Project root: `/home/andi/AndroidStudioProjects/ChargeControl`, package `com.example.chargecontrol`, on branch `master`.

---

### Task 1: Remove go-e entirely, make app start read-only

**Files:**
- Delete: `app/src/main/java/com/example/chargecontrol/network/GoeApi.kt`
- Modify: `app/src/main/java/com/example/chargecontrol/network/NetworkModule.kt` (replace entire file)
- Modify: `app/src/main/java/com/example/chargecontrol/ui/WallboxViewModel.kt` (replace entire file)
- Modify: `app/src/main/java/com/example/chargecontrol/MainActivity.kt` (one-line targeted edit — see Step 4)
- Modify: `app/src/main/java/com/example/chargecontrol/SettingsRepository.kt` (replace entire file)
- Modify: `app/src/main/java/com/example/chargecontrol/ui/SettingsScreen.kt` (replace entire file)
- Modify: `app/src/test/java/com/example/chargecontrol/ui/WallboxViewModelTest.kt` (replace entire file)
- Modify: `app/src/test/java/com/example/chargecontrol/SettingsRepositoryTest.kt` (replace entire file)

**Interfaces:**
- Consumes: nothing new.
- Produces: `WallboxViewModel(evccApi: EvccApi)` — single-argument constructor (was two) — consumed by Task 2 (unchanged further) and already reflected in this task's own `MainActivity.kt` edit. `SettingsRepository` loses `goeHost`/`DEFAULT_GOE_HOST` — no other task depends on them.

This task touches 8 files in one pass because go-e removal is one atomic behavior: any intermediate state with only some files updated would leave the app referencing a type or constructor that no longer matches across files. Do not split this task.

- [ ] **Step 1: Delete `GoeApi.kt`**

```bash
rm app/src/main/java/com/example/chargecontrol/network/GoeApi.kt
```

- [ ] **Step 2: Replace `NetworkModule.kt`**

```kotlin
package com.example.chargecontrol.network

import com.example.chargecontrol.Config
import com.example.chargecontrol.SettingsRepository
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

private const val PLACEHOLDER_EVCC_BASE_URL = "http://chargecontrol.invalid:7070/api/"

internal class DynamicHostInterceptor(
    private val hostProvider: () -> String,
    private val portProvider: () -> Int
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val newUrl = original.url.newBuilder()
            .host(hostProvider())
            .port(portProvider())
            .build()
        return chain.proceed(original.newBuilder().url(newUrl).build())
    }
}

object NetworkModule {
    val json = Json { ignoreUnknownKeys = true }

    private fun baseClientBuilder(): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .connectTimeout(Config.HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(Config.HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private val evccOkHttpClient = baseClientBuilder()
        .addInterceptor(
            DynamicHostInterceptor(
                hostProvider = { SettingsRepository.evccHost },
                portProvider = { SettingsRepository.evccPort }
            )
        )
        .build()

    val evccApi: EvccApi by lazy {
        Retrofit.Builder()
            .baseUrl(PLACEHOLDER_EVCC_BASE_URL)
            .client(evccOkHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(EvccApi::class.java)
    }
}
```

Note: `DynamicHostInterceptor` stays `internal` and unchanged — `DynamicHostInterceptorTest.kt` (existing, from a prior plan) constructs it directly with synthetic providers unrelated to go-e, so it needs no changes and keeps passing.

- [ ] **Step 3: Replace `WallboxViewModel.kt`**

```kotlin
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
                _uiState.update { it.copy(errorMessage = "evcc nicht erreichbar") }
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
            _uiState.update {
                it.copy(
                    mode = loadpoint.mode,
                    phasesConfigured = loadpoint.phasesConfigured,
                    offeredCurrent = loadpoint.offeredCurrent,
                    minCurrent = loadpoint.minCurrent.roundToInt(),
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

- [ ] **Step 4: Patch `MainActivity.kt`'s ViewModel construction**

In `app/src/main/java/com/example/chargecontrol/MainActivity.kt`, find:

```kotlin
    val viewModel: WallboxViewModel = viewModel(
        factory = viewModelFactory {
            initializer { WallboxViewModel(NetworkModule.evccApi, NetworkModule.goeApi) }
        }
    )
```

Replace with:

```kotlin
    val viewModel: WallboxViewModel = viewModel(
        factory = viewModelFactory {
            initializer { WallboxViewModel(NetworkModule.evccApi) }
        }
    )
```

Nothing else in this file changes.

- [ ] **Step 5: Replace `SettingsRepository.kt`**

```kotlin
package com.example.chargecontrol

import android.content.Context

object SettingsRepository {
    const val DEFAULT_EVCC_HOST = "192.168.224.24"
    const val DEFAULT_EVCC_PORT = 7070

    private const val KEY_EVCC_HOST = "evcc_host"
    private const val KEY_EVCC_PORT = "evcc_port"

    @Volatile
    private lateinit var store: KeyValueStore

    fun init(context: Context) {
        if (::store.isInitialized) return
        store = SharedPreferencesKeyValueStore(context)
    }

    internal fun initWithStore(store: KeyValueStore) {
        this.store = store
    }

    var evccHost: String
        get() = store.getString(KEY_EVCC_HOST, DEFAULT_EVCC_HOST)
        set(value) = store.putString(KEY_EVCC_HOST, value)

    var evccPort: Int
        get() = store.getInt(KEY_EVCC_PORT, DEFAULT_EVCC_PORT)
        set(value) = store.putInt(KEY_EVCC_PORT, value)
}
```

- [ ] **Step 6: Replace `SettingsScreen.kt`**

```kotlin
package com.example.chargecontrol.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.chargecontrol.SettingsRepository
import com.example.chargecontrol.isValidIpv4
import com.example.chargecontrol.isValidPort

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var evccHost by rememberSaveable { mutableStateOf(SettingsRepository.evccHost) }
    var evccPort by rememberSaveable { mutableStateOf(SettingsRepository.evccPort.toString()) }

    val evccHostValid = isValidIpv4(evccHost)
    val evccPortValid = isValidPort(evccPort)
    val canSave = evccHostValid && evccPortValid

    val context = LocalContext.current
    val appVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("evcc", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = evccHost,
                    onValueChange = { evccHost = it.trim() },
                    label = { Text("IP-Adresse") },
                    isError = !evccHostValid,
                    supportingText = { if (!evccHostValid) Text("Ungültige oder keine private IPv4-Adresse (10.x, 172.16-31.x, 192.168.x)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = evccPort,
                    onValueChange = { evccPort = it.trim() },
                    label = { Text("Port") },
                    isError = !evccPortValid,
                    supportingText = { if (!evccPortValid) Text("Port muss zwischen 1 und 65535 liegen") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Button(
                onClick = {
                    SettingsRepository.evccHost = evccHost
                    SettingsRepository.evccPort = evccPort.toInt()
                    onBack()
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Speichern")
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Über", style = MaterialTheme.typography.titleMedium)
                    Text("ChargeControl" + (appVersion?.let { " $it" } ?: ""))
                    Text("Entwickelt von Andreas Hutter")
                }
            }
        }
    }
}
```

- [ ] **Step 7: Replace `WallboxViewModelTest.kt`**

```kotlin
package com.example.chargecontrol.ui

import com.example.chargecontrol.Config
import com.example.chargecontrol.network.EvccApi
import com.example.chargecontrol.network.EvccStateResponse
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
        minCurrent: Double = 6.0
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

    @Test
    fun `onStart loads initial state without sending any control command`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint(mode = "pv", offeredCurrent = 9.5))))
        val viewModel = WallboxViewModel(evccApi)

        viewModel.onStart()
        dispatcher.scheduler.runCurrent()

        assertEquals("pv", viewModel.uiState.value.mode)
        assertEquals(9.5, viewModel.uiState.value.offeredCurrent, 0.0)
        assertEquals(false, viewModel.uiState.value.isLoading)
        assertEquals(1, evccApi.fetchCount)

        viewModel.onStop()
    }

    @Test
    fun `fetchState failure surfaces an error message`() = runTest {
        val evccApi = FakeEvccApi(
            state = EvccStateResponse(listOf(loadpoint(mode = "now"))),
            stateError = IOException("offline")
        )
        val viewModel = WallboxViewModel(evccApi)

        viewModel.onStart()
        dispatcher.scheduler.runCurrent()

        assertEquals("evcc nicht erreichbar", viewModel.uiState.value.errorMessage)

        viewModel.onStop()
    }

    @Test
    fun `setMode posts the new mode and refreshes state`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint(mode = "minpv"))))
        val viewModel = WallboxViewModel(evccApi)

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
        val viewModel = WallboxViewModel(evccApi)

        viewModel.setMode("now")
        dispatcher.scheduler.runCurrent()

        assertEquals("evcc-Fehler (500)", viewModel.uiState.value.errorMessage)
        assertEquals(1, evccApi.fetchCount)
    }

    @Test
    fun `onStop cancels polling so no further state fetches happen`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint())))
        val viewModel = WallboxViewModel(evccApi)

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
        val viewModel = WallboxViewModel(evccApi)

        viewModel.onStart()
        dispatcher.scheduler.runCurrent()

        assertEquals(false, viewModel.uiState.value.hasData)
        assertEquals(false, viewModel.uiState.value.isLoading)

        viewModel.onStop()
    }

    @Test
    fun `empty loadpoints list surfaces an error instead of hanging`() = runTest {
        val evccApi = FakeEvccApi(state = EvccStateResponse(emptyList()))
        val viewModel = WallboxViewModel(evccApi)

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
        val viewModel = WallboxViewModel(evccApi)

        viewModel.onStart()
        dispatcher.scheduler.runCurrent()

        assertEquals(false, viewModel.uiState.value.isLoading)
        assertEquals("Unerwartete Antwort von evcc", viewModel.uiState.value.errorMessage)

        viewModel.onStop()
    }

    @Test
    fun `poll loop fetches state again after the poll interval elapses`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint())))
        val viewModel = WallboxViewModel(evccApi)

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
        val viewModel = WallboxViewModel(evccApi)

        viewModel.setPhases(3)
        dispatcher.scheduler.runCurrent()

        assertEquals("3", evccApi.lastPhasesSet)
        assertEquals(3, viewModel.uiState.value.phasesConfigured)
    }

    @Test
    fun `setMinCurrent posts the new current and refreshes state`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint(minCurrent = 10.0))))
        val viewModel = WallboxViewModel(evccApi)

        viewModel.setMinCurrent(10)
        dispatcher.scheduler.runCurrent()

        assertEquals(10, evccApi.lastMinCurrentSet)
        assertEquals(10, viewModel.uiState.value.minCurrent)
    }
}
```

Note the renamed first test (`onStart loads initial state without sending any control command`, was `onStart releases the wallbox and loads initial state`) and its new `assertEquals(1, evccApi.fetchCount)` assertion — this is the test evidence for the "app start is purely read-only" requirement: exactly one `getState()` call, zero writes.

- [ ] **Step 8: Replace `SettingsRepositoryTest.kt`**

```kotlin
package com.example.chargecontrol

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SettingsRepositoryTest {

    private class FakeKeyValueStore : KeyValueStore {
        private val strings = mutableMapOf<String, String>()
        private val ints = mutableMapOf<String, Int>()

        override fun getString(key: String, default: String): String = strings[key] ?: default
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun getInt(key: String, default: Int): Int = ints[key] ?: default
        override fun putInt(key: String, value: Int) { ints[key] = value }
    }

    @Before
    fun setUp() {
        SettingsRepository.initWithStore(FakeKeyValueStore())
    }

    @Test
    fun `returns defaults when nothing has been saved`() {
        assertEquals(SettingsRepository.DEFAULT_EVCC_HOST, SettingsRepository.evccHost)
        assertEquals(SettingsRepository.DEFAULT_EVCC_PORT, SettingsRepository.evccPort)
    }

    @Test
    fun `persists and returns saved values`() {
        SettingsRepository.evccHost = "10.0.0.5"
        SettingsRepository.evccPort = 8080

        assertEquals("10.0.0.5", SettingsRepository.evccHost)
        assertEquals(8080, SettingsRepository.evccPort)
    }

    @Test
    fun `defaults match the previously-hardcoded values`() {
        assertEquals("192.168.224.24", SettingsRepository.evccHost)
        assertEquals(7070, SettingsRepository.evccPort)
    }
}
```

- [ ] **Step 9: Verify the project builds and all tests pass**

Run: `cd /home/andi/AndroidStudioProjects/ChargeControl && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

Run: `cd /home/andi/AndroidStudioProjects/ChargeControl && ./gradlew testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests pass (the full suite, since this task fully removes go-e everywhere it was referenced — there must be zero remaining references to `GoeApi`/`goeApi`/`goeHost` anywhere in `app/src/main` or `app/src/test` after this task; grep for `[Gg]o.?[Ee]` under `app/src` to confirm no stragglers besides comments/strings that aren't code references, e.g. the design doc or unrelated text).

- [ ] **Step 10: Commit**

```bash
cd /home/andi/AndroidStudioProjects/ChargeControl
git add -A
git commit -m "feat: remove go-e entirely, make app start purely read-only"
```

---

### Task 2: Add `vehicleSoc` and the "Fahrzeug verbunden" status line

**Files:**
- Modify: `app/src/main/java/com/example/chargecontrol/network/EvccApi.kt` (replace entire file)
- Modify: `app/src/main/java/com/example/chargecontrol/ui/WallboxViewModel.kt` (replace entire file)
- Modify: `app/src/main/java/com/example/chargecontrol/ui/MainScreen.kt` (replace entire file)
- Modify: `app/src/test/java/com/example/chargecontrol/network/EvccStateResponseTest.kt` (replace entire file)
- Modify: `app/src/test/java/com/example/chargecontrol/ui/WallboxViewModelTest.kt` (two targeted edits — see Steps 4-5)

**Interfaces:**
- Consumes: `WallboxViewModel(evccApi: EvccApi)` (Task 1) — unchanged constructor, this task only adds a field to `UiState`.
- Produces: `LoadpointDto.vehicleSoc: Double`; `UiState.vehicleSoc: Int` — nothing further downstream consumes these beyond this task's own `MainScreen.kt`.

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
    val minCurrent: Double,
    val vehicleSoc: Double
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

- [ ] **Step 2: Replace `WallboxViewModel.kt`** (adds `vehicleSoc: Int = 0` to `UiState`, populates it in `fetchState()`; everything else identical to Task 1's version)

```kotlin
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
                _uiState.update { it.copy(errorMessage = "evcc nicht erreichbar") }
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
            _uiState.update {
                it.copy(
                    mode = loadpoint.mode,
                    phasesConfigured = loadpoint.phasesConfigured,
                    offeredCurrent = loadpoint.offeredCurrent,
                    minCurrent = loadpoint.minCurrent.roundToInt(),
                    connected = loadpoint.connected,
                    charging = loadpoint.charging,
                    vehicleSoc = loadpoint.vehicleSoc.roundToInt(),
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

- [ ] **Step 3: Replace `MainScreen.kt`** (adds two new `Text` lines to `StatusCard`'s data-available branch, right after the existing "Ladestrom" line; everything else identical to the current file)

```kotlin
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
                Text("Fahrzeug verbunden: ${if (uiState.connected) "Ja" else "Nein"}")
                Text("Ladestand: ${uiState.vehicleSoc} %")
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
```

- [ ] **Step 4: Replace `EvccStateResponseTest.kt`** (adds `vehicleSoc` to both payloads and one assertion)

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
                  "vehicleSoc": 82,
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
        assertEquals(6.0, loadpoint.minCurrent, 0.0)
        assertEquals(82.0, loadpoint.vehicleSoc, 0.0)
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
                  "vehicleSoc": 0,
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

- [ ] **Step 5: Update `WallboxViewModelTest.kt`'s `loadpoint()` helper and add a `vehicleSoc` test**

In `app/src/test/java/com/example/chargecontrol/ui/WallboxViewModelTest.kt`, find the `loadpoint()` helper (written in Task 1):

```kotlin
    private fun loadpoint(
        mode: String = "now",
        phasesConfigured: Int = 0,
        offeredCurrent: Double = 0.0,
        connected: Boolean = false,
        charging: Boolean = false,
        minCurrent: Double = 6.0
    ) = LoadpointDto(mode, phasesConfigured, offeredCurrent, connected, charging, minCurrent)
```

Replace with:

```kotlin
    private fun loadpoint(
        mode: String = "now",
        phasesConfigured: Int = 0,
        offeredCurrent: Double = 0.0,
        connected: Boolean = false,
        charging: Boolean = false,
        minCurrent: Double = 6.0,
        vehicleSoc: Double = 0.0
    ) = LoadpointDto(mode, phasesConfigured, offeredCurrent, connected, charging, minCurrent, vehicleSoc)
```

Then add this new test at the end of the class, right before the final closing `}`:

```kotlin

    @Test
    fun `fetchState populates vehicleSoc rounded to the nearest percent`() = runTest {
        val evccApi = FakeEvccApi(EvccStateResponse(listOf(loadpoint(vehicleSoc = 82.6))))
        val viewModel = WallboxViewModel(evccApi)

        viewModel.onStart()
        dispatcher.scheduler.runCurrent()

        assertEquals(83, viewModel.uiState.value.vehicleSoc)

        viewModel.onStop()
    }
```

- [ ] **Step 6: Verify the project builds and all tests pass**

Run: `cd /home/andi/AndroidStudioProjects/ChargeControl && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

Run: `cd /home/andi/AndroidStudioProjects/ChargeControl && ./gradlew testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests pass (full suite plus the new `vehicleSoc` rounding test).

- [ ] **Step 7: Commit**

```bash
cd /home/andi/AndroidStudioProjects/ChargeControl
git add app/src/main/java/com/example/chargecontrol/network/EvccApi.kt app/src/main/java/com/example/chargecontrol/ui/WallboxViewModel.kt app/src/main/java/com/example/chargecontrol/ui/MainScreen.kt app/src/test/java/com/example/chargecontrol/network/EvccStateResponseTest.kt app/src/test/java/com/example/chargecontrol/ui/WallboxViewModelTest.kt
git commit -m "feat: add vehicleSoc and Fahrzeug-verbunden status line"
```

- [ ] **Step 8: On-device manual verification**

Build and install: `./gradlew installDebug` (device connected via `adb devices`, on the same WLAN as evcc).

Checklist:
- App launch shows status within ~1s, with no go-e-related network call ever fired (confirm via the evcc web UI or logs that only evcc was contacted) and no mode/phase/current change happens automatically.
- Status card shows the two new lines "Fahrzeug verbunden: Ja/Nein" and "Ladestand: X %", matching what the evcc web UI reports for the same loadpoint.
- The Settings screen no longer shows a go-e IP field — only evcc IP + port.
- All existing behavior (mode buttons, phase/current controls, error handling) still works exactly as before.
