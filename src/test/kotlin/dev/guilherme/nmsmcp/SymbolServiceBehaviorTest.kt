package dev.guilherme.nmsmcp

import dev.guilherme.nmsmcp.config.AppConfig
import dev.guilherme.nmsmcp.index.SqliteSymbolIndex
import dev.guilherme.nmsmcp.index.SymbolIndexBuilder
import dev.guilherme.nmsmcp.mappings.MappingSource
import dev.guilherme.nmsmcp.mappings.defaultObjectMapper
import dev.guilherme.nmsmcp.model.MappingNamespace
import dev.guilherme.nmsmcp.service.SymbolService
import dev.guilherme.nmsmcp.snippets.SnippetService
import kotlinx.coroutines.runBlocking
import me.kcra.takenaka.core.Version
import me.kcra.takenaka.core.VersionManifest
import net.fabricmc.mappingio.MappedElementKind
import net.fabricmc.mappingio.tree.MappingTree
import net.fabricmc.mappingio.tree.MemoryMappingTree
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SymbolServiceBehaviorTest {
    @Test
    fun `describe rejects non canonical symbol ids with a clear error`() {
        val service = emptyService()

        val error = assertFailsWith<IllegalArgumentException> {
            runBlocking { service.describe("EntityZombie") }
        }

        assertContains(error.message.orEmpty(), "canonical server-returned identifier")
    }

    @Test
    fun `snippet rejects non canonical symbol ids with a clear error`() {
        val service = emptyService()

        val error = assertFailsWith<IllegalArgumentException> {
            runBlocking { service.snippet("EntityZombie", 5, 5) }
        }

        assertContains(error.message.orEmpty(), "canonical server-returned identifier")
    }

    @Test
    fun `compare aligns classes even when source namespace is requested`() {
        val cacheDir = createTempDirectory("nms-mcp-compare")
        val config = AppConfig(
            cacheDir = cacheDir,
            bundleCoordinate = null,
            defaultVersion = "1.21.11",
            primaryNamespace = MappingNamespace.SEARGE,
            snippetsEnabled = false
        )
        val objectMapper = defaultObjectMapper()
        val sqliteIndex = SqliteSymbolIndex(cacheDir.resolve("index.sqlite"), objectMapper)
        val version188 = releaseVersion("1.8.8", "2026-01-01T00:00:00Z")
        val version12111 = releaseVersion("1.21.11", "2026-02-01T00:00:00Z")
        val tree188 = classTree("we", "net/minecraft/entity/monster/EntityZombie")
        val tree12111 = classTree("dcn", "net/minecraft/entity/monster/EntityZombie")
        val indexBuilder = SymbolIndexBuilder(config)

        sqliteIndex.storeVersion(indexBuilder.build(version188, tree188))
        sqliteIndex.storeVersion(indexBuilder.build(version12111, tree12111))

        val service = SymbolService(
            config = config,
            manifest = VersionManifest(
                latest = VersionManifest.Latest("1.21.11", "25w01a"),
                versions = listOf(version188, version12111)
            ),
            mappingSource = object : MappingSource {
                override suspend fun load(
                    versionIds: List<String>,
                    analyzer: me.kcra.takenaka.core.mapping.analysis.MappingAnalyzer?
                ): Map<Version, MappingTree> = buildMap {
                    if (version188.id in versionIds) {
                        put(version188, tree188)
                    }
                    if (version12111.id in versionIds) {
                        put(version12111, tree12111)
                    }
                }
            },
            sqliteIndex = sqliteIndex,
            snippetService = SnippetService(config)
        )

        val result = runBlocking {
            service.compare("EntityZombie", "1.8.8", "1.21.11", MappingNamespace.SOURCE)
        }

        assertEquals("1.21.11|class|dcn", result.toSymbol?.symbolId)
        assertEquals("renamed", result.relationship)
    }

    private fun emptyService(): SymbolService {
        val cacheDir = createTempDirectory("nms-mcp-validation")
        val config = AppConfig(
            cacheDir = cacheDir,
            bundleCoordinate = null,
            defaultVersion = "1.21.11",
            primaryNamespace = MappingNamespace.MOJANG,
            snippetsEnabled = false
        )
        val objectMapper = defaultObjectMapper()
        val sqliteIndex = SqliteSymbolIndex(cacheDir.resolve("index.sqlite"), objectMapper)

        return SymbolService(
            config = config,
            manifest = VersionManifest(
                latest = VersionManifest.Latest("1.21.11", "25w01a"),
                versions = listOf(releaseVersion("1.21.11", "2026-01-01T00:00:00Z"))
            ),
            mappingSource = object : MappingSource {
                override suspend fun load(
                    versionIds: List<String>,
                    analyzer: me.kcra.takenaka.core.mapping.analysis.MappingAnalyzer?
                ): Map<Version, MappingTree> = emptyMap()
            },
            sqliteIndex = sqliteIndex,
            snippetService = SnippetService(config)
        )
    }

    private fun classTree(sourceName: String, seargeInternalName: String): MemoryMappingTree =
        MemoryMappingTree().apply {
            visitNamespaces("source", listOf("searge"))
            check(visitClass(sourceName))
            visitDstName(MappedElementKind.CLASS, 0, seargeInternalName)
            visitEnd()
        }

    private fun releaseVersion(id: String, releaseTime: String): Version =
        Version(
            id = id,
            type = Version.Type.RELEASE,
            url = "https://example.invalid/$id.json",
            time = java.time.Instant.parse(releaseTime),
            releaseTime = java.time.Instant.parse(releaseTime),
            sha1 = "deadbeef",
            complianceLevel = 1
        )
}
