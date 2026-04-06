import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.io.File
import java.security.MessageDigest
import org.gradle.api.file.FileTree
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.jvm.tasks.Jar

plugins {
    kotlin("jvm") version "2.1.21"
    application
    id("com.gradleup.shadow") version "8.3.10"
}

group = "tech.guilhermekaua.nmsmcp"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("tech.guilhermekaua.nmsmcp.MainKt")
}

// Ship project and third-party licensing information with the jar and app distribution.
distributions {
    main {
        contents {
            from("LICENSE")
            from("README.md")
            from("THIRD_PARTY_NOTICES.md")
            from("THIRD_PARTY_LICENSES") {
                into("THIRD_PARTY_LICENSES")
            }
        }
    }
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

val jarTask = tasks.named<Jar>("jar")
val distZipTask = tasks.named<Zip>("distZip")
val distTarTask = tasks.named<Tar>("distTar") {
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
}
val shadowJarTask = tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("all")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
    metaInf {
        from("LICENSE")
        from("THIRD_PARTY_NOTICES.md")
        from("THIRD_PARTY_LICENSES") {
            into("THIRD_PARTY_LICENSES")
        }
    }
}

tasks.assemble {
    dependsOn(shadowJarTask)
}

tasks.register("printVersion") {
    description = "Prints the project version without extra Gradle output."
    group = "help"
    doLast {
        println(project.version)
    }
}

tasks.register("generateReleaseChecksums") {
    description = "Generates SHA-256 checksums for the public release artifacts."
    group = "distribution"
    dependsOn(shadowJarTask, distZipTask, distTarTask)

    val checksumFile = layout.buildDirectory.file("distributions/SHA256SUMS.txt")
    outputs.file(checksumFile)

    doLast {
        val artifacts = listOf(
            distZipTask.get().archiveFile.get().asFile,
            distTarTask.get().archiveFile.get().asFile,
            shadowJarTask.get().archiveFile.get().asFile
        )
        val lines = artifacts.joinToString(System.lineSeparator()) { artifact ->
            "${artifact.sha256()}  ${artifact.name}"
        } + System.lineSeparator()
        checksumFile.get().asFile.writeText(lines)
    }
}

tasks.register("verifyReleaseArtifacts") {
    description = "Checks that the release artifacts exist and that distributions contain launchers and libraries."
    group = "verification"
    dependsOn(shadowJarTask, distZipTask, distTarTask)

    doLast {
        val shadowJarFile = shadowJarTask.get().archiveFile.get().asFile
        val distZipFile = distZipTask.get().archiveFile.get().asFile
        val distTarFile = distTarTask.get().archiveFile.get().asFile
        val thinJarName = jarTask.get().archiveFileName.get()
        val distributionRoot = "${project.name}-${project.version}"

        check(shadowJarFile.isFile) { "Missing shaded release jar: ${shadowJarFile.absolutePath}" }
        check(distZipFile.isFile) { "Missing distribution zip: ${distZipFile.absolutePath}" }
        check(distTarFile.isFile) { "Missing distribution tar.gz: ${distTarFile.absolutePath}" }
        check(shadowJarFile.name == "${project.name}-${project.version}-all.jar") {
            "Unexpected shaded jar name: ${shadowJarFile.name}"
        }

        verifyDistributionContents(
            archiveName = distZipFile.name,
            entries = zipTree(distZipFile).archiveEntries(),
            distributionRoot = distributionRoot,
            thinJarName = thinJarName
        )
        verifyDistributionContents(
            archiveName = distTarFile.name,
            entries = tarTree(distTarFile).archiveEntries(),
            distributionRoot = distributionRoot,
            thinJarName = thinJarName
        )
    }
}

tasks.named<Jar>("jar") {
    metaInf {
        from("LICENSE")
        from("THIRD_PARTY_NOTICES.md")
        from("THIRD_PARTY_LICENSES") {
            into("THIRD_PARTY_LICENSES")
        }
    }
}

fun verifyDistributionContents(
    archiveName: String,
    entries: Set<String>,
    distributionRoot: String,
    thinJarName: String
) {
    val requiredEntries = setOf(
        "$distributionRoot/bin/nms-mcp",
        "$distributionRoot/bin/nms-mcp.bat",
        "$distributionRoot/lib/$thinJarName"
    )

    requiredEntries.forEach { requiredEntry ->
        check(requiredEntry in entries) {
            "$archiveName is missing required entry $requiredEntry"
        }
    }

    val libraryEntries = entries.filter { it.startsWith("$distributionRoot/lib/") && it.endsWith(".jar") }
    check(libraryEntries.size >= 2) {
        "$archiveName must contain the application jar plus runtime dependency jars"
    }
}

fun FileTree.archiveEntries(): Set<String> {
    val entries = linkedSetOf<String>()
    visit {
        if (!isDirectory) {
            entries += relativePath.pathString
        }
    }
    return entries
}

fun File.sha256(): String =
    inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
