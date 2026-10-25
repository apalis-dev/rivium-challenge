plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.kdbrian.rivapln.todoradar.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.kdbrian.rivapln.todoradar"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // OAuth App client id with Device Flow enabled; set todoRadar.githubClientId in
        // ~/.gradle/gradle.properties (public by design, kept out of the repo for forks).
        resValue("string", "github_client_id", (findProperty("todoRadar.githubClientId") as String?).orEmpty())
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildFeatures {
        compose = true
        resValues = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
}
