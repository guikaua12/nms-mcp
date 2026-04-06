package dev.guilherme.nmsmcp

import kotlin.test.Test
import kotlin.test.assertNotNull

class RuntimeClasspathCompatibilityTest {
    @Test
    fun `takenaka compatible mapping io classes are on the runtime classpath`() {
        val proGuardReader = runCatching {
            Class.forName("net.fabricmc.mappingio.format.ProGuardReader")
        }.getOrNull()
        val tinyUtils = runCatching {
            Class.forName("net.fabricmc.tinyremapper.TinyUtils")
        }.getOrNull()

        assertNotNull(proGuardReader, "Takenaka requires mapping-io 0.4.x at runtime")
        assertNotNull(tinyUtils, "Snippet remapping support should stay available")
    }
}
