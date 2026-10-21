import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: keystore.properties (git-ignored) at the repo root. Without it,
// release builds fall back to the debug key so anyone can still build and sideload.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.jarvis.watchface"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.watchface"
        minSdk = 30 // Wear OS 3+
        targetSdk = 34
        versionCode = 3
        versionName = "1.0.2"
    }
    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(project(":avatar"))
    implementation("androidx.wear.watchface:watchface:1.2.1")
    // On-watch Customize screen (character picker).
    implementation("androidx.wear.watchface:watchface-editor:1.2.1")

    // Test-only: renders the real android.graphics Canvas on the JVM for screenshots.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
