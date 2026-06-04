package io.github.miner7222.gap

import android.content.Context
import android.media.AudioManager
import android.provider.Settings

internal class AiSoundRuntime(
    private val resolveGameHelperClassLoader: () -> ClassLoader?,
) {
    fun isSupportedPackage(packageName: String?): Boolean {
        return packageName != null && packageName in SUPPORTED_PACKAGES
    }

    fun resolveCallbackPackage(callback: Any?): String? {
        return runCatching {
            ReflectCompat.getObjectField(callback, "\$pkg") as? String
        }.getOrNull()
    }

    fun resolveCallbackContext(callback: Any?): Context? {
        return runCatching {
            ReflectCompat.getObjectField(callback, "\$context") as? Context
        }.getOrNull()
    }

    fun resolveCallbackItem(callback: Any?): Any? {
        return runCatching {
            ReflectCompat.getObjectField(callback, "this\$0")
        }.getOrNull()
    }

    fun readSetting(context: Context, defaultValue: Int): Int {
        val classLoader = resolveGameHelperClassLoader() ?: context.classLoader

        return runCatching {
            val utilClass = ReflectCompat.findClass("com.zui.util.SettingsValueUtilKt", classLoader)
            ReflectCompat.callStaticMethod(
                utilClass,
                "getSystemInt",
                AI_SOUND_SETTING_KEY,
                context,
                defaultValue,
            ) as? Int
        }.getOrNull() ?: runCatching {
            Settings.System.getInt(context.contentResolver, AI_SOUND_SETTING_KEY, defaultValue)
        }.getOrElse {
            AndroidInternals.log("Failed to read AI sound setting", it)
            defaultValue
        }
    }

    fun isFeatureOpened(): Boolean {
        val classLoader = resolveGameHelperClassLoader() ?: return false

        return runCatching {
            val itemClass = ReflectCompat.findClass(
                "com.zui.game.service.sys.item.ItemAISoundEnhancement",
                classLoader,
            )
            val featuresClass = ReflectCompat.findClass(
                "com.zui.game.service.FeaturesBaseOnRomKt",
                classLoader,
            )
            val companion = ReflectCompat.getStaticObjectField(itemClass, "Companion")
            val romFeatures = ReflectCompat.callStaticMethod(featuresClass, "getRomFeatures")
            ReflectCompat.callMethod(companion, "isFeatureOpened", romFeatures) as? Boolean ?: false
        }.getOrElse {
            AndroidInternals.log("Failed to evaluate AI sound feature availability", it)
            false
        }
    }

    fun applyState(item: Any?, context: Context, enabled: Boolean) {
        if (item == null) return

        val status = if (enabled) 0 else 1
        runCatching {
            ReflectCompat.callMethod(item, "setMStatus", status)
            ReflectCompat.callMethod(item, "setMNoClick", true)
            val liveData = ReflectCompat.callMethod(item, "getMStatusLive")
            ReflectCompat.callMethod(liveData, "postValue", status)
        }.onFailure {
            AndroidInternals.log("Failed to apply AI sound item state", it)
        }

        setAudioParameter(context, enabled, "Failed to update AI sound audio parameters")
    }

    fun toggle(item: Any, context: Context) {
        val enable = runCatching {
            (ReflectCompat.callMethod(item, "getMStatus") as? Int) != 0
        }.getOrElse {
            AndroidInternals.log("Failed to read current AI sound toggle state", it)
            true
        }

        runCatching {
            ReflectCompat.callMethod(item, "change2Status", if (enable) 0 else 1)
        }.onFailure {
            AndroidInternals.log("Failed to toggle AI sound state through change2Status()", it)
            applyState(item, context, enable)
        }

        setAudioParameter(context, enable, "Failed to update AI sound audio parameters during toggle")
        writeSetting(context, if (enable) 1 else 0)
    }

    private fun writeSetting(context: Context, value: Int) {
        val classLoader = resolveGameHelperClassLoader() ?: context.classLoader
        val stored = runCatching {
            val utilClass = ReflectCompat.findClass("com.zui.util.SettingsValueUtilKt", classLoader)
            ReflectCompat.callStaticMethod(
                utilClass,
                "setSystemInt",
                AI_SOUND_SETTING_KEY,
                context,
                value,
            )
            true
        }.getOrElse {
            AndroidInternals.log("Falling back to Settings.System for AI sound state", it)
            false
        }

        if (!stored) {
            runCatching {
                Settings.System.putInt(context.contentResolver, AI_SOUND_SETTING_KEY, value)
            }.onFailure {
                AndroidInternals.log("Failed to write AI sound setting", it)
            }
        }
    }

    private fun setAudioParameter(context: Context, enabled: Boolean, failureMessage: String) {
        runCatching {
            (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.setParameters(
                if (enabled) AI_SOUND_PARAMETER_ENABLED else AI_SOUND_PARAMETER_DISABLED,
            )
        }.onFailure {
            AndroidInternals.log(failureMessage, it)
        }
    }

    private companion object {
        private const val AI_SOUND_SETTING_KEY = "key_game_aisound"
        private const val AI_SOUND_PARAMETER_ENABLED = "aisound=true"
        private const val AI_SOUND_PARAMETER_DISABLED = "aisound=false"
        private val SUPPORTED_PACKAGES = GamePackageGroups.PUBG_VARIANT_PACKAGES
    }
}
