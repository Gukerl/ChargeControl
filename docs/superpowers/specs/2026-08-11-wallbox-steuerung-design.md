# Wallbox-Steuerung (ChargeControl) — Design

## Ziel
Einfache Android-App (Kotlin, Jetpack Compose, sideloadbare APK) zur Steuerung des Ladevorgangs über evcc, ausschließlich im lokalen WLAN. Ursprünglicher Auftrag: [wallbox-app-auftrag.md](/home/andi/Downloads/wallbox-app-auftrag.md).

Projekt: bestehendes Compose-Scaffold `~/AndroidStudioProjects/ChargeControl`, Package `com.example.chargecontrol`, minSdk 24, targetSdk/compileSdk 37.

## Backend-Systeme

**evcc** — `http://192.168.224.24:7070`, offizielle REST-API, kein Token, Loadpoint-ID `1`. Live gegen die evcc-Instanz verifiziert (Version 0.313.2, Loadpoint 1 = "go-e Box"):
- `GET /api/state` → volles State-JSON
- `POST /api/loadpoints/{id}/mode/{mode}` — `mode` ∈ {off, now, minpv, pv}

**go-e Wallbox** — `http://192.168.224.245`, nur zur Freigabe beim App-Start:
- `GET /api/set?frc=0` (Neutral — hebt eine evtl. gesetzte Sperre auf)

## Architektur

MVVM, Single-Activity Compose App.

- **`EvccApi`** (Retrofit-Interface, Base-URL `http://192.168.224.24:7070/api/`):
  - `GET state`
  - `POST loadpoints/{id}/mode/{mode}`
- **`GoeApi`** (Retrofit-Interface, Base-URL `http://192.168.224.245/`):
  - `GET api/set?frc=0`
- **`WallboxViewModel`**: hält `StateFlow<UiState>` (mode, phasesConfigured, offeredCurrent, connected, charging, isLoading, errorMessage), kapselt alle Netzwerk-Aufrufe, keine Business-Logik in der UI-Schicht.
- **`MainScreen`** (Composable): Status-Card oben (Modus, Phasen als "Auto/1-phasig/3-phasig" aus `phasesConfigured`, `offeredCurrent` in A, verbunden/lädt-Status), darunter 4 Material-3-Buttons (minpv/pv/now/Laden stoppen), aktiver Modus visuell hervorgehoben.

## Datenfluss

1. App-Start → `GET http://192.168.224.245/api/set?frc=0`, danach sofort `GET /api/state`.
2. Jeder Button-Tap → `POST /api/loadpoints/1/mode/{x}` (stoppen = `mode/off`), danach sofortiger Re-Fetch des States statt auf den nächsten Poll-Tick zu warten.
3. Parallel läuft ein Polling-Loop im ViewModel (`viewModelScope`, `while (true) { fetchState(); delay(7000) }`), gestartet in `onStart` der Activity/Lifecycle, gestoppt in `onStop`.
4. Die UI zeigt ausschließlich den zuletzt vom Server gelesenen Zustand — nie einen lokal gemerkten "zuletzt gedrückten Button". Ändert jemand den Modus z. B. über die evcc-Weboberfläche, zeigt die App das spätestens beim nächsten Poll korrekt an.

## Anzeige-Werte

- **Modus**: `loadpoints[0].mode`
- **Phasen**: `loadpoints[0].phasesConfigured` (0 = Auto, 1 = 1-phasig, 3 = 3-phasig)
- **Ladestrom**: `loadpoints[0].offeredCurrent` — der tatsächlich von evcc freigegebene Ist-Wert, nicht die eingestellte min/max-Grenze.

## Fehlerbehandlung

Jeder Retrofit-Call in try/catch (`IOException`, `HttpException`) im ViewModel, setzt `errorMessage` im `UiState` kurzzeitig → Compose zeigt eine Snackbar, State wird danach zurückgesetzt. Kein Crash, kein Blockieren der UI, kein Retry. Letzter bekannter Status bleibt sichtbar.

## Technik-Setup

- Kotlin, Jetpack Compose + Material 3 (bereits im Scaffold vorhanden).
- Retrofit + OkHttp + kotlinx.serialization für JSON (neu hinzuzufügen).
- `network_security_config.xml` mit `cleartextTrafficPermitted="true"`, beschränkt auf `192.168.224.24` und `192.168.224.245`.
- `INTERNET`-Permission im Manifest.
- IPs/Ports/Loadpoint-ID als Konstanten in `Config.kt`.

## Nicht im Scope (v1, spätere Iteration)

- Phasensteuerung (0/1/3) und Ladestrom-Regler im UI — bei Bedarf später ergänzen; der Regler würde dann `POST /api/loadpoints/{id}/mincurrent/{A}` setzen (per Nutzerentscheidung: minCurrent, nicht maxCurrent).
- ioBroker-Anbindung
- Authentifizierung/Login
- Nutzung außerhalb des lokalen WLANs
- RFID-Verwaltung an der Wallbox

## Testing

Unit-Tests für `WallboxViewModel`-State-Übergänge mit gemocktem `EvccApi`/`GoeApi` (MockWebServer oder Fake-Implementierungen). Kein UI-Test-Framework nötig für diesen Umfang.
