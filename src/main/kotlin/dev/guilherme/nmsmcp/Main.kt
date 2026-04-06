package dev.guilherme.nmsmcp

import dev.guilherme.nmsmcp.config.AppConfig
import dev.guilherme.nmsmcp.index.SqliteSymbolIndex
import dev.guilherme.nmsmcp.mappings.createMappingSource
import dev.guilherme.nmsmcp.mappings.defaultObjectMapper
import dev.guilherme.nmsmcp.mcp.NmsMcpServer
import dev.guilherme.nmsmcp.service.SymbolService
import dev.guilherme.nmsmcp.snippets.SnippetService
import me.kcra.takenaka.core.versionManifestFrom
import kotlin.io.path.createDirectories

fun main() {
    val config = AppConfig.fromEnvironment()
    config.cacheDir.createDirectories()
    val objectMapper = defaultObjectMapper()
    val manifest = versionManifestFrom(config.cacheDir.resolve("version_manifest_v2.json"))
    val mappingSource = createMappingSource(config, manifest)
    val sqliteIndex = SqliteSymbolIndex(config.cacheDir.resolve("index.sqlite"), objectMapper)
    val snippetService = SnippetService(config)
    val symbolService = SymbolService(
        config = config,
        manifest = manifest,
        mappingSource = mappingSource,
        sqliteIndex = sqliteIndex,
        snippetService = snippetService
    )

    NmsMcpServer(
        config = config,
        service = symbolService,
        objectMapper = objectMapper
    ).start()

    while (true) {
        Thread.sleep(60_000)
    }
}
