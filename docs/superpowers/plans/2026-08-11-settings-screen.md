# Settings Screen (evcc/go-e IP Config + About) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a settings screen that lets the user change and persist the evcc host:port and go-e host at runtime, per [docs/superpowers/specs/2026-08-11-settings-screen-design.md](../specs/2026-08-11-settings-screen-design.md).

**Architecture:** A new `SettingsRepository` singleton wraps `SharedPreferences` for the three persisted values. `NetworkModule`'s Retrofit clients get a placeholder `baseUrl` plus an `okhttp3.Interceptor` that rewrites each request's host/port from `SettingsRepository` at send-time — so `evccApi`/`goeApi` stay long-lived singletons and every future request automatically uses the latest saved value, with no ViewModel/app restart needed. A new `SettingsScreen` composable provides the editing UI plus an "Über" section; `MainActivity` gets a minimal two-state local screen switch (no navigation library needed for two screens).

**Tech Stack:** Same as the existing project (Kotlin, Compose, Retrofit, OkHttp, kotlinx.coroutines), plus one new dependency: `androidx.compose.material:material-icons-core` (BOM-managed, no explicit version needed) for the settings-gear and back-arrow icons.

## Global Constraints

- Defaults for all three settings match the previously-hardcoded values, so existing behavior is unchanged until the user explicitly edits something: evcc host `192.168.224.24`, evcc port `7070`, go-e host `192.168.224.245`.
- Only IPv4 addresses are supported (no hostnames) — matches the original spec's assumption of statically-assigned IPs.
- Save is blocked client-side until all three fields pass validation (IPv4 format for both hosts, 1–65535 for the port); invalid fields show inline error text.
- Saving takes effect immediately (next request/poll cycle) — no "restart required" message, no app restart.
- Network security config is broadened to general cleartext (`<base-config cleartextTrafficPermitted="true" />`) — agreed trade-off with the user, since Android's Network Security Config cannot express "cleartext allowed for whatever IP is currently configured."
- `Config.LOADPOINT_ID`, `POLL_INTERVAL_MS`, `HTTP_TIMEOUT_SECONDS` are unchanged and out of scope for this plan.
- Project root: `/home/andi/AndroidStudioProjects/ChargeControl`, package `com.example.chargecontrol`, on branch `master`.

---

### Task 1: `IpValidation` + `SettingsRepository`

**Files:**
- Create: `app/src/main/java/com/example/chargecontrol/IpValidation.kt`
- Create: `app/src/main/java/com/example/chargecontrol/KeyValueStore.kt`
- Create: `app/src/main/java/com/example/chargecontrol/SettingsRepository.kt`
- Test: `app/src/test/java/com/example/chargecontrol/IpValidationTest.kt`
- Test: `app/src/test/java/com/example/chargecontrol/SettingsRepositoryTest.kt`

**Interfaces:**
- Produces: `fun isValidIpv4(value: String): Boolean`, `fun isValidPort(value: String): Boolean` — consumed by Task 3 (`SettingsScreen`). `object SettingsRepository` with `fun init(context: Context)`, `var evccHost: String`, `var evccPort: Int`, `var goeHost: String`, plus public constants `DEFAULT_EVCC_HOST`, `DEFAULT_EVCC_PORT`, `DEFAULT_GOE_HOST` — consumed by Task 2 (`NetworkModule`), Task 3 (`SettingsScreen`), and Task 4 (`MainActivity`'s `init` call).

`SettingsRepository` stores its three values through a small `KeyValueStore` interface rather than talking to `SharedPreferences` directly — this is the seam that makes it unit-testable with a fake, the same fakes-over-mocks approach already used for `EvccApi`/`GoeApi` in `WallboxViewModelTest`, instead of pulling in a framework like Robolectric just to get a real `SharedPreferences` instance in a JVM test.

- [ ] **Step 1: Create `IpValidation.kt`**

```kotlin
package com.example.chargecontrol

private val IPV4_REGEX = Regex(
    "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
)

fun isValidIpv4(value: String): Boolean = IPV4_REGEX.matches(value)

fun isValidPort(value: String): Boolean {
    val port = value.toIntOrNull() ?: return false
    return port in 1..65535
}
```

- [ ] **Step 2: Create `KeyValueStore.kt`**

```kotlin
package com.example.chargecontrol

import android.content.Context
import androidx.core.content.edit

interface KeyValueStore {
    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
}

class SharedPreferencesKeyValueStore(context: Context) : KeyValueStore {
    private val prefs = context.applicationContext
        .getSharedPreferences("chargecontrol_settings", Context.MODE_PRIVATE)

    override fun getString(key: String, default: String): String =
        prefs.getString(key, default) ?: default

    override fun putString(key: String, value: String) {
        prefs.edit { putString(key, value) }
    }

    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)

    override fun putInt(key: String, value: Int) {
        prefs.edit { putInt(key, value) }
    }
}
```

- [ ] **Step 3: Create `SettingsRepository.kt`**

```kotlin
package com.example.chargecontrol

import android.content.Context

object SettingsRepository {
    const val DEFAULT_EVCC_HOST = "192.168.224.24"
    const val DEFAULT_EVCC_PORT = 7070
    const val DEFAULT_GOE_HOST = "192.168.224.245"

    private const val KEY_EVCC_HOST = "evcc_host"
    private const val KEY_EVCC_PORT = "evcc_port"
    private const val KEY_GOE_HOST = "goe_host"

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

    var goeHost: String
        get() = store.getString(KEY_GOE_HOST, DEFAULT_GOE_HOST)
        set(value) = store.putString(KEY_GOE_HOST, value)
}
```

`initWithStore` is `internal` (not `private`) specifically so `SettingsRepositoryTest` — compiled as part of the same Gradle module's test source set — can inject a fake store without going through a real `Context`. Production code only ever calls `init(context)`.

- [ ] **Step 4: Write the tests**

`app/src/test/java/com/example/chargecontrol/IpValidationTest.kt`:

```kotlin
package com.example.chargecontrol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpValidationTest {

    @Test
    fun `accepts valid IPv4 addresses`() {
        assertTrue(isValidIpv4("192.168.224.24"))
        assertTrue(isValidIpv4("0.0.0.0"))
        assertTrue(isValidIpv4("255.255.255.255"))
        assertTrue(isValidIpv4("10.0.0.1"))
    }

    @Test
    fun `rejects invalid IPv4 addresses`() {
        assertFalse(isValidIpv4("256.1.1.1"))
        assertFalse(isValidIpv4("192.168.1"))
        assertFalse(isValidIpv4("192.168.1.1.1"))
        assertFalse(isValidIpv4("abc.def.ghi.jkl"))
        assertFalse(isValidIpv4(""))
    }

    @Test
    fun `accepts valid ports`() {
        assertTrue(isValidPort("1"))
        assertTrue(isValidPort("7070"))
        assertTrue(isValidPort("65535"))
    }

    @Test
    fun `rejects invalid ports`() {
        assertFalse(isValidPort("0"))
        assertFalse(isValidPort("65536"))
        assertFalse(isValidPort("-1"))
        assertFalse(isValidPort("abc"))
        assertFalse(isValidPort(""))
    }
}
```

`app/src/test/java/com/example/chargecontrol/SettingsRepositoryTest.kt`:

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
        assertEquals(SettingsRepository.DEFAULT_GOE_HOST, SettingsRepository.goeHost)
    }

    @Test
    fun `persists and returns saved values`() {
        SettingsRepository.evccHost = "10.0.0.5"
        SettingsRepository.evccPort = 8080
        SettingsRepository.goeHost = "10.0.0.6"

        assertEquals("10.0.0.5", SettingsRepository.evccHost)
        assertEquals(8080, SettingsRepository.evccPort)
        assertEquals("10.0.0.6", SettingsRepository.goeHost)
    }
}
```

Each test calls `initWithStore` with a fresh `FakeKeyValueStore` in `@Before`, so the two tests don't leak state into each other despite `SettingsRepository` being a singleton `object`.

- [ ] **Step 5: Run the tests and verify they pass**

Run: `cd /home/andi/AndroidStudioProjects/ChargeControl && ./gradlew testDebugUnitTest --tests "com.example.chargecontrol.IpValidationTest" --tests "com.example.chargecontrol.SettingsRepositoryTest"`
Expected: `BUILD SUCCESSFUL`, all 6 tests pass.

- [ ] **Step 6: Commit**

```bash
cd /home/andi/AndroidStudioProjects/ChargeControl
git add app/src/main/java/com/example/chargecontrol/IpValidation.kt app/src/main/java/com/example/chargecontrol/KeyValueStore.kt app/src/main/java/com/example/chargecontrol/SettingsRepository.kt app/src/test/java/com/example/chargecontrol/IpValidationTest.kt app/src/test/java/com/example/chargecontrol/SettingsRepositoryTest.kt
git commit -m "feat: add IPv4/port validation and SettingsRepository"
```

---

### Task 2: `NetworkModule` dynamic host/port + cleartext config

**Files:**
- Modify: `app/src/main/java/com/example/chargecontrol/network/NetworkModule.kt` (replace entire file)
- Modify: `app/src/main/java/com/example/chargecontrol/Config.kt` (replace entire file)
- Modify: `app/src/main/res/xml/network_security_config.xml` (replace entire file)

**Interfaces:**
- Consumes: `SettingsRepository.evccHost`/`evccPort`/`goeHost` (Task 1).
- Produces: `NetworkModule.evccApi`/`goeApi` — same public shape as before (no signature change), now backed by dynamic host/port instead of a fixed `baseUrl`. Consumed by Task 4 (`MainActivity`, unchanged call site).

- [ ] **Step 1: Replace `NetworkModule.kt`**

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
private const val PLACEHOLDER_GOE_BASE_URL = "http://chargecontrol.invalid/"

private class DynamicHostInterceptor(
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

    private val goeOkHttpClient = baseClientBuilder()
        .addInterceptor(
            DynamicHostInterceptor(
                hostProvider = { SettingsRepository.goeHost },
                portProvider = { 80 }
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

    val goeApi: GoeApi by lazy {
        Retrofit.Builder()
            .baseUrl(PLACEHOLDER_GOE_BASE_URL)
            .client(goeOkHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GoeApi::class.java)
    }
}
```

Note: `PLACEHOLDER_EVCC_BASE_URL`/`PLACEHOLDER_GOE_BASE_URL` use the reserved `.invalid` TLD (RFC 2606 — guaranteed to never resolve). This is safe because Retrofit only uses `baseUrl` to construct the initial `okhttp3.Request` object client-side; DNS resolution happens later, after `DynamicHostInterceptor` has already rewritten the host, so the placeholder is never actually dialed.

- [ ] **Step 2: Replace `Config.kt`**

```kotlin
package com.example.chargecontrol

object Config {
    const val LOADPOINT_ID = 1
    const val POLL_INTERVAL_MS = 7000L
    const val HTTP_TIMEOUT_SECONDS = 5L
}
```

- [ ] **Step 3: Replace `network_security_config.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="true" />
</network-security-config>
```

- [ ] **Step 4: Verify the project builds and existing tests still pass**

Run: `cd /home/andi/AndroidStudioProjects/ChargeControl && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

Run: `cd /home/andi/AndroidStudioProjects/ChargeControl && ./gradlew testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all existing tests still pass (none of them reference `Config.EVCC_BASE_URL`/`GOE_BASE_URL` or `NetworkModule`, only `Config.LOADPOINT_ID`/`POLL_INTERVAL_MS`, which are unchanged).

- [ ] **Step 5: Commit**

```bash
cd /home/andi/AndroidStudioProjects/ChargeControl
git add app/src/main/java/com/example/chargecontrol/network/NetworkModule.kt app/src/main/java/com/example/chargecontrol/Config.kt app/src/main/res/xml/network_security_config.xml
git commit -m "feat: make evcc/go-e host and port dynamic via SettingsRepository"
```

---

### Task 3: `SettingsScreen` + `MainScreen` gear icon

**Files:**
- Modify: `gradle/libs.versions.toml` (add one library entry)
- Modify: `app/build.gradle.kts` (add one dependency line)
- Create: `app/src/main/java/com/example/chargecontrol/ui/SettingsScreen.kt`
- Modify: `app/src/main/java/com/example/chargecontrol/ui/MainScreen.kt` (replace entire file)

**Interfaces:**
- Consumes: `isValidIpv4`/`isValidPort`, `SettingsRepository` (Task 1).
- Produces: `@Composable fun SettingsScreen(onBack: () -> Unit)`; `MainScreen` gains one new required parameter `onSettingsClick: () -> Unit` — both consumed by Task 4 (`MainActivity`).

- [ ] **Step 1: Add the icons dependency**

In `gradle/libs.versions.toml`, add to `[libraries]` (no `version.ref` needed — it's managed by the already-present Compose BOM):

```toml
androidx-compose-material-icons-core = { group = "androidx.compose.material", name = "material-icons-core" }
```

In `app/build.gradle.kts`, add to `dependencies { ... }`, next to the other `androidx.compose` lines:

```kotlin
    implementation(libs.androidx.compose.material.icons.core)
```

- [ ] **Step 2: Create `SettingsScreen.kt`**

```kotlin
package com.example.chargecontrol.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
    var evccHost by remember { mutableStateOf(SettingsRepository.evccHost) }
    var evccPort by remember { mutableStateOf(SettingsRepository.evccPort.toString()) }
    var goeHost by remember { mutableStateOf(SettingsRepository.goeHost) }

    val evccHostValid = isValidIpv4(evccHost)
    val evccPortValid = isValidPort(evccPort)
    val goeHostValid = isValidIpv4(goeHost)
    val canSave = evccHostValid && evccPortValid && goeHostValid

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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("evcc", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = evccHost,
                    onValueChange = { evccHost = it },
                    label = { Text("IP-Adresse") },
                    isError = !evccHostValid,
                    supportingText = { if (!evccHostValid) Text("Ungültige IPv4-Adresse") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = evccPort,
                    onValueChange = { evccPort = it },
                    label = { Text("Port") },
                    isError = !evccPortValid,
                    supportingText = { if (!evccPortValid) Text("Port muss zwischen 1 und 65535 liegen") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("go-e Wallbox", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = goeHost,
                    onValueChange = { goeHost = it },
                    label = { Text("IP-Adresse") },
                    isError = !goeHostValid,
                    supportingText = { if (!goeHostValid) Text("Ungültige IPv4-Adresse") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Button(
                onClick = {
                    SettingsRepository.evccHost = evccHost
                    SettingsRepository.evccPort = evccPort.toInt()
                    SettingsRepository.goeHost = goeHost
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

- [ ] **Step 3: Replace `MainScreen.kt`** (adds a `TopAppBar` with a settings-gear action and the new `onSettingsClick` parameter; everything else is unchanged from the current file)

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
import androidx.compose.foundation.layout.weight
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

- [ ] **Step 4: Verify the project builds**

Run: `cd /home/andi/AndroidStudioProjects/ChargeControl && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`. (`MainActivity.kt`'s existing `MainScreen(...)` call site doesn't pass `onSettingsClick` yet, so this specific call won't compile until Task 4 — if `assembleDebug` fails ONLY on that missing-argument error at `MainActivity.kt`'s `MainScreen(...)` invocation, that's expected and fixed in Task 4. To confirm this task in isolation instead, run `./gradlew compileDebugKotlin -x :app:compileDebugKotlin` is not meaningful for a single module — instead, temporarily verify `SettingsScreen.kt` and the rest of `MainScreen.kt` compile by checking the error output names only `MainActivity.kt`'s call site, nothing else.)

- [ ] **Step 5: Commit**

```bash
cd /home/andi/AndroidStudioProjects/ChargeControl
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/example/chargecontrol/ui/SettingsScreen.kt app/src/main/java/com/example/chargecontrol/ui/MainScreen.kt
git commit -m "feat: add SettingsScreen and MainScreen settings-gear entry point"
```

---

### Task 4: Wire `MainActivity` — screen switching + `SettingsRepository.init`

**Files:**
- Modify: `app/src/main/java/com/example/chargecontrol/MainActivity.kt` (replace entire file)

**Interfaces:**
- Consumes: `SettingsRepository.init` (Task 1), `SettingsScreen` (Task 3), `MainScreen`'s new `onSettingsClick` parameter (Task 3).
- Produces: nothing further consumed — this is the final integration task.

- [ ] **Step 1: Replace `MainActivity.kt`**

```kotlin
package com.example.chargecontrol

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
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

private enum class Screen { Main, Settings }

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

private fun hasLocalNetworkPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED

@Composable
fun ChargeControlApp() {
    val context = LocalContext.current
    val activity = context as Activity

    SettingsRepository.init(context)

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

    var currentScreen by remember { mutableStateOf(Screen.Main) }

    if (currentScreen == Screen.Settings) {
        SettingsScreen(onBack = { currentScreen = Screen.Main })
        return
    }

    val viewModel: WallboxViewModel = viewModel(
        factory = viewModelFactory {
            initializer { WallboxViewModel(NetworkModule.evccApi, NetworkModule.goeApi) }
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

    MainScreen(
        uiState = uiState,
        onModeSelected = viewModel::setMode,
        onStop = { viewModel.setMode("off") },
        onErrorShown = viewModel::errorShown,
        onPhasesSelected = viewModel::setPhases,
        onMinCurrentChanged = viewModel::setMinCurrent,
        onSettingsClick = { currentScreen = Screen.Settings }
    )
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
```

This drops in `SettingsRepository.init(context)` as the first statement, and adds the `currentScreen`/`Screen` switch right after the permission gate — everything else (permission flow, lifecycle wiring, ViewModel construction) is untouched from the current file.

- [ ] **Step 2: Full build and test verification**

Run: `cd /home/andi/AndroidStudioProjects/ChargeControl && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

Run: `cd /home/andi/AndroidStudioProjects/ChargeControl && ./gradlew testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests (Task 1's new ones plus the full pre-existing suite) pass.

- [ ] **Step 3: Commit**

```bash
cd /home/andi/AndroidStudioProjects/ChargeControl
git add app/src/main/java/com/example/chargecontrol/MainActivity.kt
git commit -m "feat: wire settings screen navigation and SettingsRepository init"
```

- [ ] **Step 4: On-device manual verification**

Build and install: `./gradlew installDebug` (device connected via `adb devices`, on the same WLAN as evcc/go-e).

Checklist:
- Tapping the gear icon on the main screen opens the settings screen; the back arrow returns to the main screen.
- Fields are pre-filled with the current values (defaults on first launch: `192.168.224.24` / `7070` / `192.168.224.245`).
- Typing an invalid IP or out-of-range port shows the inline error and disables "Speichern"; fixing it re-enables the button.
- Saving a value equal to the current live evcc/go-e IP: app continues working exactly as before (regression check).
- Saving a deliberately wrong IP: the next poll shows the "evcc nicht erreichbar" (or go-e equivalent) Snackbar, no crash, last known state stays visible — then correcting it back in Settings makes the app recover without restarting.
- Verify no "app restart" prompt ever appears; the change takes effect on its own within one poll interval (~7s) of saving.
- The "Über" section shows the app name, version, and "Entwickelt von Andreas Hutter".
- Confirm the app still functions correctly given the broadened cleartext policy (no unexpected network security exceptions in logcat if accessible).
