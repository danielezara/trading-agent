package com.tradingagent.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class DemoKeySlotTest {
    @Test
    fun `missing keys are incomplete and the slot does not retain values`() {
        val missing = DemoKeySlot.fromEnvironment(emptyMap())
        assertFalse(missing.apiKeyPresent)
        assertFalse(missing.userKeyPresent)
        assertFalse(missing.complete())

        val blank = DemoKeySlot.fromEnvironment(
            mapOf(
                DemoKeySlot.API_KEY_ENV to " ",
                DemoKeySlot.USER_KEY_ENV to "",
            ),
        )
        assertFalse(blank.complete())

        val apiValue = "local-demo-api-value"
        val userValue = "local-demo-user-value"
        val present = DemoKeySlot.fromEnvironment(
            mapOf(
                DemoKeySlot.API_KEY_ENV to apiValue,
                DemoKeySlot.USER_KEY_ENV to userValue,
            ),
        )
        assertTrue(present.complete())
        assertFalse(present.toString().contains(apiValue))
        assertFalse(present.toString().contains(userValue))
        assertFalse(present.toString().contains(DemoKeySlot.API_KEY_ENV))
    }

    @Test
    fun `the example file names the two keys and leaves them empty`() {
        val file = exampleFile()
        val assignments = file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals(
            listOf(
                "${DemoKeySlot.API_KEY_ENV}=",
                "${DemoKeySlot.USER_KEY_ENV}=",
            ),
            assignments,
        )
    }

    private fun exampleFile(): File {
        val candidates = listOf(File("backend/.env.example"), File(".env.example"))
        return candidates.firstOrNull { it.isFile } ?: error("backend/.env.example is missing")
    }
}
