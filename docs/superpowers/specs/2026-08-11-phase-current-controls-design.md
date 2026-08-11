# Phasen- und Ladestrom-Steuerung (Erweiterte Einstellungen) — Design

## Ziel
Ergänzung der bestehenden ChargeControl-App um die im ursprünglichen Auftrag als optional vermerkte Phasen- und Ladestrom-Steuerung, jetzt nachgezogen. Aus Versehentlich-Verstellen-Schutz sind beide Kontrollen hinter einer gemeinsamen Sperr-Checkbox verborgen.

Baut auf [2026-08-11-wallbox-steuerung-design.md](2026-08-11-wallbox-steuerung-design.md) auf — Architektur (MVVM, Retrofit, StateFlow) bleibt unverändert, nur Erweiterung der bestehenden Typen.

## Backend

evcc-Endpunkte (bereits beim ursprünglichen Design gegen die Live-Instanz und die offizielle Doku verifiziert):
- `POST /api/loadpoints/{id}/phases/{phases}` — `phases` ∈ {"0", "1", "3"} (0 = Automatisch, 1 = 1-phasig, 3 = 3-phasig)
- `POST /api/loadpoints/{id}/mincurrent/{current}` — Ganzzahl, evcc-seitig typischerweise 6–16A begrenzt

Das evcc-State-JSON liefert `minCurrent` bereits als Feld pro Loadpoint (bisher ungenutzt, siehe Live-Beispiel aus dem ursprünglichen Design: `"minCurrent":6,"maxCurrent":16`).

## Datenmodell-Änderungen

- `LoadpointDto`: neues Feld `minCurrent: Int`.
- `EvccApi`: zwei neue Methoden `setPhases(id: Int, phases: String): Response<ResponseBody>` und `setMinCurrent(id: Int, current: Int): Response<ResponseBody>`, gleiches Fehlerverhalten wie `setMode` (kein Exception-Throw bei Nicht-2xx, `response.isSuccessful` prüfen).
- `UiState`: neues Feld `minCurrent: Int = 6`, befüllt in `fetchState()` wie die übrigen Felder.
- `WallboxViewModel`: zwei neue Methoden `setPhases(phases: Int)` und `setMinCurrent(current: Int)` — identisches Muster wie `setMode()`: POST, danach unconditional `fetchState()`, IOException → Fehlermeldung, kein Crash.

## UI-Änderungen (`MainScreen`)

Neuer Bereich unterhalb der vier Haupt-Buttons, oberhalb nichts weiter Neues:

1. **Checkbox** „Erweiterte Einstellungen entsperren" — rein lokaler Compose-State (`remember { mutableStateOf(false) }`), kein Teil von `UiState`/ViewModel. Bleibt entsperrt bis manuell wieder deaktiviert oder App-Neustart (kein Auto-Reset nach einer Änderung).
2. **Phasen-Buttons** (Automatisch / 1-phasig / 3-phasig), gleiche visuelle Sprache wie die Modus-Buttons (aktiver Wert aus `uiState.phasesConfigured` hervorgehoben, `enabled = false` wenn Checkbox nicht angehakt).
3. **Ladestrom-Slider** 6–16A (Ganzzahlschritte), Startwert/aktueller Wert aus `uiState.minCurrent` wenn nicht gerade gezogen wird. Der `setMinCurrent`-Call feuert nur bei `onValueChangeFinished` (Loslassen), nicht bei jeder Zwischenposition. `enabled = false` wenn Checkbox nicht angehakt.

Alle drei Kontrollen sind nicht ausgeblendet, sondern sichtbar-aber-ausgegraut (`enabled = false`) wenn gesperrt, damit der Nutzer erkennt, dass es die Funktion gibt.

## Fehlerbehandlung
Identisch zum bestehenden Muster: kein Crash, kein Retry, kurze Fehlermeldung via Snackbar, letzter bekannter Zustand bleibt sichtbar.

## Testing
Unit-Tests für `WallboxViewModel.setPhases()` und `setMinCurrent()` nach dem bestehenden Muster in `WallboxViewModelTest.kt` (Fakes, Erfolg + Fehlerfall). Kein UI-Test-Framework nötig (Konsistenz mit dem restlichen Projekt).

## Nicht im Scope
- Keine Persistenz des Entsperrt-Zustands über App-Neustarts hinaus (bewusst, wie besprochen).
- Keine serverseitige Validierung der 6–16A-Grenzen über evcc hinaus — die App schickt, was der Slider anzeigt, evcc entscheidet über Gültigkeit.
