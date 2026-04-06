plugins {
    kotlin("jvm") version "2.1.21"
    application
}

group = "dev.guilherme.nmsmcp"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.guilherme.nmsmcp.MainKt")
}

configurations.all {
    resolutionStrategy.force("net.fabricmc:mapping-io:0.4.2")
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("io.modelcontextprotocol.sdk:mcp:0.14.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.3")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("org.slf4j:slf4j-simple:2.0.16")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("me.kcra.takenaka:core:1.2.1-SNAPSHOT")
    implementation("me.kcra.takenaka:generator-common:1.2.1-SNAPSHOT")
    // Takenaka 1.2.1-SNAPSHOT still expects the legacy ProGuardReader class from mapping-io 0.4.x.
    implementation("net.fabricmc:mapping-io:0.4.2")
    implementation("net.fabricmc:tiny-remapper:0.13.1")
    implementation("org.benf:cfr:0.152")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
