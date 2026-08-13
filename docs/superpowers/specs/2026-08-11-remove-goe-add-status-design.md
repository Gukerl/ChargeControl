# Go-e entfernen, Status erweitern, passiver App-Start — Design

## Ziel
Drei zusammenhängende Änderungen an ChargeControl:
1. Die automatische go-e-Freigabe (`frc=0`) beim App-Start entfällt — und mit ihr die gesamte go-e-Anbindung, da sie sonst nirgends genutzt wird.
2. Neue Statusanzeigen: Fahrzeug-Verbindungsstatus als eigene Zeile, Ladestand (Batterie-SoC) in Prozent.
3. App-Start liest ausschließlich Status, führt keinerlei Steuerbefehl aus.

## Go-e-Entfernung (vollständig)

Go-e wird komplett aus der App entfernt, nicht nur der automatische Aufruf:
- `network/GoeApi.kt` — löschen.
- `network/NetworkModule.kt` — `goeOkHttpClient`, `goeApi` entfernen.
- `SettingsRepository.kt` — `goeHost`-Property, `DEFAULT_GOE_HOST`, `KEY_GOE_HOST` entfernen.
- `ui/SettingsScreen.kt` — go-e-IP-Eingabefeld und zugehörige Validierung entfernen; `canSave` prüft nur noch die zwei evcc-Felder.
- `ui/WallboxViewModel.kt` — Konstruktor verliert `goeApi: GoeApi`-Parameter; `releaseWallbox()`-Methode und ihr Aufruf in `onStart()` entfallen komplett; die go-e-spezifischen Fehlermeldungen ("go-e Box nicht erreichbar", "go-e Box-Fehler (...)") entfallen mit.
- `MainActivity.kt` — `WallboxViewModel(NetworkModule.evccApi)` (ein Parameter statt zwei).

Die App steuert danach ausschließlich über evcc. Der `ACCESS_LOCAL_NETWORK`-Berechtigungsfluss bleibt unverändert nötig (evcc ist weiterhin eine lokale IP). Die Netzwerk-Security-Config bleibt beim generellen Cleartext-Erlauben (unverändert, unabhängig von go-e).

## Passiver App-Start

`WallboxViewModel.onStart()`:
```kotlin
fun onStart() {
    if (pollingJob?.isActive == true) return
    pollingJob = viewModelScope.launch {
        while (isActive) {
            fetchState()
            delay(Config.POLL_INTERVAL_MS)
        }
    }
}
```
Kein Aufruf mehr vor der Schleife — der allererste Poll-Durchlauf liest sofort den aktuellen Status, ohne vorher irgendeinen Steuerbefehl an evcc oder go-e zu senden.

## Neue Statusfelder

`LoadpointDto` (network/EvccApi.kt) bekommt ein neues Feld `vehicleSoc: Double` (evcc liefert Ladestand potenziell als Kommazahl — `Double` statt `Int`, um denselben Deserialisierungs-Fehler zu vermeiden, der bei `minCurrent` in einer früheren Review gefunden wurde: ein einzelner Kommawert hätte sonst dauerhaft "Kein Status verfügbar" verursacht).

`UiState` bekommt `vehicleSoc: Int = 0`, befüllt via `loadpoint.vehicleSoc.roundToInt()` in `fetchState()` — die Rundung passiert an der UI-Grenze, nicht im Wire-Format-Parsing (gleiches Muster wie bei `minCurrent`).

`MainScreen.kt`s `StatusCard` bekommt zwei neue Zeilen, zusätzlich zur bestehenden Lade-Status-Zeile (bleibt unverändert bestehen):
- `"Fahrzeug verbunden: ${if (uiState.connected) "Ja" else "Nein"}"`
- `"Ladestand: ${uiState.vehicleSoc} %"`

## Testanpassungen

`WallboxViewModelTest.kt`:
- `FakeGoeApi`-Klasse entfernen, alle `WallboxViewModel(evccApi, goeApi)`-Konstruktoraufrufe werden zu `WallboxViewModel(evccApi)`.
- Der Test `onStart releases the wallbox and loads initial state` wird zu `onStart loads initial state` — die Assertion `goeApi.releaseCalled` entfällt, der Rest (State wird korrekt geladen) bleibt.
- `loadpoint()`-Test-Helper bekommt einen neuen `vehicleSoc: Double = 0.0`-Parameter mit Default.
- Ein neuer Test bestätigt, dass `onStart()` keinerlei go-e-Aufruf mehr macht (da `GoeApi` nicht mehr existiert, ist das strukturell garantiert — kein zusätzlicher Test dafür nötig, aber ein Test für den neuen `vehicleSoc`-Datenfluss ist sinnvoll).

`EvccStateResponseTest.kt`: beide Test-Payloads bekommen ein `"vehicleSoc"`-Feld, eine Assertion dafür wird ergänzt.

## Fehlerbehandlung
Unverändert zum bestehenden Muster (kein Crash, kein Retry, kurze Fehlermeldung, letzter bekannter Zustand bleibt sichtbar) — durch den Wegfall von go-e vereinfacht sich die Fehlerbehandlung sogar (eine Fehlerquelle weniger).

## Nicht im Scope
- Keine Wiedereinführung einer manuellen go-e-Freigabe-Funktion.
- Keine weiteren neuen Statusfelder über Fahrzeug-Verbindung und Ladestand hinaus.
