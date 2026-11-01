plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget()

    sourceSets {
        commonMain.dependencies { }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

android { namespace = "caixa.finance"; compileSdk = 36 }
