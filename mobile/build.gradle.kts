import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

setupApp()

// google-services.json is a CI secret that is not exposed to fork PRs. Apply the
// Firebase plugins only when it exists so fork/CI builds still configure; the
// Firebase SDKs no-op at runtime without a google_app_id.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
} else {
    logger.warn("mobile/google-services.json not found; skipping Firebase Gradle plugins")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun prop(key: String): String? =
    localProps.getProperty(key) ?: System.getenv(key)

android {
    namespace = "io.github.madeye.meow"

    // Compose lives only in the app module; :core stays UI-free so it does not
    // pay the Compose compiler cost.
    buildFeatures {
        compose = true
    }

    defaultConfig {
        applicationId = "io.github.madeye.meow"
    }

    // Only the locales the app translates. Without this, AndroidX drags in ~70
    // locales' worth of strings the app can never select. A values-xx directory
    // missing here is stripped from the APK; StringsParityTest catches that.
    androidResources {
        localeFilters += listOf("en", "zh-rCN", "ru", "fa", "vi", "ar", "tr", "my")
        // Lists the same locales under Settings > Apps > Meow > Language on
        // Android 13+, so the app language can differ from the system's.
        generateLocaleConfig = true
    }

    val keystorePath = prop("KEYSTORE_PATH")
    val keystoreFile = keystorePath?.let { File(it) }

    if (keystoreFile != null && keystoreFile.exists()) {
        signingConfigs {
            create("release") {
                storeFile = keystoreFile
                storePassword = prop("KEYSTORE_PASSWORD")
                keyAlias = prop("KEY_ALIAS")
                keyPassword = prop("KEY_PASSWORD")
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("release")
            }
            getByName("playRelease") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar)

    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)

    implementation(libs.sora.editor)
    implementation(libs.sora.editor.textmate)

    implementation(libs.zxing.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.compose)
    implementation(libs.camera.lifecycle)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
