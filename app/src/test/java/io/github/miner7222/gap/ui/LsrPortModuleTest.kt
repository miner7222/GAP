package io.github.miner7222.gap.ui

import io.github.miner7222.gap.SupportedPackageList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LsrPortModuleTest {

    @Test
    fun exposesExpectedModuleDirectoryAndDownloadUrl() {
        assertEquals(
            "/data/adb/modules/${SupportedPackageList.MODULE_ID}",
            LsrPortModule.MODULE_DIR,
        )
        assertEquals(
            "https://gitlab.com/miner7222/lsrport/-/releases",
            LsrPortModule.RELEASES_URL,
        )
    }

    @Test
    fun modulePresenceCommandChecksDirectory() {
        val command = LsrPortModule.existsCommand()

        assertTrue(command.contains("[ -d '${LsrPortModule.MODULE_DIR}' ]"))
        assertTrue(command.contains("echo 1"))
        assertTrue(command.contains("echo 0"))
    }
}
