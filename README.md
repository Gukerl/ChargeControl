# ChargeControl

Native Android-App (Kotlin, Jetpack Compose, Material 3) zur Steuerung einer privaten EV-Ladeinfrastruktur über [evcc](https://evcc.io) und optional eine go-e Wallbox — ausschließlich über das lokale WLAN, ohne Konto, Cloud oder Tracking.

## Funktionen

- Lademodus wählen: kW min + PV-Überschuss, PV-Überschuss, Schnellladen oder Stopp
- Status auf einen Blick: Modus, Ladestrom, aktive Phasen, Fahrzeug-Verbindung und Akkustand
- Ladestrom-Einstellungen: Phasenzahl (1-/3-phasig) und Mindest-Ladestrom manuell festlegen
- Optionale go-e-Autorisierung mit RFID-Kontoauswahl, auf Wunsch automatisch bei angeschlossenem Fahrzeug
- Steuer-Buttons mit einstellbarer Bestätigungszeit gegen versehentliches Antippen

## Voraussetzungen

- Eine laufende [evcc](https://evcc.io)-Installation (z. B. auf einem Raspberry Pi) im selben lokalen Netzwerk
- Optional: eine go-e Wallbox im selben Netzwerk

## Build

```bash
./gradlew assembleDebug   # Debug-APK
./gradlew bundleRelease   # signiertes Release-Bundle (benötigt keystore.properties, siehe unten)
./gradlew testDebugUnitTest
```

Für einen signierten Release-Build wird eine lokale, nicht versionierte `keystore.properties` erwartet:

```properties
storeFile=<Pfad zur .jks-Datei>
storePassword=<...>
keyAlias=<...>
keyPassword=<...>
```

## Lizenz

[GPL-3.0-or-later](LICENSE)

## Datenschutz

ChargeControl sammelt, speichert oder überträgt keine personenbezogenen Daten. Die App kommuniziert ausschließlich direkt mit Geräten im eigenen lokalen WLAN.
