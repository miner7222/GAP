package io.github.miner7222.gap.ui

import io.github.miner7222.gap.SupportedPackageList
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageManagerControllerRootScriptTest {

    @Test
    fun saveScriptBindsSystemFileLabeledModuleMirrorBeforeRuntimeFallback() {
        val script = buildRootScript(useOverlay = true)

        assertTrue(script.contains("chcon u:object_r:system_file:s0 '${SupportedPackageList.MODULE_LIST_PATH}'"))
        assertTrue(script.contains("SOURCE_LIST='${SupportedPackageList.MODULE_LIST_PATH}'"))
        assertTrue(script.contains("SOURCE_LIST='${SupportedPackageList.RUNTIME_LIST_PATH}'"))
        assertTrue(
            script.indexOf("SOURCE_LIST='${SupportedPackageList.MODULE_LIST_PATH}'") <
                script.indexOf("SOURCE_LIST='${SupportedPackageList.RUNTIME_LIST_PATH}'"),
        )
    }

    @Test
    fun saveScriptRestartsNativeGppServiceOnSm8850pInsteadOfStartingDuplicateDaemon() {
        val script = buildRootScript(useOverlay = true)

        assertTrue(script.contains("SOC_MODEL=\"$(getprop ro.soc.model"))
        assertTrue(script.contains("[ \"${'$'}SOC_MODEL\" = 'SM8850P' ]"))
        assertTrue(script.contains("stop vendor.gppservice"))
        assertTrue(script.contains("start vendor.gppservice"))
        assertTrue(script.contains("[ \"${'$'}SOC_MODEL\" = 'SM8750P' ]"))
        assertTrue(script.contains("runcon u:r:vendor_gppservice:s0 /system/bin/gppservice"))
    }

    @Test
    fun saveScriptForceKillsGameHelperAfterSavingRuntimeList() {
        val script = buildRootScript(useOverlay = true)

        assertTrue(script.contains("am force-stop ${SupportedPackageList.GAME_HELPER_PACKAGE}"))
        assertTrue(script.contains("kill_process_by_name ${SupportedPackageList.GAME_HELPER_PACKAGE}"))
        assertTrue(script.contains("kill -9 ${'$'}PIDS 2>/dev/null || true"))
    }

    @Test
    fun saveScriptForceKillsGppServiceBeforeRestartingIt() {
        val script = buildRootScript(useOverlay = true)

        assertTrue(script.contains("kill_process_by_name gppservice"))
        assertTrue(
            script.indexOf("kill_process_by_name gppservice") <
                script.indexOf("setprop vendor.gpp.create_frc_extension 1"),
        )
    }

    @Test
    fun restoreDefaultScriptRefreshesBackingFilesBeforeRemovingOverride() {
        val script = buildRootScript(useOverlay = false)

        val refreshRuntime = "cat '/data/local/tmp/gpp_app_list.generated' > '${SupportedPackageList.RUNTIME_LIST_PATH}'"
        val refreshModule = "cat '/data/local/tmp/gpp_app_list.generated' > '${SupportedPackageList.MODULE_LIST_PATH}'"
        val removeOverrides = "rm -f '${SupportedPackageList.RUNTIME_LIST_PATH}' '${SupportedPackageList.MODULE_LIST_PATH}'"
        val unbindCall = "\nunbind_active_list\n"

        assertTrue(script.contains(refreshRuntime))
        assertTrue(script.contains(refreshModule))
        assertTrue(script.contains(removeOverrides))
        assertTrue(script.contains(unbindCall))
        assertTrue(script.indexOf(refreshRuntime) < script.indexOf(removeOverrides))
        assertTrue(script.indexOf(refreshModule) < script.indexOf(removeOverrides))
        assertTrue(script.indexOf(unbindCall) < script.indexOf(removeOverrides))
    }

    private fun buildRootScript(useOverlay: Boolean): String {
        val method = PackageManagerController::class.java.getDeclaredMethod(
            "buildRootScript",
            String::class.java,
            Boolean::class.javaPrimitiveType,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(
            PackageManagerController,
            "/data/local/tmp/gpp_app_list.generated",
            useOverlay,
            "com.example.game",
        ) as String
    }
}
