package io.github.miner7222.gap

import android.content.Context

internal class AiSoundHooks(
    private val aiSoundRuntime: AiSoundRuntime,
) {
    fun install(scope: HookScope) {
        with(scope) {
            hookAiSoundItemInitialization()
            hookAiSoundToggleHandler()
            hookAiSoundFloatingNotice()
        }
    }

    private fun HookScope.hookAiSoundItemInitialization() {
        val className = "com.zui.game.service.sys.item.ItemAISoundEnhancement"
        val methodName = "initFromSavedState"
        if (!hasMethodWithParamCount(className, methodName, 2)) {
            AndroidInternals.log("Skip missing $className#$methodName/2 in Game Helper")
            return
        }

        afterMethod(className, methodName, parameterCount = 2) {
            val context = args.firstOrNull() as? Context ?: return@afterMethod
            val packageName = args.getOrNull(1) as? String ?: return@afterMethod
            if (!aiSoundRuntime.isSupportedPackage(packageName)) return@afterMethod

            val enabled = aiSoundRuntime.readSetting(context, defaultValue = 1) == 1
            aiSoundRuntime.applyState(instanceOrNull, context, enabled)
            result = if (enabled) 0 else 1
        }
    }

    private fun HookScope.hookAiSoundToggleHandler() {
        val className = "com.zui.game.service.sys.item.ItemAISoundEnhancement\$initFromSavedState\$1"
        if (!hasMethodWithParamCount(className, "onNoClick", 0)) {
            AndroidInternals.log("Skip missing $className#onNoClick/0 in Game Helper")
            return
        }

        replaceMethod(className, "onNoClick") {
            val packageName = aiSoundRuntime.resolveCallbackPackage(instanceOrNull)
                ?: return@replaceMethod callOriginal()
            if (!aiSoundRuntime.isSupportedPackage(packageName)) {
                return@replaceMethod callOriginal()
            }

            val context = aiSoundRuntime.resolveCallbackContext(instanceOrNull)
                ?: return@replaceMethod callOriginal()
            val item = aiSoundRuntime.resolveCallbackItem(instanceOrNull)
                ?: return@replaceMethod callOriginal()
            aiSoundRuntime.toggle(item, context)
            null
        }

        replaceMethod(className, "onToast") {
            val packageName = aiSoundRuntime.resolveCallbackPackage(instanceOrNull)
            if (packageName != null && aiSoundRuntime.isSupportedPackage(packageName)) {
                return@replaceMethod null
            }
            callOriginal()
        }
    }

    private fun HookScope.hookAiSoundFloatingNotice() {
        val className = "com.zui.game.service.ui.FloatingGameNoticController"
        val methodName = "checkAISoundEnhancementEnable"
        if (!hasMethodWithParamCount(className, methodName, 1)) {
            AndroidInternals.log("Skip missing $className#$methodName/1 in Game Helper")
            return
        }

        replaceMethod(className, methodName, parameterCount = 1) {
            val packageName = args.firstOrNull() as? String ?: return@replaceMethod callOriginal()
            if (!aiSoundRuntime.isSupportedPackage(packageName)) {
                return@replaceMethod callOriginal()
            }

            val context = runCatching {
                ReflectCompat.getObjectField(instanceOrNull, "mContext") as? Context
            }.getOrNull() ?: return@replaceMethod callOriginal()

            aiSoundRuntime.readSetting(context, defaultValue = 1) == 1 && aiSoundRuntime.isFeatureOpened()
        }
    }
}
