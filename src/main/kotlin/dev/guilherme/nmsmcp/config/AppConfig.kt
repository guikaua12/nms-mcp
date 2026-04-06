package dev.guilherme.nmsmcp.config

import dev.guilherme.nmsmcp.model.MappingNamespace
import java.nio.file.Path
import kotlin.io.path.Path

data class AppConfig(
    val cacheDir: Path,
    val bundleCoordinate: String?,
    val defaultVersion: String?,
    val primaryNamespace: MappingNamespace,
    val snippetsEnabled: Boolean,
    val maxSearchResults: Int
) {
    companion object {
        fun fromEnvironment(): AppConfig {
            val cacheDir = Path(System.getenv("NMS_MCP_CACHE_DIR") ?: "cache")
            val bundleCoordinate = System.getenv("NMS_MCP_BUNDLE_COORDINATE")?.trim()?.ifBlank { null }
            val defaultVersion = System.getenv("NMS_MCP_DEFAULT_VERSION")?.trim()?.ifBlank { null }
            val primaryNamespace = MappingNamespace.fromWireName(
                System.getenv("NMS_MCP_PRIMARY_NAMESPACE")?.trim().orEmpty()
            ) ?: MappingNamespace.MOJANG
            val snippetsEnabled = System.getenv("NMS_MCP_ENABLE_SNIPPETS")
                ?.trim()
                ?.lowercase()
                ?.let { it == "1" || it == "true" || it == "yes" }
                ?: true
            val maxSearchResults = System.getenv("NMS_MCP_MAX_SEARCH_RESULTS")
                ?.trim()
                ?.toIntOrNull()
                ?.coerceIn(1, 50)
                ?: 10

            return AppConfig(
                cacheDir = cacheDir,
                bundleCoordinate = bundleCoordinate,
                defaultVersion = defaultVersion,
                primaryNamespace = primaryNamespace,
                snippetsEnabled = snippetsEnabled,
                maxSearchResults = maxSearchResults
            )
        }
    }
}
