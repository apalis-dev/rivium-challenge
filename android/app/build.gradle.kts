import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.linkqueue"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.linkqueue"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

// Build the same Rust library for physical ARM64 phones and common x86_64 emulators.
// Requires cargo-ndk, both Rust targets, and the Android NDK selected by Android Studio.
val localAndroidSdk = providers.fileContents(
    layout.projectDirectory.dir("..").file("local.properties")
).asText.map { contents ->
    val localProperties = Properties().apply { load(contents.reader()) }
    localProperties.getProperty("sdk.dir")
        ?: throw GradleException("sdk.dir is missing from android/local.properties")
}
val androidSdk = providers.environmentVariable("ANDROID_HOME")
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    .orElse(localAndroidSdk)

tasks.register<Exec>("buildRustAndroid") {
    workingDir(rootProject.projectDir.parentFile)
    environment("ANDROID_HOME", androidSdk.get())
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "x86_64",
        "-o", file("src/main/jniLibs").absolutePath,
        "build", "--release", "--lib"
    )
}

tasks.named("preBuild").configure {
    dependsOn("buildRustAndroid")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
