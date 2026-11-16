plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.paparazzi)
}

import java.util.Properties

// ---------------------------------------------------------------------------
// Watchly signing (cycle 7, Phase A0). Root cause this fixes: Gradle
// builds on the build VM run as root, so the auto-generated debug
// keystore lived in /root/.android — which is NOT persisted when the
// VM is replaced. Every replacement silently minted a NEW debug key,
// and Android then rejects install-over updates with a conflicting-
// signature error (hit on the cycle-6 delivery; the old keys are
// unrecoverable). The one true keystore now lives in the persisted
// workspace, OUTSIDE this repo (../signing/ — workspace-persisted,
// git-ignored by pattern as a tripwire), and BOTH build types sign
// with it whenever it is present.
//
// Fallback: when ../signing/signing.properties is absent (CI, any
// fresh checkout), nothing is wired and builds use the platform
// defaults exactly as before — CI stays green keyless.
// ---------------------------------------------------------------------------
val watchlySigningPropsFile = rootProject.file("../signing/signing.properties")
val watchlySigningProps = Properties()
if (watchlySigningPropsFile.exists()) {
    watchlySigningPropsFile.inputStream().use { watchlySigningProps.load(it) }
}
val hasWatchlySigning = watchlySigningPropsFile.exists()

// ---------------------------------------------------------------------------
// Watchly master API keys — baked into RELEASE builds so the app is
// zero-setup: no key screens, no pasting, nothing for a user to configure.
//
// Resolution order per key: environment (CI secrets) → git-ignored
// secrets.properties → the built-in fallback constants below. Debug/test
// builds stay BLANK on purpose (screenshot goldens and feature gating need
// the keyless state to be deterministic); only the release APK carries keys.
//
// To rotate: replace the fallback constants here (or set the same-named
// environment variables / secrets.properties entries, which win) — no other
// change needed.
// ---------------------------------------------------------------------------
val watchlySecretsProps = Properties()
rootProject.file("secrets.properties").takeIf { it.exists() }?.inputStream()?.use {
    watchlySecretsProps.load(it)
}

// Built-in fallback keys (release builds only). The TMDB value is a
// comma-separated rotation pool: if TMDB rejects the first, TmdbApi
// falls through to the next.
val watchlyMasterDefaults = mapOf(
    "MASTER_TMDB_KEY" to
        "4f298a53e5522830c957e3bc1d007647,3b6bdb61d7ea586e9b15d15aad74a0e0",
    "MASTER_OMDB_KEY" to "3fe6e3fc",
    "MASTER_TRAKT_CLIENT_ID" to "2M3JfayN76tNAq_ppJw6ZXROTDmhsq9THc0pnuqfB6o",
    "MASTER_OPENSUBTITLES_KEY" to "nU9U21UXcHfxmOSCNUHZnFNb80zq0aa9",
    "MASTER_FANART_KEY" to "42ab51ecdff1d6a6100d90fb4032cc9d"
)

fun watchlySecret(name: String, fallback: String = ""): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: watchlySecretsProps.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: fallback

android {
    namespace = "com.watchly.app"
    // Platform android-36 is installed in the local SDK; compile/target stay on
    // 35 until the matching AGP version is confirmed, then bump both to 36.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.watchly.app"
        minSdk = 26
        targetSdk = 35
        // v1.0.0 (cycle 8): versionCode scheme = major*100 + minor*10 +
        // patch, so 1.0.0 = 100 and every later release sorts above it.
        // v1.1.0 (cycle 9): the player pack — 110.
        // v1.2.0 (cycle 10): backup/export, Data Saver, details depth — 120.
        // v1.3.0 (cycle 11): person pages (§3.3) — 130.
        // v1.4.0 (cycle 12): profiles (§8) — 140.
        versionCode = (System.getenv("BUILD_VERSION_CODE") ?: "151").toIntOrNull() ?: 151
        // Per-build versioning for the in-app updater: CI passes a monotonic
        // BUILD_VERSION_CODE / BUILD_VERSION_NAME (e.g. 1.5.1.37) so every
        // build installs over the last and the update banner compares true.
        // Local builds fall back to the fixed release values.
        versionName = System.getenv("BUILD_VERSION_NAME") ?: "1.5.1"
        // Instrumented tests (app/src/androidTest) run under the
        // standard AndroidX runner on the CI emulator.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val internalKeystore = rootProject.file("signing/watchly.jks")
    signingConfigs {
        if (hasWatchlySigning) {
            create("watchly") {
                // storeFile in the properties is a bare file name,
                // resolved next to the properties file itself so the
                // pair travels with the signing directory.
                storeFile = File(
                    watchlySigningPropsFile.parentFile,
                    watchlySigningProps.getProperty("storeFile")
                )
                storePassword = watchlySigningProps.getProperty("storePassword")
                keyAlias = watchlySigningProps.getProperty("keyAlias")
                keyPassword = watchlySigningProps.getProperty("keyPassword")
            }
        } else if (internalKeystore.exists()) {
            // CI restores the one permanent release keystore from the repo
            // secrets into signing/watchly.jks. The credentials come from the
            // RELEASE_KEYSTORE_* environment variables so the key and its
            // password never live in git.
            val ksPassword = System.getenv("RELEASE_KEYSTORE_PASSWORD") ?: "watchly123"
            val ksAlias = System.getenv("RELEASE_KEYSTORE_ALIAS") ?: "watchly"
            create("watchly") {
                storeFile = internalKeystore
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // Sign with the persistent Watchly key when present
            // (local builds) so every delivered build installs over
            // the previous one; platform debug key otherwise (CI).
            signingConfig = signingConfigs.findByName("watchly") ?: signingConfigs.getByName("debug")
            // Master keys stay BLANK in debug/test builds: screenshot
            // goldens and key-gating assertions must be deterministic
            // (keyless) — never a live key here.
            buildConfigField("String", "MASTER_TMDB_KEY", "\"\"")
            buildConfigField("String", "MASTER_OMDB_KEY", "\"\"")
            buildConfigField("String", "MASTER_TRAKT_CLIENT_ID", "\"\"")
            buildConfigField("String", "MASTER_OPENSUBTITLES_KEY", "\"\"")
            buildConfigField("String", "MASTER_FANART_KEY", "\"\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("watchly") ?: signingConfigs.getByName("debug")
            // Zero-setup: release APKs carry the master keys (env /
            // secrets.properties override the baked defaults).
            watchlyMasterDefaults.forEach { (name, default) ->
                buildConfigField("String", name, "\"${watchlySecret(name, default)}\"")
            }
        }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
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
        buildConfig = true
    }
    testOptions {
        unitTests {
            // Robolectric needs real Android resources/manifest on the classpath.
            isIncludeAndroidResources = true
            all {
                // Run JUnit 4 and JUnit 5 tests on the JUnit Platform
                // (Vintage engine below is what lets Robolectric's JUnit 4
                // runner execute alongside the JUnit 5 tests).
                it.useJUnitPlatform()
                it.maxHeapSize = "1g"
                it.testLogging {
                    events("passed", "failed", "skipped")
                }
                // Roborazzi goldens: VERIFY against the committed
                // screenshots by default (this is what CI runs);
                // record/refresh them with -ProborazziRecord.
                if (project.hasProperty("roborazziRecord")) {
                    it.systemProperty("roborazzi.test.record", "true")
                } else {
                    it.systemProperty("roborazzi.test.verify", "true")
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    // Cycle 9 player pack: MediaSession (system media controls —
    // lock screen, notification, headset) bound to the player.
    implementation(libs.androidx.media3.session)
    // Cycle 9: media3-session only carries androidx.media at
    // runtime; the notification's MediaStyle + the session compat
    // token need it on the compile classpath too.
    implementation(libs.androidx.media)
    // Cycle 5 download engine: OkHttp range requests + WorkManager
    // foreground worker (replaces the system DownloadManager engine).
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime.ktx)
    // Cycle 5 local files: enumerate a user-granted folder (SAF).
    implementation(libs.androidx.documentfile)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // JVM unit tests (JUnit 5) + Robolectric (JUnit 4 on the same platform)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit.ktx)
    // Roborazzi screenshot goldens on Robolectric (testing-rd-plan
    // Upgrade 3): full-screen captures inside ordinary Robolectric
    // tests, so they also run under the local in-process harness.
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    // Compose semantics/bounds assertions on Robolectric (cycle 13):
    // the accessibility regression tests measure real laid-out touch
    // targets and spoken labels in the JVM suite, beside the goldens.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    // Instrumented tests (testing-rd-plan Upgrade 1): the bug-named
    // Compose UI suites in app/src/androidTest run on the CI emulator
    // — the layer that can honestly assert real rendering, real back
    // behaviour in a composed NavHost, and real intent effects.
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit.ktx)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.espresso.intents)
    // Registers ComponentActivity in the debug manifest so
    // createAndroidComposeRule can launch it.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
