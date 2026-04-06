package dev.guilherme.nmsmcp.config

import dev.guilherme.nmsmcp.model.MappingNamespace
import java.nio.file.Path
import kotlin.io.path.Path

data class AppConfig(
    val cacheDir: Path,
    val bundleCoordinate: String?,
    val defaultVersion: String?,
    val primaryNamespace: MappingNamespace,
    val snippetsEnabled: Boolean
) {
    companion object {
        fun fromEnvironment(
            environment: Map<String, String> = System.getenv(),
            osName: String = System.getProperty("os.name").orEmpty(),
            userHome: String? = System.getProperty("user.home")
        ): AppConfig {
            val cacheDir = resolveCacheDir(environment, osName, userHome)
            val bundleCoordinate = environment["NMS_MCP_BUNDLE_COORDINATE"]?.trim()?.ifBlank { null }
            val defaultVersion = environment["NMS_MCP_DEFAULT_VERSION"]?.trim()?.ifBlank { null }
            val primaryNamespace = MappingNamespace.fromWireName(
                environment["NMS_MCP_PRIMARY_NAMESPACE"]?.trim().orEmpty()
            ) ?: MappingNamespace.MOJANG
            val snippetsEnabled = environment["NMS_MCP_ENABLE_SNIPPETS"]
                ?.trim()
                ?.lowercase()
                ?.let { it == "1" || it == "true" || it == "yes" }
                ?: true

            return AppConfig(
                cacheDir = cacheDir,
                bundleCoordinate = bundleCoordinate,
                defaultVersion = defaultVersion,
                primaryNamespace = primaryNamespace,
                snippetsEnabled = snippetsEnabled
            )
        }

        private fun resolveCacheDir(
            environment: Map<String, String>,
            osName: String,
            userHome: String?
        ): Path {
            environment["NMS_MCP_CACHE_DIR"]?.let { return Path(it) }

            val normalizedOsName = osName.lowercase()
            val homeDir = environment["HOME"]?.trim()?.ifBlank { null }
                ?: userHome?.trim()?.ifBlank { null }

            return when {
                normalizedOsName.contains("win") -> environment["LOCALAPPDATA"]
                    ?.trim()
                    ?.ifBlank { null }
                    ?.let { Path(it, "nms-mcp", "cache") }
                normalizedOsName.contains("mac") || normalizedOsName.contains("darwin") -> homeDir
                    ?.let { Path(it, "Library", "Caches", "nms-mcp") }
                normalizedOsName.contains("linux") || normalizedOsName.contains("nix") || normalizedOsName.contains("nux") -> {
                    environment["XDG_CACHE_HOME"]
                        ?.trim()
                        ?.ifBlank { null }
                        ?.let { Path(it, "nms-mcp") }
                        ?: homeDir?.let { Path(it, ".cache", "nms-mcp") }
                }
                else -> null
            } ?: Path("cache")
        }
    }
}
