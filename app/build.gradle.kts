import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/** Version aus [AppVersion] / Version.kt (einzige Quelle). */
fun readAppVersion(): Pair<String, Int> {
    val text = file("src/main/java/de/dk8de/rotorapp/Version.kt").readText()
    fun constInt(name: String): Int =
        Regex("""const val $name\s*=\s*(\d+)""").find(text)?.groupValues?.get(1)?.toInt()
            ?: error("Version.kt: $name nicht gefunden")
    val major = constInt("MAJOR")
    val minor = constInt("MINOR")
    val patch = constInt("PATCH")
    val name = "$major.$minor.$patch"
    val code = major * 10_000 + minor * 100 + patch
    return name to code
}

val (appVersionName, appVersionCode) = readAppVersion()

/**
 * Release-Signierung: CI liefert die Werte per Umgebungsvariable, lokal kommen sie
 * aus `keystore.properties` (nicht im Repo). Ohne Keystore bleibt `null` — dann
 * signiert Gradle mit Debug-Key, und das APK taugt nur zum Testen, nicht als Update.
 */
data class ReleaseSigning(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

fun releaseSigning(): ReleaseSigning? {
    val props = Properties()
    rootProject.file("keystore.properties")
        .takeIf { it.exists() }
        ?.inputStream()
        ?.use { props.load(it) }

    fun value(env: String, prop: String): String? {
        val v: String? = System.getenv(env) ?: props.getProperty(prop)
        return if (v.isNullOrBlank()) null else v
    }

    val path = value("ROTOR_KEYSTORE_FILE", "storeFile") ?: return null
    val store = file(path).takeIf { it.exists() } ?: rootProject.file(path)
    if (!store.exists()) return null
    return ReleaseSigning(
        storeFile = store,
        storePassword = value("ROTOR_KEYSTORE_PASSWORD", "storePassword") ?: return null,
        keyAlias = value("ROTOR_KEY_ALIAS", "keyAlias") ?: return null,
        keyPassword = value("ROTOR_KEY_PASSWORD", "keyPassword") ?: return null,
    )
}

val signing = releaseSigning()

android {
    namespace = "de.dk8de.rotorapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.dk8de.rotorapp"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (signing != null) {
            create("release") {
                storeFile = signing.storeFile
                storePassword = signing.storePassword
                keyAlias = signing.keyAlias
                keyPassword = signing.keyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Eigene App-ID: Test-Build und Release-APK dürfen parallel installiert sein
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8: unbenutzten Code/Ressourcen entfernen — kleinere APK, schnellerer Start
            isMinifyEnabled = true
            isShrinkResources = true
            // Fester Sideload-Schlüssel: nur damit lässt sich ein Release updaten,
            // statt es deinstallieren zu müssen.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
