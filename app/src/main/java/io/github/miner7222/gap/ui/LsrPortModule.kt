package io.github.miner7222.gap.ui

import io.github.miner7222.gap.SupportedPackageList

internal object LsrPortModule {

    const val RELEASES_URL = "https://gitlab.com/miner7222/lsrport/-/releases"
    const val MODULE_DIR = "/data/adb/modules/${SupportedPackageList.MODULE_ID}"

    fun existsCommand(): String {
        return """
            if [ -d '$MODULE_DIR' ]; then
              echo 1
            else
              echo 0
            fi
        """.trimIndent()
    }
}
