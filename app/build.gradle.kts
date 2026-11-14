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
        versionCode = 142
        versionName = "1.4.2"
        // Instrumented tests (app/src/androidTest) run under the
        // standard AndroidX runner on the CI emulator.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

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
        }
    }

    buildTypes {
        debug {
            // Sign with the persistent Watchly key when present
            // (local builds) so every delivered build installs over
            // the previous one; platform debug key otherwise (CI).
            if (hasWatchlySigning) {
                signingConfig = signingConfigs.getByName("watchly")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasWatchlySigning) {
                signingConfig = signingConfigs.getByName("watchly")
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
