// Deliberately dependency-free. LogRedact is needed by leaf modules (serial, SMB,
// Reticulum) that have no project dependencies at all, and making them pull in
// :core:data — Room, DataStore, Hilt — to redact a hostname would be a poor
// trade. Keep it that way: nothing in here should ever need a dependency.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "sh.haven.core.redact"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
