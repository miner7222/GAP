package io.github.miner7222.gap

import android.content.Context

internal class GameEnhancementHooks(
    private val gameEnhancementRuntime: GameEnhancementRuntime,
) {
    fun install(scope: HookScope) {
        with(scope) {
            installWideVisionHooks()
            installVibrationSupportHooks()
        }
    }

    private fun HookScope.installWideVisionHooks() {
        val className = "com.zui.ugame.gamesetting.ui.options.content.widevision.MoreGameViewModel"
        val methodName = "<init>"
        if (!hasMethodWithParamCount(className, methodName, 2)) {
            AndroidInternals.log("Skip missing $className#$methodName/2 in Game Helper")
            return
        }

        afterMethod(className, methodName, parameterCount = 2) {
            gameEnhancementRuntime.mergeWideVisionKnownGames(instanceOrNull)
        }
    }

    private fun HookScope.installVibrationSupportHooks() {
        val className = "com.zui.game.service.vibrate.VibrationToolKt"
        val methodName = "isGameSupport4dVibration"
        if (!hasMethodWithParamCount(className, methodName, 2)) {
            AndroidInternals.log("Skip missing $className#$methodName/2 in Game Helper")
            return
        }

        replaceMethod(className, methodName, parameterCount = 2) {
            val context = args.firstOrNull() as? Context
            val packageName = args.getOrNull(1) as? String
            val original = runCatching {
                @Suppress("UNCHECKED_CAST")
                (callOriginal() as? List<Any?>) ?: emptyList()
            }.getOrElse {
                AndroidInternals.log("Failed to call original $className#$methodName", it)
                emptyList()
            }

            if (context == null) {
                return@replaceMethod original.mapNotNull { it as? String }
            }

            gameEnhancementRuntime.mergeVibrationSupportKeys(context, packageName, original)
        }
    }
}
