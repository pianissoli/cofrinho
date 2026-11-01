plugins {
    kotlin("multiplatform")
    id("app.cash.sqldelight")
    id("com.android.library")
}

android { namespace = "cofrinho.storage"; compileSdk = 36 }

kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:finance"))
            implementation("app.cash.sqldelight:runtime:2.0.2")
        }
        jvmMain.dependencies { implementation("app.cash.sqldelight:sqlite-driver:2.0.2") }
        androidMain.dependencies { implementation("app.cash.sqldelight:android-driver:2.0.2") }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

sqldelight {
    databases {
        create("CofrinhoDatabase") {
            packageName.set("cofrinho.storage.db")
            srcDirs("src/commonMain/sqldelight")
        }
    }
}
