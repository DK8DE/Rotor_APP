# Rotor App (Android)

Kotlin + Jetpack Compose App zur Steuerung von Rotoren über **RS485-Telegramme per TCP**.

## Voraussetzungen

- JDK 17+
- Android Studio (optional, aber empfohlen)
- Android SDK 35 (Platform + Build-Tools + Platform-Tools)
- Für den Emulator: **AEHD** oder Windows Hypervisor Platform (WHPX)

SDK-Pfad in `local.properties` (bereits gesetzt bei lokaler Installation):

```
sdk.dir=C\:\\Users\\<USER>\\AppData\\Local\\Android\\Sdk
```

AVD-Name (falls angelegt): `Rotor_API35`

### Emulator-Beschleunigung (Windows)

Falls der Emulator mit `x86_64 emulation currently requires hardware acceleration` abbricht:

```powershell
cd %LOCALAPPDATA%\Android\Sdk\extras\google\Android_Emulator_Hypervisor_Driver
# als Administrator:
silent_install.bat
```

Danach ggf. **PC neu starten**. Wenn `sc query aehd` weiterhin `STOPPED` zeigt: Virtualisierung im BIOS aktivieren bzw. Konflikt mit Hyper-V/WSL prüfen (Windows-Features „Windows-Hypervisorplattform“). Alternativ physisches Gerät per USB mit USB-Debugging.

## Version / GitHub Release

Die Versionsnummer steht nur in `app/src/main/java/de/dk8de/rotorapp/Version.kt`
(`MAJOR` / `MINOR` / `PATCH`). Gradle und die App lesen sie von dort.

Nach Push auf `main` mit geänderter `Version.kt` baut GitHub Actions die Release-APK
und legt ein Release mit Tag `vX.Y.Z` an (falls der Tag noch nicht existiert).
Manuell: Actions → **Build Android APK** → Run workflow.

## Bauen

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
.\gradlew.bat test
```

Auf Gerät/Emulator:

```powershell
.\gradlew.bat installDebug
# oder:
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n de.dk8de.rotorapp/.MainActivity
```

## Nutzung

1. Profil anlegen (Name, IP, Port, Master/Slave-IDs, optional Elevation)
2. **Verbinden** → **TEST** / **GETPOS**
3. Auf den Azimut-Kompass tippen → `SETPOSDG`
4. Während der Fahrt pollt die App ~250 ms `GETPOSDG` (Deadman)

Protokoll: `#SRC:DST:CMD:PARAMS:CS$` (wie RotorTcpBridge / RotorController_RS485).
