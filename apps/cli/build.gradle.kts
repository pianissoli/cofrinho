import java.io.File

plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared:finance"))
    implementation(project(":shared:transfer"))
    implementation(project(":shared:storage"))
    implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

application { mainClass.set("caixa.cli.MainKt") }

tasks.register("packageLinuxX64") {
    dependsOn("installDist")
    doLast {
        check(System.getProperty("os.name").lowercase().contains("linux")) { "Linux x86_64 packaging requires a Linux host" }
        val install = project.layout.buildDirectory.dir("install/cli").get().asFile
        val packageRoot = project.layout.buildDirectory.dir("package/caixa-linux-x86_64").get().asFile
        project.delete(packageRoot)
        project.copy { from(install); into(packageRoot) }
        val jlink: File = File(System.getProperty("java.home"), "bin/jlink")
        check(jlink.isFile) { "Bundled JVM packaging requires JDK 17 jlink at '${jlink.absolutePath}'" }
        project.exec {
            commandLine(jlink.absolutePath, "--add-modules", "java.base,java.sql,java.logging,jdk.crypto.ec", "--strip-debug", "--no-man-pages", "--no-header-files", "--output", packageRoot.resolve("jre").absolutePath)
        }
    }
}
