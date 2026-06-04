package io.github.miner7222.gap

import android.content.Context

internal class RomFeatureHooks(
    private val romFeatureRuntime: RomFeatureRuntime,
    private val isBaldurBoard: () -> Boolean,
) {
    fun install(scope: HookScope) {
        with(scope) {
            installRomFeatureHooks()
            installGameSettingFeatureHooks()
        }
    }

    private fun HookScope.installRomFeatureHooks() {
        replaceMethod("com.zui.game.service.RomFeatures", "isFeatureOpen", String::class.java) {
            romFeatureRuntime.resolveFeatureOpen(args.firstOrNull() as? String) {
                callOriginal()
            }
        }

        afterMethod("com.zui.game.service.RomFeatures", "getKeyList") {
            romFeatureRuntime.patchRomFeatureKeyList(instanceOrNull)
            result = romFeatureRuntime.readRomFeatureKeyList(instanceOrNull) ?: result
        }

        replaceMethod(
            "com.zui.game.service.sys.item.KeyContainer",
            "isFeatureOpened",
            parameterCount = 1,
        ) {
            romFeatureRuntime.resolveKeyContainerFeatureOpen(instanceOrNull) {
                callOriginal()
            }
        }

        beforeMethod(
            "com.zui.game.service.FeatureKey\$Companion",
            "createByKeys",
            Array<String>::class.java,
        ) {
            val keys = (args.firstOrNull() as? Array<*>)?.mapNotNull { it as? String } ?: return@beforeMethod
            val normalized = romFeatureRuntime.normalizeFeatureKeys(keys)
            if (keys.size != normalized.size || keys.toList() != normalized.toList()) {
                AndroidInternals.log("Normalized FeatureKey list from ${keys.size} to ${normalized.size}")
            }
            args[0] = normalized
        }

        romFeatureRuntime.patchExistingRomFeatureSets()
    }

    private fun HookScope.installGameSettingFeatureHooks() {
        replaceMethod("com.zui.ugame.gamesetting.feature.FeatureList", "list") {
            val original = runCatching {
                @Suppress("UNCHECKED_CAST")
                callOriginal() as? List<Any?>
            }.getOrNull() ?: emptyList()
            romFeatureRuntime.normalizeGameSettingFeatureList(original)
        }

        // The settings screen also keeps a static XML entry, so remove it after inflation.
        afterMethod(
            "com.zui.ugame.gamesetting.ui.options.SaverGameSettingsExtension",
            "onCreatePreferences",
            parameterCount = 2,
        ) {
            romFeatureRuntime.removeColorfulLightPreference(instanceOrNull)
        }
        afterMethod("com.zui.ugame.gamesetting.ui.options.SaverGameSettingsExtension", "onResume") {
            romFeatureRuntime.removeColorfulLightPreference(instanceOrNull)
        }

        // Backstop the ViewModel cache in case it was built before FeatureList.list() was filtered.
        replaceMethod(
            "com.zui.ugame.gamesetting.ui.options.SaverGameSettingsExtensionViewModel",
            "getFeatureList",
        ) {
            val original = runCatching {
                @Suppress("UNCHECKED_CAST")
                callOriginal() as? List<Any?>
            }.getOrNull() ?: emptyList()
            romFeatureRuntime.normalizeGameSettingFeatureList(original)
        }

        replaceMethodWithTrue(
            "com.zui.ugame.gamesetting.feature.FEATURE_SUPER_RESOLUTION",
            "isEnable",
            Context::class.java,
        )

        replaceMethod("com.zui.ugame.gamesetting.feature.FEATURE_COLORFUL_LIGHT", "isEnable", Context::class.java) {
            isBaldurBoard()
        }
        replaceMethod(
            "com.zui.ugame.gamesetting.feature.FEATURE_COLORFUL_LIGHT",
            "onPreferenceTreeClick",
            parameterCount = 3,
        ) {
            if (isBaldurBoard()) callOriginal() else false
        }
    }
}
