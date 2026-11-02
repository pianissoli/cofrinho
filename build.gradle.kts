import com.android.build.gradle.BaseExtension

plugins {
    kotlin("multiplatform") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    kotlin("jvm") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
    kotlin("plugin.compose") version "2.0.21" apply false
    id("com.android.application") version "8.10.1" apply false
    id("com.android.library") version "8.10.1" apply false
    id("app.cash.sqldelight") version "2.0.2" apply false
}

group = "cofrinho"
version = "0.1.1"

subprojects {
    listOf("com.android.application", "com.android.library").forEach { androidPluginId ->
        plugins.withId(androidPluginId) {
            extensions.configure<BaseExtension> {
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }
        }
    }
}

tasks.register("checkProjectLayout") {
    doLast {
        val required = listOf(
            "shared/finance/src/commonMain",
            "shared/transfer/src/commonMain",
            "shared/storage/src/commonMain",
            "apps/cli/src/main",
            "apps/android/src/main",
        )
        required.forEach { check(file(it).isDirectory) { "Missing project path: $it" } }
    }
}

tasks.register("check") {
    dependsOn("checkProjectLayout")
    dependsOn(subprojects.map { it.tasks.matching { candidate -> candidate.name == "check" } })
}
