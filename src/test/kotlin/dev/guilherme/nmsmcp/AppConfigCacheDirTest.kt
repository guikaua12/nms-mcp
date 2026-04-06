package dev.guilherme.nmsmcp

import dev.guilherme.nmsmcp.config.AppConfig
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class AppConfigCacheDirTest {
    @Test
    fun `cache dir override keeps highest precedence`() {
        val config = AppConfig.fromEnvironment(
            environment = mapOf(
                "NMS_MCP_CACHE_DIR" to "custom-cache",
                "LOCALAPPDATA" to "C:\\Users\\Example\\AppData\\Local",
                "XDG_CACHE_HOME" to "/tmp/xdg-cache"
            ),
            osName = "Windows 11",
            userHome = "C:\\Users\\Example"
        )

        assertEquals(Path("custom-cache"), config.cacheDir)
    }

    @Test
    fun `windows defaults to local app data cache`() {
        val config = AppConfig.fromEnvironment(
            environment = mapOf("LOCALAPPDATA" to "C:\\Users\\Example\\AppData\\Local"),
            osName = "Windows 11",
            userHome = "C:\\Users\\Example"
        )

        assertEquals(
            Path("C:\\Users\\Example\\AppData\\Local", "nms-mcp", "cache"),
            config.cacheDir
        )
    }

    @Test
    fun `macos defaults to library caches`() {
        val config = AppConfig.fromEnvironment(
            environment = mapOf("HOME" to "/Users/example"),
            osName = "Mac OS X",
            userHome = "/Users/example"
        )

        assertEquals(
            Path("/Users/example", "Library", "Caches", "nms-mcp"),
            config.cacheDir
        )
    }

    @Test
    fun `linux prefers xdg cache home`() {
        val config = AppConfig.fromEnvironment(
            environment = mapOf(
                "HOME" to "/home/example",
                "XDG_CACHE_HOME" to "/var/cache/example"
            ),
            osName = "Linux",
            userHome = "/home/example"
        )

        assertEquals(Path("/var/cache/example", "nms-mcp"), config.cacheDir)
    }

    @Test
    fun `linux falls back to home cache when xdg cache home is missing`() {
        val config = AppConfig.fromEnvironment(
            environment = mapOf("HOME" to "/home/example"),
            osName = "Linux",
            userHome = "/home/example"
        )

        assertEquals(Path("/home/example", ".cache", "nms-mcp"), config.cacheDir)
    }

    @Test
    fun `missing os specific location falls back to relative cache`() {
        val config = AppConfig.fromEnvironment(
            environment = emptyMap(),
            osName = "Windows 11",
            userHome = null
        )

        assertEquals(Path("cache"), config.cacheDir)
    }
}
