package io.github.miner7222.gap

internal class SuperResolutionStateRuntime(
    private val useCompatibilityLsr: () -> Boolean,
    private val shouldExposeSuperResolution: (String) -> Boolean,
) {
    fun usesCompatibilityLsr(): Boolean = useCompatibilityLsr()

    fun shouldForceSavedBooleanReadFalse(key: String?): Boolean {
        return resolveDisabledSwitchPackage(key) != null
    }

    fun coerceSavedBooleanWrite(key: String?, value: Boolean): Boolean {
        return if (resolveDisabledSwitchPackage(key) != null) false else value
    }

    fun disabledSwitchKeys(packageName: String?): List<String> {
        val normalizedPackage = packageName?.trim()
            ?.takeIf { it.isNotEmpty() && it != GAME_HELPER_PACKAGE }
            ?: return emptyList()
        if (!useCompatibilityLsr() || shouldExposeSuperResolution(normalizedPackage)) {
            return emptyList()
        }
        return SWITCH_KEY_SUFFIXES.map { suffix -> normalizedPackage + suffix }
    }

    private fun resolveDisabledSwitchPackage(key: String?): String? {
        if (!useCompatibilityLsr()) return null
        val normalizedKey = key?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val packageName = SWITCH_KEY_SUFFIXES.firstNotNullOfOrNull { suffix ->
            normalizedKey
                .takeIf { it.endsWith(suffix) }
                ?.removeSuffix(suffix)
                ?.takeIf { packageName -> packageName.isNotBlank() && packageName != GAME_HELPER_PACKAGE }
        } ?: return null
        return packageName.takeIf { !shouldExposeSuperResolution(it) }
    }

    private companion object {
        private const val GAME_HELPER_PACKAGE = "com.zui.game.service"
        private val SWITCH_KEY_SUFFIXES = listOf(
            "switchSuperResolution",
            "switchFrameInterpolation",
        )
    }
}
