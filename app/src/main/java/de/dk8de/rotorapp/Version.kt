package de.dk8de.rotorapp

/**
 * App-Version (Semantic Versioning: MAJOR.MINOR.PATCH)
 *
 * Dies ist die EINZIGE Stelle, an der die App-Versionsnummer gepflegt wird.
 * - [app/build.gradle.kts] liest MAJOR/MINOR/PATCH für `versionName` / `versionCode`
 * - [set-version.ps1] kann die Nummer setzen und `README.md` abgleichen
 * - GitHub Actions erstellt bei neuer Version automatisch ein Release (Tag `vX.Y.Z`)
 *
 * Bei jeder Veröffentlichung mindestens PATCH erhöhen
 * (MINOR für neue Features, MAJOR für inkompatible Änderungen).
 *
 * Licensed under the Apache License, Version 2.0.
 * Copyright (c) DK8DE
 */
object AppVersion {
    const val MAJOR = 1
    const val MINOR = 2
    const val PATCH = 2

    /** Anzeige-String, z. B. `"0.1.0"`. */
    const val NAME: String = "$MAJOR.$MINOR.$PATCH"

    /** Android `versionCode` (MMmmpp). */
    const val CODE: Int = MAJOR * 10_000 + MINOR * 100 + PATCH
}
