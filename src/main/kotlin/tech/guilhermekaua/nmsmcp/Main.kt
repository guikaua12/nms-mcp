package tech.guilhermekaua.nmsmcp

import tech.guilhermekaua.nmsmcp.config.AppConfig
import tech.guilhermekaua.nmsmcp.index.SqliteSymbolIndex
import tech.guilhermekaua.nmsmcp.mappings.createMappingSource
import tech.guilhermekaua.nmsmcp.mappings.defaultObjectMapper
import tech.guilhermekaua.nmsmcp.mcp.NmsMcpServer
import tech.guilhermekaua.nmsmcp.service.SymbolService
import tech.guilhermekaua.nmsmcp.snippets.SnippetService
import me.kcra.takenaka.core.versionManifestFrom
import kotlin.io.path.createDirectories

fun main() {
    val config = _root_ide_package_.tech.guilhermekaua.nmsmcp.config.AppConfig.fromEnvironment()
    config.cacheDir.createDirectories()
    val objectMapper = _root_ide_package_.tech.guilhermekaua.nmsmcp.mappings.defaultObjectMapper()
    val manifest = versionManifestFrom(config.cacheDir.resolve("version_manifest_v2.json"))
    val mappingSource = _root_ide_package_.tech.guilhermekaua.nmsmcp.mappings.createMappingSource(config, manifest)
    val sqliteIndex = _root_ide_package_.tech.guilhermekaua.nmsmcp.index.SqliteSymbolIndex(
        config.cacheDir.resolve("index.sqlite"),
        objectMapper
    )
    val snippetService = _root_ide_package_.tech.guilhermekaua.nmsmcp.snippets.SnippetService(config)
    val symbolService = _root_ide_package_.tech.guilhermekaua.nmsmcp.service.SymbolService(
        config = config,
        manifest = manifest,
        mappingSource = mappingSource,
        sqliteIndex = sqliteIndex,
        snippetService = snippetService
    )

    _root_ide_package_.tech.guilhermekaua.nmsmcp.mcp.NmsMcpServer(
        config = config,
        service = symbolService,
        objectMapper = objectMapper
    ).start()

    while (true) {
        Thread.sleep(60_000)
    }
}
