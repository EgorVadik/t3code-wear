plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The Wearable Data Layer only connects apps with the same package and signing certificate,
// so each flavor mirrors an apps/mobile variant and must be signed with that variant's key.
fun signingValue(name: String): String? =
    (findProperty("t3.wear.$name") as String?) ?: System.getenv("T3_WEAR_${name.uppercase()}")

val phoneDebugKeystore = rootDir.resolve("../mobile/android/app/debug.keystore")

android {
    namespace = "com.t3tools.t3code.wear"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("phone") {
            val storeFilePath = signingValue("storeFile")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = signingValue("storePassword")
                keyAlias = signingValue("keyAlias")
                keyPassword = signingValue("keyPassword")
            } else if (phoneDebugKeystore.exists()) {
                // Expo prebuild signs debug phone builds with React Native's template keystore.
                storeFile = phoneDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    flavorDimensions += "variant"
    productFlavors {
        create("development") {
            dimension = "variant"
            applicationId = "com.t3tools.t3code.dev"
        }
        create("preview") {
            dimension = "variant"
            applicationId = "com.t3tools.t3code.preview"
        }
        create("production") {
            dimension = "variant"
            applicationId = "com.t3tools.t3code"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("phone")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("phone")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.wear.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
