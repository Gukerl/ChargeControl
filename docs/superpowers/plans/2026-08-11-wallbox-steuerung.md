# Wallbox-Steuerung (ChargeControl) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Android app described in [docs/superpowers/specs/2026-08-11-wallbox-steuerung-design.md](../specs/2026-08-11-wallbox-steuerung-design.md) — a 4-button Compose app that controls an evcc loadpoint over the local WLAN and shows its live status.

**Architecture:** MVVM. Two thin Retrofit interfaces (`EvccApi`, `GoeApi`) talk to the two local devices. `WallboxViewModel` owns a `StateFlow<UiState>`, does the go-e release + evcc state polling, and exposes `setMode()` for the four buttons. `MainScreen` is a stateless Composable driven entirely by `UiState`.

**Tech Stack:** Kotlin 2.2.10, Jetpack Compose (BOM 2025.12.00) + Material 3, Retrofit 2.11.0 + OkHttp 4.12.0 + kotlinx.serialization 1.7.3, kotlinx.coroutines 1.9.0, androidx.lifecycle 2.11.0 (viewmodel-compose, runtime-compose).

## Global Constraints

- evcc base URL: `http://192.168.224.24:7070/api/`, loadpoint ID `1` (verified live: evcc 0.313.2, loadpoint 1 = "go-e Box").
- go-e base URL: `http://192.168.224.245/`.
- evcc REST endpoints (verified against evcc docs + live instance): `GET state` (no `result` envelope — `loadpoints` is a top-level key), `POST loadpoints/{id}/mode/{mode}` with `mode` ∈ {off, now, minpv, pv}.
- go-e release endpoint: `GET api/set?frc=0`.
- Cleartext HTTP must be permitted only for `192.168.224.24` and `192.168.224.245` via Network Security Config — no blanket `usesCleartextTraffic`.
- Polling interval: 7000 ms, only while the app is started (started in `ON_START`, stopped in `ON_STOP`).
- Status display shows `offeredCurrent` (live ist-Wert), not `minCurrent`/`maxCurrent`.
- No retries, no crash, no UI blocking on network errors — surface a short error message and keep the last known state visible.
- No phase/current controls, no ioBroker, no login, no RFID management — out of scope for this plan.
- Project root: `/home/andi/AndroidStudioProjects/ChargeControl`, package `com.example.chargecontrol`, minSdk 24, targetSdk/compileSdk 37. Git repo already initialized with one commit (`f15f3cf`) containing the scaffold + design doc.

---

### Task 1: Gradle dependencies, network security config, manifest

**Files:**
- Modify: `gradle/libs.versions.toml` (replace entire file)
- Modify: `app/build.gradle.kts` (replace entire file)
- Create: `app/src/main/res/xml/network_security_config.xml`
- Modify: `app/src/main/AndroidManifest.xml` (replace entire file)

**Interfaces:**
- Produces: Gradle version catalog aliases `libs.retrofit`, `libs.retrofit.kotlinx.serialization.converter`, `libs.kotlinx.serialization.json`, `libs.kotlinx.coroutines.android`, `libs.kotlinx.coroutines.test`, `libs.okhttp`, `libs.androidx.lifecycle.viewmodel.ktx`, `libs.androidx.lifecycle.viewmodel.compose`, `libs.androidx.lifecycle.runtime.compose`, plugin alias `libs.plugins.kotlin.serialization` — all consumed by later tasks.

- [ ] **Step 1: Replace `gradle/libs.versions.toml`**

```toml
[versions]
agp = "9.3.1"
coreKtx = "1.19.0"
junit = "4.13.2"
junitVersion = "1.3.0"
espressoCore = "3.7.0"
lifecycleRuntimeKtx = "2.11.0"
activityCompose = "1.13.0"
kotlin = "2.2.10"
composeBom = "2025.12.00"
retrofit = "2.11.0"
retrofitKotlinxSerializationConverter = "1.0.0"
kotlinxSerializationJson = "1.7.3"
kotlinxCoroutines = "1.9.0"
okhttp = "4.12.0"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-junit = { group = "androidx.test.ext", name = "junit", version.ref = "junitVersion" }
androidx-espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espressoCore" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycleRuntimeKtx" }
androidx-lifecycle-viewmodel-ktx = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-ktx", version.ref = "lifecycleRuntimeKtx" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycleRuntimeKtx" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycleRuntimeKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
androidx-compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-compose-material3-adaptive-navigation-suite = { group = "androidx.compose.material3", name = "material3-adaptive-navigation-suite" }
retrofit = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
retrofit-kotlinx-serialization-converter = { group = "com.jakewharton.retrofit", name = "retrofit2-kotlinx-serialization-converter", version.ref = "retrofitKotlinxSerializationConverter" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerializationJson" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "kotlinxCoroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "kotlinxCoroutines" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

Note: `androidx-compose-material3-adaptive-navigation-suite` is kept for now even though the design drops the nav-suite UI — `MainActivity.kt` still uses it until Task 5 rewrites that file. Removing it here would break the build for Tasks 1-4. Task 5 removes this entry when it removes the last usage.

- [ ] **Step 2: Replace `app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.chargecontrol"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.chargecontrol"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization.converter)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
```

- [ ] **Step 3: Create the network security config**

`app/src/main/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">192.168.224.24</domain>
        <domain includeSubdomains="false">192.168.224.245</domain>
    </domain-config>
</network-security-config>
```

- [ ] **Step 4: Replace `app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:networkSecurityConfig="@xml/network_security_config"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.ChargeControl">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:theme="@style/Theme.ChargeControl"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />

                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```

- [ ] **Step 5: Verify the project still builds**

Run: `cd /home/andi/AndroidStudioProjects/ChargeControl/.worktrees/wallbox-steuerung && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`. No source files changed in this task, only build config — the existing `MainActivity.kt` must still compile unmodified.

- [ ] **Step 6: Commit**

```bash
cd /home/andi/AndroidStudioProjects/ChargeControl/.worktrees/wallbox-steuerung
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/res/xml/network_security_config.xml app/src/main/AndroidManifest.xml
git commit -m "build: add Retrofit/OkHttp/serialization deps, cleartext network security config"
```

---

### Task 2: Config constants + evcc/go-e Retrofit interfaces

**Files:**
- Create: `app/src/main/java/com/example/chargecontrol/Config.kt`
- Create: `app/src/main/java/com/example/chargecontrol/network/EvccApi.kt`
- Create: `app/src/main/java/com/example/chargecontrol/network/GoeApi.kt`
- Create: `app/src/main/java/com/example/chargecontrol/network/NetworkModule.kt`
- Test: `app/src/test/java/com/example/chargecontrol/network/EvccStateResponseTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `Config` object (`EVCC_BASE_URL`, `GOE_BASE_URL`, `LOADPOINT_ID: Int`, `POLL_INTERVAL_MS: Long`, `HTTP_TIMEOUT_SECONDS: Long`); `data class LoadpointDto(mode: String, phasesConfigured: Int, offeredCurrent: Double, connected: Boolean, charging: Boolean)`; `data class EvccStateResponse(loadpoints: List<LoadpointDto>)`; `interface EvccApi { suspend fun getState(): EvccStateResponse; suspend fun setMode(id: Int, mode: String): Response<ResponseBody> }`; `interface GoeApi { suspend fun release(frc: Int): Response<ResponseBody> }`; `object NetworkModule { val json: Json; val evccApi: EvccApi; val goeApi: GoeApi }` — all consumed by Task 3 (ViewModel) and Task 5 (MainActivity wiring).

- [ ] **Step 1: Create `Config.kt`**

```kotlin
package com.example.chargecontrol

object Config {
    const val EVCC_BASE_URL = "http://192.168.224.24:7070/api/"
    const val GOE_BASE_URL = "http://192.168.224.245/"
    const val LOADPOINT_ID = 1
    const val POLL_INTERVAL_MS = 7000L
    const val HTTP_TIMEOUT_SECONDS = 5L
}
```

- [ ] **Step 2: Create `network/EvccApi.kt`**

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
    val charging: Boolean
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
}
```

- [ ] **Step 3: Create `network/GoeApi.kt`**

```kotlin
package com.example.chargecontrol.network

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface GoeApi {
    @GET("api/set")
    suspend fun release(@Query("frc") frc: Int): Response<ResponseBody>
}
```

- [ ] **Step 4: Create `network/NetworkModule.kt`**

```kotlin
package com.example.chargecontrol.network

import com.example.chargecontrol.Config
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

object NetworkModule {
    val json = Json { ignoreUnknownKeys = true }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(Config.HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(Config.HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    val evccApi: EvccApi by lazy {
        Retrofit.Builder()
            .baseUrl(Config.EVCC_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(EvccApi::class.java)
    }

    val goeApi: GoeApi by lazy {
        Retrofit.Builder()
            .baseUrl(Config.GOE_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GoeApi::class.java)
    }
}
```

- [ ] **Step 5: Write the failing test for JSON parsing**

`app/src/test/java/com/example/chargecontrol/network/EvccStateResponseTest.kt`:

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

- [ ] **Step 6: Run the test and verify it passes**

Run: `cd /home/andi/AndroidStudioProjects/ChargeControl/.worktrees/wallbox-steuerung && ./gradlew testDebugUnitTest --tests "com.example.chargecontrol.network.EvccStateResponseTest"`
Expected: `BUILD SUCCESSFUL`, both tests pass. (There's no prior failing state to observe here — the DTOs and the test are new together — so just confirm both tests pass on first run.)

- [ ] **Step 7: Commit**

```bash
cd /home/andi/AndroidStudioProjects/ChargeControl/.worktrees/wallbox-steuerung
git add app/src/main/java/com/example/chargecontrol/Config.kt app/src/main/java/com/example/chargecontrol/network/ app/src/test/java/com/example/chargecontrol/network/
git commit -m "feat: add evcc/go-e Retrofit interfaces and state DTOs"
```

---

### Task 3: WallboxViewModel

**Files:**
- Create: `app/src/main/java/com/example/chargecontrol/ui/WallboxViewModel.kt`
- Test: `app/src/test/java/com/example/chargecontrol/ui/WallboxViewModelTest.kt`

**Interfaces:**
- Consumes: `Config` (`LOADPOINT_ID`, `POLL_INTERVAL_MS`), `EvccApi`, `GoeApi`, `LoadpointDto`, `EvccStateResponse` from Task 2.
- Produces: `data class UiState(mode: String, phasesConfigured: Int, offeredCurrent: Double, connected: Boolean, charging: Boolean, isLoading: Boolean, errorMessage: String?)`; `class WallboxViewModel(evccApi: EvccApi, goeApi: GoeApi) : ViewModel()` with `val uiState: StateFlow<UiState>`, `fun onStart()`, `fun onStop()`, `fun setMode(mode: String)`, `fun errorShown()` — all consumed by Task 5 (MainActivity).

- [ ] **Step 1: Write `ui/WallboxViewModel.kt`**

```kotlin
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
```

- [ ] **Step 2: Write the failing tests**

`app/src/test/java/com/example/chargecontrol/ui/WallboxViewModelTest.kt`:

```kotlin
package com.example.chargecontrol.ui

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
        charging: Boolean = false
    ) = LoadpointDto(mode, phasesConfigured, offeredCurrent, connected, charging)

    private class FakeEvccApi(
        var state: EvccStateResponse,
        var stateError: Throwable? = null,
        var setModeResponse: Response<ResponseBody> = Response.success(null)
    ) : EvccApi {
        var lastModeSet: String? = null
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
}
```

- [ ] **Step 3: Run the tests and verify they pass**

Run: `cd /home/andi/AndroidStudioProjects/ChargeControl/.worktrees/wallbox-steuerung && ./gradlew testDebugUnitTest --tests "com.example.chargecontrol.ui.WallboxViewModelTest"`
Expected: `BUILD SUCCESSFUL`, all 5 tests pass. If `Response.success(null)` or `Response.error(...)` don't compile as written, it's a Retrofit version-API mismatch — check the installed `retrofit` artifact's `Response` companion signatures and adjust the fake construction accordingly; the test intent (success vs. non-2xx response) must stay the same.

- [ ] **Step 4: Commit**

```bash
cd /home/andi/AndroidStudioProjects/ChargeControl/.worktrees/wallbox-steuerung
git add app/src/main/java/com/example/chargecontrol/ui/WallboxViewModel.kt app/src/test/java/com/example/chargecontrol/ui/WallboxViewModelTest.kt
git commit -m "feat: add WallboxViewModel with polling, mode changes, and error surfacing"
```

---

### Task 4: MainScreen Composable

**Files:**
- Create: `app/src/main/java/com/example/chargecontrol/ui/MainScreen.kt`

**Interfaces:**
- Consumes: `UiState` from Task 3.
- Produces: `@Composable fun MainScreen(uiState: UiState, onModeSelected: (String) -> Unit, onStop: () -> Unit, onErrorShown: () -> Unit, modifier: Modifier = Modifier)` — consumed by Task 5 (MainActivity).

- [ ] **Step 1: Write `ui/MainScreen.kt`**

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            StatusCard(uiState)

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ModeButton("PV-Überschuss (minpv)", isActive = uiState.mode == "minpv") {
                    onModeSelected("minpv")
                }
                ModeButton("Nur PV (pv)", isActive = uiState.mode == "pv") {
                    onModeSelected("pv")
                }
                ModeButton("Sofortladen (now)", isActive = uiState.mode == "now") {
                    onModeSelected("now")
                }
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text("Laden stoppen")
                }
            }
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
private fun ModeButton(label: String, isActive: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        enabled = !isActive
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

- [ ] **Step 2: Verify it compiles**

Run: `cd /home/andi/AndroidStudioProjects/ChargeControl/.worktrees/wallbox-steuerung && ./gradlew compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. `MainActivity.kt` is untouched by this task and the navigation-suite dependency is still present (removed only in Task 5), so the whole module compiles cleanly including the new `MainScreen.kt`.

- [ ] **Step 3: Commit**

```bash
cd /home/andi/AndroidStudioProjects/ChargeControl/.worktrees/wallbox-steuerung
git add app/src/main/java/com/example/chargecontrol/ui/MainScreen.kt
git commit -m "feat: add MainScreen composable with status card and mode buttons"
```

---

### Task 5: Wire MainActivity, remove template leftovers, on-device verification

**Files:**
- Modify: `app/src/main/java/com/example/chargecontrol/MainActivity.kt` (replace entire file)
- Modify: `gradle/libs.versions.toml` (remove one line)
- Modify: `app/build.gradle.kts` (remove one line)

**Interfaces:**
- Consumes: `NetworkModule.evccApi`, `NetworkModule.goeApi` (Task 2), `WallboxViewModel` (Task 3), `MainScreen` (Task 4).
- Produces: nothing further consumed — this is the final integration task.

- [ ] **Step 1: Replace `MainActivity.kt`**

```kotlin
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
```

This drops the wizard-generated `NavigationSuiteScaffold`/`AppDestinations`/`Greeting` template code entirely — none of it is used by this single-screen app.

- [ ] **Step 2: Remove the now-unused navigation-suite dependency**

`MainActivity.kt` no longer uses `NavigationSuiteScaffold`, so this is the only remaining reference to the nav-suite artifact — remove it from both files.

In `gradle/libs.versions.toml`, delete this line from `[libraries]`:

```toml
androidx-compose-material3-adaptive-navigation-suite = { group = "androidx.compose.material3", name = "material3-adaptive-navigation-suite" }
```

In `app/build.gradle.kts`, delete this line from `dependencies { ... }`:

```kotlin
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
```

- [ ] **Step 3: Full build verification**

Run: `cd /home/andi/AndroidStudioProjects/ChargeControl/.worktrees/wallbox-steuerung && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`. If `viewModelFactory`/`initializer` fail to resolve from `androidx.lifecycle.viewmodel`, or `LocalLifecycleOwner`/`collectAsStateWithLifecycle` fail to resolve from `androidx.lifecycle.compose`, check the actually-resolved `androidx.lifecycle` artifact version's package layout (`./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep lifecycle`) and fix the import paths — the runtime behavior (start/stop polling tied to `ON_START`/`ON_STOP`, ViewModel constructed with the two API instances) must stay the same.

- [ ] **Step 4: Run the full unit test suite**

Run: `cd /home/andi/AndroidStudioProjects/ChargeControl/.worktrees/wallbox-steuerung && ./gradlew testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests from Task 2 and Task 3 still pass.

- [ ] **Step 5: Install and manually verify on a device on the home WLAN**

Run: `cd /home/andi/AndroidStudioProjects/ChargeControl/.worktrees/wallbox-steuerung && ./gradlew installDebug` (with a device/emulator connected via `adb devices`, on the same WLAN as evcc/go-e for the live checks below).

Manual checklist against the spec's acceptance criteria:
- App launch shows a short loading state, then Modus/Phasen/Ladestrom populate within ~1s (confirms `frc=0` + initial `getState()` both fired on start).
- Tapping `minpv` / `pv` / `now` updates the status card to the new mode within a couple seconds, and the tapped button becomes visually disabled (active-state indicator) while the others stay enabled.
- Tapping "Laden stoppen" sets mode to `off` and the status card reflects it.
- Changing the mode via the evcc web UI directly (not through the app) is reflected in the app within one poll interval (~7s) without touching any button.
- Turn off WLAN on the device: a short Snackbar error appears, the app doesn't crash, the last known status stays visible, and it auto-recovers once WLAN is back on.
- Toggle system dark mode: the app follows it immediately (already handled by the existing `ChargeControlTheme`, just confirm no regression).
- Put the app in the background (home button) and check `adb shell dumpsys activity <package>`-level behavior or simply reason from logs/breakpoints that no further network calls happen while backgrounded, then bring it back to the foreground and confirm polling resumes.

- [ ] **Step 6: Commit**

```bash
cd /home/andi/AndroidStudioProjects/ChargeControl/.worktrees/wallbox-steuerung
git add app/src/main/java/com/example/chargecontrol/MainActivity.kt gradle/libs.versions.toml app/build.gradle.kts
git commit -m "feat: wire MainActivity to WallboxViewModel and MainScreen, drop template nav scaffold"
```
