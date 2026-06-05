package io.github.miner7222.gap

import java.io.File
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XposedMetadataTest {

    @Test
    fun declaresModernJavaEntry() {
        assertEquals(
            listOf("io.github.miner7222.gap.MainHook"),
            readResourceLines("META-INF/xposed/java_init.list"),
        )
    }

    @Test
    fun declaresApi101StaticScopeModuleProperties() {
        val properties = Properties().apply {
            resourceFile("META-INF/xposed/module.prop").inputStream().use(::load)
        }

        assertEquals("101", properties.getProperty("minApiVersion"))
        assertEquals("101", properties.getProperty("targetApiVersion"))
        assertEquals("true", properties.getProperty("staticScope"))
    }

    @Test
    fun scopeListMatchesRequiredGapTargets() {
        assertEquals(
            listOf("system", "com.zui.game.service"),
            readResourceLines("META-INF/xposed/scope.list"),
        )
    }

    @Test
    fun legacyXposedEntryIsAbsent() {
        assertFalse(appDir.resolve("src/main/assets/xposed_init").exists())
        assertFalse(appDir.resolve("src/main/resources/assets/xposed_init").exists())
    }

    @Test
    fun proguardKeepsModernEntryAndAdaptsEntryResource() {
        val rules = appDir.resolve("proguard-rules.pro").readText()

        assertTrue(rules.contains("-keep class io.github.miner7222.gap.MainHook { *; }"))
        assertTrue(rules.contains("-adaptresourcefilecontents META-INF/xposed/java_init.list"))
        assertFalse(rules.contains("io.github.miner7222.lsr."))
    }

    private fun readResourceLines(path: String): List<String> {
        return resourceFile(path)
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun resourceFile(path: String): File {
        return appDir.resolve("src/main/resources").resolve(path)
    }

    private companion object {
        private val projectRoot: File = findProjectRoot()
        private val appDir: File = projectRoot.resolve("app")

        private fun findProjectRoot(): File {
            val userDir = System.getProperty("user.dir") ?: error("user.dir is not set")
            var current: File? = File(userDir).absoluteFile
            while (current != null) {
                if (current.resolve("app/src/main").isDirectory) {
                    return current
                }
                current = current.parentFile
            }
            error("Could not locate GAP project root from $userDir")
        }
    }
}
