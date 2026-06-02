plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Build-time config layered on TWO files:
//
//   client/Android.env.defaults   (tracked)   - canonical defaults, ships with
//                                               the repo, defines the schema.
//                                               Must exist or build fails.
//   client/Android.env            (gitignored)- per-fork overrides. Optional.
//                                               Values here win per-KEY over
//                                               defaults; KEYs absent from the
//                                               override fall back to defaults.
//
// Each merged KEY=VALUE pair becomes a String BuildConfig field; Config.kt
// reads them at runtime as `BuildConfig.<KEY>` with the parsing it needs
// (toInt / toLong / toBooleanStrict / enum valueOf).
//
// Why per-KEY merge instead of whole-file precedence: if I add a new KEY to
// .defaults later, forkers with a pre-existing .env automatically inherit the
// new default without having to re-copy the whole file. The only thing they
// need in .env is the values they actually want to override.
fun parseAndroidEnv(f: File): Map<String, String> = f.readLines()
    .map { it.substringBefore('#').trim() }
    .filter { it.isNotEmpty() && it.contains('=') }
    .associate { line ->
        val idx = line.indexOf('=')
        line.substring(0, idx).trim() to line.substring(idx + 1).trim()
    }
val androidEnvDefaults = rootDir.parentFile.resolve("Android.env.defaults")
val androidEnvOverride = rootDir.parentFile.resolve("Android.env")
require(androidEnvDefaults.exists()) {
    "Required file missing: ${androidEnvDefaults.absolutePath}. " +
        "Android.env.defaults is the canonical schema for build-time config."
}
val androidEnv: Map<String, String> = buildMap {
    putAll(parseAndroidEnv(androidEnvDefaults))
    if (androidEnvOverride.exists()) {
        // putAll on an existing key overwrites, which is exactly the
        // per-KEY override semantic we want.
        putAll(parseAndroidEnv(androidEnvOverride))
    }
}

android {
    namespace = "pl.dubba.share"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.dubba.share"
        minSdk = 28
        targetSdk = 35
        // versionCode scheme: major*10000 + minor*100 + patch.
        // 1.0.0 → 10000; bump leaves room for 1.0.1 (10001), 1.1.0 (10100), 2.0.0 (20000).
        versionCode = 10001
        versionName = "1.0.1"
        // Default app label substituted into AndroidManifest's
        // android:label="${appLabel}". Debug build overrides this so the
        // home-screen icon visibly distinguishes the dev install.
        manifestPlaceholders["appLabel"] = "Share location"

        // Inject every key from Android.env as a String BuildConfig field.
        // Values that need Int (e.g. DEFAULT_SERVER_PORT) get `.toInt()` at
        // the read site - keeps the gradle side schema-free.
        androidEnv.forEach { (key, value) ->
            buildConfigField("String", key, "\"$value\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            // Coexists with the release build on the same device:
            //   - applicationIdSuffix → fresh applicationId (pl.dubba.share.dev),
            //     so the OS treats it as a separate app for install/upgrade.
            //   - versionNameSuffix → "0.1.0-dev" surfaces in About / system
            //     app-info, makes the distinction obvious from inside.
            //   - manifestPlaceholders.appLabel → "Share (dev)" on the home
            //     screen icon so you can tell them apart at a glance.
            //   - FileProvider authority in the manifest uses ${applicationId}
            //     so dev and release don't collide there either.
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            manifestPlaceholders["appLabel"] = "Share (dev)"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":protocol"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    testImplementation(kotlin("test"))
}
