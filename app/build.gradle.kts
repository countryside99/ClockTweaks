plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.clocktweaks"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.clocktweaks"
        minSdk = 33
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
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

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // Hook classes and META-INF/xposed metadata are packaged into the APK but
    // are only loaded by the Xposed framework inside the Clock app process.
    runtimeOnly(project(":hook"))
}
