package io.github.miner7222.gap

import android.content.res.Resources
import android.os.Handler
import android.os.Looper

internal class SuperResolutionHooks(
    private val superResolutionRuntime: SuperResolutionRuntime,
    private val floatingBarRuntime: FloatingBarRuntime,
) {
    fun install(scope: HookScope) {
        with(scope) {
            installAvailabilityHooks()
            installSupportHooks()
        }
    }

    private fun HookScope.installAvailabilityHooks() {
        replaceMethodWithTrue("com.zui.game.service.di.Settings", "getSupportSuperResolution")

        installResourceHooks()

        // Backstop the rendered list so stale SR buttons cannot survive after
        // the runtime whitelist changes.
        afterMethod(
            "com.zui.game.service.ui.GameHelperViewController\$getCurrentView\$1\$1",
            "emit",
            parameterCount = 2,
        ) {
            val controller = runCatching {
                ReflectCompat.getObjectField(instanceOrNull, "this\$0")
            }.getOrNull()
            floatingBarRuntime.normalizeItems(controller, "getCurrentView collector")
            // If the LiveData was empty (list not populated yet), schedule a
            // retry after the current message finishes so the original code
            // has a chance to fill the list first.
            Handler(Looper.getMainLooper()).post {
                floatingBarRuntime.normalizeItems(controller, "getCurrentView collector (deferred)")
            }
        }
    }

    private fun HookScope.installResourceHooks() {
        replaceMethod("android.content.res.Resources", "getStringArray", Int::class.javaPrimitiveType!!) {
            val resources = instanceOrNull as? Resources ?: return@replaceMethod callOriginal()
            val resId = args.firstOrNull() as? Int ?: return@replaceMethod callOriginal()
            val entryName = runCatching { resources.getResourceEntryName(resId) }.getOrNull()
            val packageName = runCatching { resources.getResourcePackageName(resId) }.getOrNull()

            if (entryName != GAME_RESOLUTION_APPS_ARRAY || packageName != GAME_HELPER_PACKAGE) {
                return@replaceMethod callOriginal()
            }

            val originalEntries = runCatching {
                (callOriginal() as? Array<*>)
                    ?.mapNotNull { it as? String }
                    ?.toTypedArray()
            }.getOrElse {
                AndroidInternals.log("Failed to read stock $GAME_RESOLUTION_APPS_ARRAY entries", it)
                null
            } ?: return@replaceMethod emptyArray<String>()

            val overriddenEntries = superResolutionRuntime.resolveArrayEntries(originalEntries)
            if (overriddenEntries != null) {
                AndroidInternals.log(
                    "Replaced $GAME_RESOLUTION_APPS_ARRAY with ${overriddenEntries.size} runtime entries",
                )
                overriddenEntries
            } else {
                originalEntries
            }
        }
    }

    private fun HookScope.installSupportHooks() {
        hookSupportMethod(
            "com.zui.ugame.gamesetting.data.RepositoryImpl",
            "querySuperResolutionSupportPackage",
        )
        hookSupportMethod(
            "com.zui.ugame.gamesetting.data.source.PreDownloadSourceImpl",
            "querySuperResolutionSupportPackage",
        )
        hookSupportMethod(
            "com.zui.game.service.util.ConstValueKt",
            "getSuperResolutionSupportPackages",
        )
        hookSupportMethod(
            "com.zui.game.service.util.ConstValueKt",
            "getSuperResolutionSupportPackagesForAll",
        )
    }

    private fun HookScope.hookSupportMethod(className: String, methodName: String) {
        replaceMethod(className, methodName) {
            superResolutionRuntime.resolveRegisteredPackagesOrOriginal("$className#$methodName") {
                callOriginal()
            }
        }
    }

    private companion object {
        private const val GAME_HELPER_PACKAGE = "com.zui.game.service"
        private const val GAME_RESOLUTION_APPS_ARRAY = "game_resolution_apps"
    }
}
