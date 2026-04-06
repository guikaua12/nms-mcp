package tech.guilhermekaua.nmsmcp.mcp

import kotlin.test.Test
import kotlin.test.assertEquals

class NmsMcpServerLimitTest {
    @Test
    fun `omitted fallback limit stays omitted`() {
        assertEquals(null, parseExplicitResultLimit(rawValue = null, maximum = 100))
    }

    @Test
    fun `fallback limit is clamped to the configured maximum`() {
        assertEquals(100, parseExplicitResultLimit(rawValue = 500, maximum = 100))
    }

    @Test
    fun `fallback limit is clamped to the minimum`() {
        assertEquals(1, parseExplicitResultLimit(rawValue = 0, maximum = 100))
    }

    @Test
    fun `fallback limit parses numeric strings before clamping`() {
        assertEquals(100, parseExplicitResultLimit(rawValue = "250", maximum = 100))
    }
}
