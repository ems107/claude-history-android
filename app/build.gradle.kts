import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The signing key never lives in this repository. `keystore.properties` is
 * gitignored and points at a file outside it, so a fresh clone can build debug
 * APKs and nothing else -- which is the right default, because a release
 * signed with a different key cannot install over an existing one.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "io.github.ems107.claudehistory"
    compileSdk = 37

    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.github.ems107.claudehistory"
        // The floor is the Urovo DT50 (Android 9), the ceiling a Galaxy S25.
        minSdk = 28
        targetSdk = 36
        versionCode = 104
        versionName = "0.1.4"
    }

    signingConfigs {
        if (keystoreProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            // Signed with the release key when there is one, and that is not a
            // convenience: Android refuses to install over an app whose
            // signature differs, so a debug build carrying the debug key can
            // only be replaced by a release APK after UNINSTALLING it -- which
            // takes the configured servers and their passwords with it, because
            // the key that encrypted them lives and dies with the install.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}


dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)

    debugImplementation(libs.compose.ui.tooling)
}
