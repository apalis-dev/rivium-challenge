import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    sourceSets {
        getByName("desktopMain") {
            dependencies {
                implementation(project(":shared"))
                implementation(compose.desktop.currentOs)
                implementation(libs.compose.material3)
                implementation(libs.compose.material.icons)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
    }
}

// The JNI library built by `cargo build -p radar-jni --release` (scripts/build-desktop.*).
val os = System.getProperty("os.name").lowercase()
val nativeLib = when {
    os.contains("win") -> "radar_jni.dll"
    os.contains("mac") -> "libradar_jni.dylib"
    else -> "libradar_jni.so"
}
val devLib = rootProject.layout.projectDirectory.file("../rust/target/release/$nativeLib").asFile

compose.desktop {
    application {
        mainClass = "io.github.kdbrian.rivapln.todoradar.desktop.MainKt"
        // `./gradlew :desktopApp:run` loads the freshly built engine straight from cargo's output.
        jvmArgs += listOf("-Dradar.lib=${devLib.absolutePath}", "-Xmx512m")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "TodoRadar"
            packageVersion = "1.0.0"
            description = "Scan GitHub repos for TODOs with an embedded Rust engine"
            // Packaged builds ship the library under resources/<os-arch>/ (copied by the build script).
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
        }
    }
}
