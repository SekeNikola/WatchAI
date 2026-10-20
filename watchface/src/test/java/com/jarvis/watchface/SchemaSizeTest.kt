package com.jarvis.watchface

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The style schema (with its option icons) must stay under the library's 50 000-byte limit. */
class SchemaSizeTest {
    @Test
    fun schemaIconsAreSmall() {
        val dir = File("src/main/res/drawable-nodpi")
        val total = dir.listFiles { f -> f.name.endsWith("_small.png") }!!.sumOf { it.length() }
        assertTrue("schema icons are $total bytes", total in 1..30_000)
    }
}
