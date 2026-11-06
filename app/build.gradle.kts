plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.paparazzi)
}

android {
    namespace = "com.watchly.app"
    // Platform android-36 is installed in the local SDK; compile/target stay on
    // 35 until the matching AGP version is confirmed, then bump both to 36.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.watchly.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}
