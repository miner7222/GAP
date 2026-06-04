package io.github.miner7222.gap

import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings

internal class SuperResolutionHooks(
    private val superResolutionRuntime: SuperResolutionRuntime,
    private val floatingBarRuntime: FloatingBarRuntime,
    private val superResolutionStateRuntime: SuperResolutionStateRuntime,
) {
    fun install(scope: HookScope) {
        with(scope) {
            installAvailabilityHooks()
            installSupportHooks()
            installStateHooks()
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

    private fun HookScope.installStateHooks() {
        replaceMethod(
            SWITCH_STATE_CLASS,
            "getSavedBooleanState",
            Context::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
        ) {
            val key = args.getOrNull(1) as? String
            if (superResolutionStateRuntime.shouldForceSavedBooleanReadFalse(key)) {
                AndroidInternals.log("Forced disabled Game Helper SR state read for $key")
                false
            } else {
                callOriginal()
            }
        }

        beforeMethod(
            SWITCH_STATE_CLASS,
            "saveBooleanState",
            Context::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
        ) {
            val key = args.getOrNull(1) as? String ?: return@beforeMethod
            val value = args.getOrNull(2) as? Boolean ?: return@beforeMethod
            val coercedValue = superResolutionStateRuntime.coerceSavedBooleanWrite(key, value)
            if (coercedValue != value) {
                args[2] = coercedValue
                AndroidInternals.log("Forced disabled Game Helper SR state write for $key")
            }
        }

        beforeMethod(SUPER_RESOLUTION_WINDOW_MANAGER_CLASS, "onGameModeEnter", String::class.java) {
            val packageName = args.firstOrNull() as? String
            clearDisabledGameHelperState(packageName, source = "onGameModeEnter", sendUltraHdOff = true)
        }

        afterMethod(SUPER_RESOLUTION_WINDOW_MANAGER_CLASS, "onGameModeExit", String::class.java) {
            val packageName = args.firstOrNull() as? String
            clearDisabledGameHelperState(packageName, source = "onGameModeExit", sendUltraHdOff = false)
        }

        afterMethod(SUPER_RESOLUTION_WINDOW_MANAGER_CLASS, "resetState", parameterCount = 0) {
            clearDisabledGameHelperState(
                packageName = resolveCurrentGamePackage(),
                source = "resetState",
                sendUltraHdOff = true,
            )
        }
    }

    private fun HookScope.clearDisabledGameHelperState(
        packageName: String?,
        source: String,
        sendUltraHdOff: Boolean,
    ) {
        val keys = superResolutionStateRuntime.disabledSwitchKeys(packageName)
        if (keys.isEmpty()) return
        val context = resolveSuperResolutionManagerContext() ?: return
        val switchStateClass = ReflectCompat.findClass(SWITCH_STATE_CLASS, appClassLoader)

        keys.forEach { key ->
            runCatching {
                ReflectCompat.callStaticMethod(switchStateClass, "saveBooleanState", context, key, false)
            }.onFailure {
                AndroidInternals.log("Failed to clear Game Helper SR switch state for $key from $source", it)
            }
        }

        runCatching {
            val resolver = context.contentResolver
            Settings.Global.putInt(resolver, KEY_GAME_ASSISTANT_SWITCH_UPSCALEF, 0)
            Settings.Global.putInt(resolver, KEY_GAME_ASSISTANT_SWITCH_INTERPF, 0)
        }.onFailure {
            AndroidInternals.log("Failed to clear Game Helper SR global state for $packageName from $source", it)
        }

        if (sendUltraHdOff) {
            sendCompatUltraHdOff(source)
        }

        AndroidInternals.log("Cleared disabled Game Helper SR state for $packageName from $source")
    }

    private fun HookScope.sendCompatUltraHdOff(source: String) {
        if (!superResolutionStateRuntime.usesCompatibilityLsr()) return
        runCatching {
            val lsrClass = ReflectCompat.findClass(LSR_SERVICE_INTERFACE_CLASS, appClassLoader)
            val companion = ReflectCompat.getStaticObjectField(lsrClass, "Companion")
            val instance = ReflectCompat.callMethod(companion, "getInstance") ?: return
            listOf(false, true).forEach { fromFrameInterpolation ->
                val bundle = Bundle().apply {
                    putBoolean("switchOnOff", false)
                    putInt("upscaleF", 0)
                    putInt("interpF", 0)
                    putBoolean("fromFrameInterpolation", fromFrameInterpolation)
                }
                ReflectCompat.callMethod(instance, "switchOnOffGameSR", bundle)
            }
            AndroidInternals.log("Sent compat Ultra HD OFF from $source")
        }.onFailure {
            AndroidInternals.log("Failed to send compat Ultra HD OFF from $source", it)
        }
    }

    private fun HookScope.resolveCurrentGamePackage(): String? {
        return runCatching {
            ReflectCompat.getStaticObjectField(
                ReflectCompat.findClass(SUPER_RESOLUTION_WINDOW_MANAGER_CLASS, appClassLoader),
                "currentGame",
            ) as? String
        }.getOrNull()
    }

    private fun HookScope.resolveSuperResolutionManagerContext(): Context? {
        return runCatching {
            ReflectCompat.getStaticObjectField(
                ReflectCompat.findClass(SUPER_RESOLUTION_WINDOW_MANAGER_CLASS, appClassLoader),
                "context",
            ) as? Context
        }.getOrNull()
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
        private const val KEY_GAME_ASSISTANT_SWITCH_INTERPF = "key_game_assistant_switch_interpf"
        private const val KEY_GAME_ASSISTANT_SWITCH_UPSCALEF = "key_game_assistant_switch_upscalef"
        private const val LSR_SERVICE_INTERFACE_CLASS = "com.zui.game.service.util.LsrServiceInterface"
        private const val SUPER_RESOLUTION_WINDOW_MANAGER_CLASS =
            "com.zui.game.service.ui.superresolution.SuperResolutionWindowManager"
        private const val SWITCH_STATE_CLASS = "com.zui.game.service.util.SwitchStateKt"
    }
}
