package dev.guilherme.nmsmcp

import dev.guilherme.nmsmcp.config.AppConfig
import dev.guilherme.nmsmcp.index.SqliteSymbolIndex
import dev.guilherme.nmsmcp.mappings.MappingSource
import dev.guilherme.nmsmcp.mappings.defaultObjectMapper
import dev.guilherme.nmsmcp.model.IndexedSymbol
import dev.guilherme.nmsmcp.model.MappingNamespace
import dev.guilherme.nmsmcp.model.SearchQuery
import dev.guilherme.nmsmcp.model.SymbolAlias
import dev.guilherme.nmsmcp.model.SymbolKind
import dev.guilherme.nmsmcp.model.VersionIndex
import dev.guilherme.nmsmcp.service.SymbolService
import dev.guilherme.nmsmcp.snippets.SnippetService
import kotlinx.coroutines.runBlocking
import me.kcra.takenaka.core.Version
import me.kcra.takenaka.core.VersionManifest
import net.fabricmc.mappingio.tree.MappingTree
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchRankingTest {
    @Test
    fun exactQualifiedMatchRanksFirst() {
        val cacheDir = createTempDirectory("nms-mcp-test")
        val config = AppConfig(
            cacheDir = cacheDir,
            bundleCoordinate = null,
            defaultVersion = "1.21.11",
            primaryNamespace = MappingNamespace.MOJANG,
            snippetsEnabled = false,
            maxSearchResults = 10
        )
        val objectMapper = defaultObjectMapper()
        val sqliteIndex = SqliteSymbolIndex(cacheDir.resolve("index.sqlite"), objectMapper)
        val versionIndex = VersionIndex(
            versionId = "1.21.11",
            releaseTime = java.time.Instant.parse("2026-01-01T00:00:00Z"),
            indexedAt = java.time.Instant.parse("2026-01-01T00:00:00Z"),
            symbolsById = linkedMapOf(
                "1.21.11|class|net/minecraft/server/MinecraftServer" to IndexedSymbol(
                    symbolId = "1.21.11|class|net/minecraft/server/MinecraftServer",
                    versionId = "1.21.11",
                    kind = SymbolKind.CLASS,
                    sourceInternalName = "abc",
                    sourceName = "abc",
                    sourceDescriptor = null,
                    ownerSourceInternalName = null,
                    canonicalName = "MinecraftServer",
                    canonicalQualifiedName = "net.minecraft.server.MinecraftServer",
                    canonicalBinaryName = "net.minecraft.server.MinecraftServer",
                    packageName = "net.minecraft.server",
                    modifiers = 1,
                    signature = null,
                    superClass = null,
                    interfaces = emptyList(),
                    aliases = mapOf(
                        "mojang" to SymbolAlias(
                            namespace = "mojang",
                            name = "MinecraftServer",
                            qualifiedName = "net.minecraft.server.MinecraftServer",
                            binaryName = "net.minecraft.server.MinecraftServer",
                            descriptor = null,
                            ownerBinaryName = null
                        )
                    )
                )
            )
        )
        sqliteIndex.storeVersion(versionIndex)

        val manifest = VersionManifest(
            latest = VersionManifest.Latest("1.21.11", "25w01a"),
            versions = listOf(
                Version(
                    id = "1.21.11",
                    type = Version.Type.RELEASE,
                    url = "https://example.invalid/1.21.11.json",
                    time = java.time.Instant.parse("2026-01-01T00:00:00Z"),
                    releaseTime = java.time.Instant.parse("2026-01-01T00:00:00Z"),
                    sha1 = "deadbeef",
                    complianceLevel = 1
                )
            )
        )

        val service = SymbolService(
            config = config,
            manifest = manifest,
            mappingSource = object : MappingSource {
                override suspend fun load(versionIds: List<String>, analyzer: me.kcra.takenaka.core.mapping.analysis.MappingAnalyzer?): Map<Version, MappingTree> =
                    emptyMap()
            },
            sqliteIndex = sqliteIndex,
            snippetService = SnippetService(config)
        )

        val results = runBlocking {
            service.search(
                SearchQuery(
                    query = "net.minecraft.server.MinecraftServer",
                    version = "1.21.11",
                    kind = SymbolKind.CLASS,
                    namespace = MappingNamespace.MOJANG,
                    owner = null,
                    packagePrefix = null,
                    limit = 5
                )
            )
        }

        assertEquals(1, results.size)
        assertEquals("1.21.11|class|net/minecraft/server/MinecraftServer", results.first().symbol.symbolId)
    }
}
