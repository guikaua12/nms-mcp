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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchLimitBehaviorTest {
    @Test
    fun `search honors explicit limit larger than former config cap`() {
        val service = playerSearchService()

        val results = runBlocking {
            service.search(
                SearchQuery(
                    query = "player",
                    version = TEST_VERSION,
                    kind = SymbolKind.CLASS,
                    namespace = MappingNamespace.MOJANG,
                    owner = null,
                    packagePrefix = null,
                    limit = 12
                )
            )
        }

        assertEquals(12, results.size)
    }

    @Test
    fun `search returns all matches when limit is omitted`() {
        val service = playerSearchService()

        val results = runBlocking {
            service.search(
                SearchQuery(
                    query = "player",
                    version = TEST_VERSION,
                    kind = SymbolKind.CLASS,
                    namespace = MappingNamespace.MOJANG,
                    owner = null,
                    packagePrefix = null,
                    limit = null
                )
            )
        }

        assertEquals(12, results.size)
    }

    @Test
    fun `resolve fallback honors provided limit`() {
        val service = playerSearchService()

        val result = runBlocking {
            service.resolve(
                name = "player",
                versionId = TEST_VERSION,
                kind = SymbolKind.CLASS,
                namespace = MappingNamespace.MOJANG,
                owner = null,
                limit = 5
            )
        }

        assertFalse(result.exact)
        assertEquals(5, result.hits.size)
    }

    @Test
    fun `resolve fallback returns all matches when limit is omitted`() {
        val service = playerSearchService()

        val result = runBlocking {
            service.resolve(
                name = "player",
                versionId = TEST_VERSION,
                kind = SymbolKind.CLASS,
                namespace = MappingNamespace.MOJANG,
                owner = null,
                limit = null
            )
        }

        assertFalse(result.exact)
        assertEquals(12, result.hits.size)
    }

    @Test
    fun `resolve can skip fallback when exact matches are required`() {
        val service = playerSearchService()

        val result = runBlocking {
            service.resolve(
                name = "player",
                versionId = TEST_VERSION,
                kind = SymbolKind.CLASS,
                namespace = MappingNamespace.MOJANG,
                owner = null,
                limit = null,
                allowFallback = false
            )
        }

        assertFalse(result.exact)
        assertTrue(result.hits.isEmpty())
    }

    private fun playerSearchService(): SymbolService {
        val cacheDir = createTempDirectory("nms-mcp-search-limit")
        val config = AppConfig(
            cacheDir = cacheDir,
            bundleCoordinate = null,
            defaultVersion = TEST_VERSION,
            primaryNamespace = MappingNamespace.MOJANG,
            snippetsEnabled = false
        )
        val objectMapper = defaultObjectMapper()
        val sqliteIndex = SqliteSymbolIndex(cacheDir.resolve("index.sqlite"), objectMapper)
        sqliteIndex.storeVersion(
            VersionIndex(
                versionId = TEST_VERSION,
                releaseTime = java.time.Instant.parse("2026-01-01T00:00:00Z"),
                indexedAt = java.time.Instant.parse("2026-01-01T00:00:00Z"),
                symbolsById = (1..12).associate { index ->
                    val binaryName = "net.minecraft.test.Player$index"
                    val symbol = IndexedSymbol(
                        symbolId = "$TEST_VERSION|class|player$index",
                        versionId = TEST_VERSION,
                        kind = SymbolKind.CLASS,
                        sourceInternalName = "player$index",
                        sourceName = "player$index",
                        sourceDescriptor = null,
                        ownerSourceInternalName = null,
                        canonicalName = "Player$index",
                        canonicalQualifiedName = binaryName,
                        canonicalBinaryName = binaryName,
                        packageName = "net.minecraft.test",
                        modifiers = 1,
                        signature = null,
                        superClass = "java.lang.Object",
                        interfaces = emptyList(),
                        aliases = mapOf(
                            "mojang" to SymbolAlias(
                                namespace = "mojang",
                                name = "Player$index",
                                qualifiedName = binaryName,
                                binaryName = binaryName,
                                descriptor = null,
                                ownerBinaryName = null
                            )
                        )
                    )
                    symbol.symbolId to symbol
                }
            )
        )

        return SymbolService(
            config = config,
            manifest = VersionManifest(
                latest = VersionManifest.Latest(TEST_VERSION, "25w01a"),
                versions = listOf(
                    Version(
                        id = TEST_VERSION,
                        type = Version.Type.RELEASE,
                        url = "https://example.invalid/$TEST_VERSION.json",
                        time = java.time.Instant.parse("2026-01-01T00:00:00Z"),
                        releaseTime = java.time.Instant.parse("2026-01-01T00:00:00Z"),
                        sha1 = "deadbeef",
                        complianceLevel = 1
                    )
                )
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

    private companion object {
        const val TEST_VERSION = "1.21.11"
    }
}
