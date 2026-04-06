package dev.guilherme.nmsmcp.mappings

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.guilherme.nmsmcp.config.AppConfig
import me.kcra.takenaka.core.CompositeWorkspace
import me.kcra.takenaka.core.Version
import me.kcra.takenaka.core.VersionManifest
import me.kcra.takenaka.core.compositeWorkspace
import me.kcra.takenaka.core.mapping.analysis.MappingAnalyzer
import me.kcra.takenaka.core.mapping.resolve.impl.HashedMappingResolver
import me.kcra.takenaka.core.mapping.resolve.impl.IntermediaryMappingResolver
import me.kcra.takenaka.core.mapping.resolve.impl.MojangServerMappingResolver
import me.kcra.takenaka.core.mapping.resolve.impl.QuiltMappingResolver
import me.kcra.takenaka.core.mapping.resolve.impl.SeargeMappingResolver
import me.kcra.takenaka.core.mapping.resolve.impl.SpigotClassMappingResolver
import me.kcra.takenaka.core.mapping.resolve.impl.SpigotMemberMappingResolver
import me.kcra.takenaka.core.mapping.resolve.impl.VanillaServerMappingContributor
import me.kcra.takenaka.core.mapping.resolve.impl.YarnMappingResolver
import me.kcra.takenaka.generator.common.provider.impl.BundledMappingProvider
import me.kcra.takenaka.generator.common.provider.impl.ResolvingMappingProvider
import me.kcra.takenaka.generator.common.provider.impl.buildMappingConfig
import net.fabricmc.mappingio.tree.MappingTree
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

interface MappingSource {
    suspend fun load(versionIds: List<String>, analyzer: MappingAnalyzer? = null): Map<Version, MappingTree>
}

class CompositeMappingSource(private val delegates: List<MappingSource>) : MappingSource {
    override suspend fun load(versionIds: List<String>, analyzer: MappingAnalyzer?): Map<Version, MappingTree> {
        val remaining = versionIds.toMutableSet()
        val result = linkedMapOf<Version, MappingTree>()

        for (delegate in delegates) {
            if (remaining.isEmpty()) {
                break
            }

            val loaded = delegate.load(remaining.toList(), analyzer)
            result.putAll(loaded)
            remaining.removeAll(loaded.keys.map { it.id }.toSet())
        }

        return result
    }
}

class BundledMappingSource(
    private val bundleFile: Path,
    private val manifest: VersionManifest
) : MappingSource {
    override suspend fun load(versionIds: List<String>, analyzer: MappingAnalyzer?): Map<Version, MappingTree> {
        if (!Files.exists(bundleFile)) {
            return emptyMap()
        }

        return BundledMappingProvider(bundleFile, versionIds, manifest).get(analyzer)
    }
}

class TakenakaResolvingMappingSource(
    private val workspace: CompositeWorkspace,
    private val manifest: VersionManifest
) : MappingSource {
    override suspend fun load(versionIds: List<String>, analyzer: MappingAnalyzer?): Map<Version, MappingTree> {
        val config = buildMappingConfig {
            version(versionIds)
            workspace(workspace)
            contributors { versionedWorkspace ->
                listOf(
                    VanillaServerMappingContributor(versionedWorkspace),
                    MojangServerMappingResolver(versionedWorkspace),
                    SpigotClassMappingResolver(versionedWorkspace, false),
                    SpigotMemberMappingResolver(versionedWorkspace, false),
                    SeargeMappingResolver(versionedWorkspace),
                    IntermediaryMappingResolver(versionedWorkspace),
                    YarnMappingResolver(versionedWorkspace, false),
                    HashedMappingResolver(versionedWorkspace),
                    QuiltMappingResolver(versionedWorkspace, false)
                )
            }
            joinedOutputPath { versionedWorkspace -> versionedWorkspace["joined.tiny"] }
        }

        return ResolvingMappingProvider(config, manifest).get(analyzer)
    }
}

class MavenBundleResolver(private val cacheDir: Path) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun resolve(coordinate: String): Path {
        val parts = coordinate.split(':')
        require(parts.size == 3) {
            "Expected Maven coordinate in the form group:artifact:version, got '$coordinate'"
        }

        val (group, artifact, version) = parts
        val targetDir = cacheDir.resolve("bundles").createDirectories()
        val targetFile = targetDir.resolve("${group}_${artifact}_${version}.jar".replace(':', '_').replace('+', '_'))
        if (targetFile.exists()) {
            return targetFile
        }

        val groupPath = group.replace('.', '/')
        val base = "https://repo.screamingsandals.org/public/$groupPath/$artifact/$version"
        val fileName = if (version.endsWith("-SNAPSHOT")) {
            val metadata = fetchText("$base/maven-metadata.xml")
            val snapshotValue = SNAPSHOT_VALUE_REGEX.find(metadata)?.groupValues?.get(1)
                ?: error("Could not resolve snapshot jar for $coordinate")
            "$artifact-$snapshotValue.jar"
        } else {
            "$artifact-$version.jar"
        }

        download("$base/$fileName", targetFile)
        return targetFile
    }

    private fun fetchText(url: String): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(30))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        check(response.statusCode() in 200..299) {
            "Failed to fetch $url: HTTP ${response.statusCode()}"
        }
        return response.body()
    }

    private fun download(url: String, target: Path) {
        Files.createDirectories(target.parent)
        val request = HttpRequest.newBuilder(URI.create(url))
            .GET()
            .timeout(Duration.ofMinutes(2))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(target))
        check(response.statusCode() in 200..299) {
            "Failed to download $url: HTTP ${response.statusCode()}"
        }
    }

    private companion object {
        val SNAPSHOT_VALUE_REGEX = Regex(
            "<snapshotVersion>\\s*<extension>jar</extension>\\s*<value>([^<]+)</value>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
    }
}

fun createMappingSource(config: AppConfig, manifest: VersionManifest): MappingSource {
    val workspaceRoot = config.cacheDir.resolve("takenaka")
    val workspace = compositeWorkspace { rootDirectory(workspaceRoot) }
    val delegates = mutableListOf<MappingSource>()

    val bundleCoordinate = config.bundleCoordinate
    if (bundleCoordinate != null) {
        runCatching {
            val bundleFile = MavenBundleResolver(config.cacheDir).resolve(bundleCoordinate)
            delegates += BundledMappingSource(bundleFile, manifest)
        }
    }

    delegates += TakenakaResolvingMappingSource(workspace, manifest)
    return CompositeMappingSource(delegates)
}

fun defaultObjectMapper() = jacksonObjectMapper().findAndRegisterModules()
