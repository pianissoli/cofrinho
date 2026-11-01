import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

kotlin { jvmToolchain(17) }

val signingProperties: Properties = Properties()
val signingFile = rootProject.file("keystore.properties")
if (signingFile.isFile) signingFile.inputStream().use(signingProperties::load)

android {
    namespace = "caixa.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.caixa.app"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (signingFile.isFile) {
            create("release") {
                storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":shared:finance"))
    implementation(project(":shared:transfer"))
    implementation(project(":shared:storage"))
    implementation("app.cash.sqldelight:android-driver:2.0.2")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui:1.7.5")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
