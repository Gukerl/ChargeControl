# Optionsmenü: evcc/go-e IP-Konfiguration + About — Design

## Ziel
Ein Einstellungen-Bildschirm, über den die evcc-IP:Port und die go-e-Wallbox-IP zur Laufzeit geändert und gespeichert werden können, plus ein "Über"-Abschnitt mit Entwickler-Angabe.

## Kernproblem und Lösung

`NetworkModule` baute die Retrofit-Clients bisher einmalig mit fixer `baseUrl` aus `Config.EVCC_BASE_URL`/`GOE_BASE_URL`. Damit eine zur Laufzeit geänderte IP ohne Neustart und ohne Umbau von `WallboxViewModel` (das seine `EvccApi`/`GoeApi`-Instanzen einmalig im Konstruktor erhält) sofort wirkt, bekommt Retrofit eine Platzhalter-`baseUrl`, die nie tatsächlich angefragt wird (reservierte `.invalid`-TLD, wird nie aufgelöst). Ein `okhttp3.Interceptor` ersetzt Host (und bei evcc zusätzlich den Port) jeder ausgehenden Anfrage unmittelbar vor dem Versand durch den aktuellen Wert aus `SettingsRepository`. `evccApi`/`goeApi` bleiben dadurch einmalige Singletons wie bisher — nur die tatsächliche Zieladresse ist jetzt dynamisch.

## Cleartext-Netzwerkzugriff (Sicherheits-Trade-off, mit Nutzer abgestimmt)

Da Android Network Security Config keine IP-Bereiche/Platzhalter unterstützt, kann die bisherige Einschränkung auf zwei fest einprogrammierte IPs bei laufzeit-änderbaren IPs nicht aufrechterhalten werden. `network_security_config.xml` wird auf generelles Klartext-Erlauben umgestellt:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="true" />
</network-security-config>
```

Akzeptierter Trade-off: Die App ist ohnehin ausschließlich fürs lokale Heim-WLAN gedacht (kein sonstiger Internetzugriff nötig), daher vertretbar.

## Persistenz

`SettingsRepository` (neues Singleton-Objekt) kapselt `SharedPreferences` — die einfachste passende Lösung für drei Textwerte, keine zusätzliche Abhängigkeit (DataStore wäre Overkill). Muss einmalig mit `Context` initialisiert werden, bevor `NetworkModule` erstmals auf `evccApi`/`goeApi` zugreift — geschieht in `MainActivity`/`ChargeControlApp()` als erste Zeile.

Defaults = die bisherigen fest einprogrammierten Werte (`192.168.224.24`, Port `7070`, `192.168.224.245`), damit bestehendes Verhalten ohne jede Konfiguration erhalten bleibt.

```kotlin
object SettingsRepository {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("chargecontrol_settings", Context.MODE_PRIVATE)
    }

    var evccHost: String
        get() = prefs.getString(KEY_EVCC_HOST, DEFAULT_EVCC_HOST) ?: DEFAULT_EVCC_HOST
        set(value) = prefs.edit { putString(KEY_EVCC_HOST, value) }

    var evccPort: Int
        get() = prefs.getInt(KEY_EVCC_PORT, DEFAULT_EVCC_PORT)
        set(value) = prefs.edit { putInt(KEY_EVCC_PORT, value) }

    var goeHost: String
        get() = prefs.getString(KEY_GOE_HOST, DEFAULT_GOE_HOST) ?: DEFAULT_GOE_HOST
        set(value) = prefs.edit { putString(KEY_GOE_HOST, value) }
}
```
(Exakte Konstanten-/Key-Namen werden im Implementierungsplan festgelegt.)

## UI

**Einstieg:** `MainScreen` bekommt eine `TopAppBar` mit Zahnrad-Icon oben rechts. Tap öffnet den Einstellungen-Bildschirm.

**Navigation:** Einfacher lokaler Zwei-Zustands-Wechsel (`remember { mutableStateOf(Screen.Main) }` in `ChargeControlApp()`) zwischen Haupt- und Einstellungen-Bildschirm — keine Navigation-Compose-Abhängigkeit nötig für zwei Bildschirme.

**Einstellungen-Bildschirm (`SettingsScreen.kt`, neu, in `ui/`):**
- Abschnitt "evcc": Textfeld "IP-Adresse" + Textfeld "Port" (Zahlentastatur, Default `7070` vorausgefüllt).
- Abschnitt "go-e Wallbox": Textfeld "IP-Adresse".
- IPv4-Format wird live validiert (vier Oktette 0–255), Port-Bereich 1–65535. "Speichern"-Button bleibt deaktiviert, solange ein Feld ungültig ist; ungültige Felder zeigen einen kurzen Fehlertext.
- "Speichern" schreibt in `SettingsRepository` und kehrt sofort zum Hauptbildschirm zurück — keine Neustart-Meldung, die neuen Werte gelten ab der nächsten Anfrage (spätestens nächster Poll-Zyklus).
- Darunter, immer sichtbar (kein extra Bildschirm): Abschnitt "Über" mit App-Name, Versionsnummer (`BuildConfig.VERSION_NAME`) und "Entwickelt von Andreas Hutter".

## Geänderte Dateien (Überblick)

- `Config.kt`: `EVCC_BASE_URL`/`GOE_BASE_URL` entfallen. `LOADPOINT_ID`, `POLL_INTERVAL_MS`, `HTTP_TIMEOUT_SECONDS` bleiben unverändert (nicht Teil dieser Anfrage).
- `NetworkModule.kt`: Platzhalter-baseUrl + zwei dynamische Interceptor-Instanzen (evcc: Host+Port, go-e: nur Host, Port fix 80).
- `MainScreen.kt`: `TopAppBar` mit Zahnrad-Icon.
- `MainActivity.kt`: lokale Bildschirm-Navigation, `SettingsRepository.init(context)` beim Start.
- `AndroidManifest.xml`: keine Änderung nötig (Manifest verweist bereits auf die NSC-Datei).
- `network_security_config.xml`: wie oben beschrieben.

## Fehlerbehandlung

Unverändert zum bestehenden Muster: ungültige Eingaben blockieren das Speichern clientseitig (sofortiges Feedback). Eine formal gültige, aber falsche IP führt zum bekannten "evcc nicht erreichbar"/"go-e Box nicht erreichbar" beim nächsten Verbindungsversuch — kein Absturz, kein Retry, letzter bekannter Zustand bleibt sichtbar (bestehendes Verhalten, unverändert).

## Testing

Unit-Tests für `SettingsRepository` (Werte werden korrekt gespeichert/gelesen, Defaults greifen wenn nichts gespeichert) und für die IPv4-/Port-Validierungslogik (gültige/ungültige Beispiele). Kein UI-Test-Framework nötig (Konsistenz mit dem restlichen Projekt).

## Nicht im Scope
- Keine Änderung an `LOADPOINT_ID`, Polling-Intervall oder Timeout-Konfiguration.
- Keine Authentifizierung/kein Schutz des Einstellungen-Bildschirms.
- Kein Hostname-Support (nur IPv4-Adressen, wie im ursprünglichen Auftrag vorgesehen).
