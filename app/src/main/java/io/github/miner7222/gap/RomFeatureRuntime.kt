package io.github.miner7222.gap

import java.lang.reflect.Modifier
import java.util.LinkedHashSet

internal class RomFeatureRuntime(
    private val resolveGameHelperClassLoader: () -> ClassLoader?,
    private val isBaldurBoard: () -> Boolean,
) {
    fun resolveFeatureOpen(featureKey: String?, callOriginal: () -> Any?): Any? {
        return when (featureKey) {
            SUPER_RESOLUTION_FEATURE_KEY -> true
            FOUR_D_VIBRATE_FEATURE_KEY -> true
            COLORFUL_LIGHT_FEATURE_KEY -> isBaldurBoard()
            else -> callOriginal()
        }
    }

    fun resolveKeyContainerFeatureOpen(container: Any?, callOriginal: () -> Any?): Any? {
        return resolveFeatureOpen(resolveKeyContainerFeatureKey(container), callOriginal)
    }

    fun normalizeFeatureKeys(keys: Collection<String>): Array<String> {
        val normalized = LinkedHashSet<String>()

        keys.forEach { key ->
            if (key == COLORFUL_LIGHT_FEATURE_KEY && !isBaldurBoard()) return@forEach
            normalized += key
        }

        return normalized.toTypedArray()
    }

    fun patchExistingRomFeatureSets() {
        val classLoader = resolveGameHelperClassLoader() ?: return
        runCatching {
            val featuresClass = ReflectCompat.findClass("com.zui.game.service.FeaturesBaseOnRomKt", classLoader)
            var patchedCount = 0

            featuresClass.declaredFields
                .filter { Modifier.isStatic(it.modifiers) && it.type.name == "com.zui.game.service.RomFeatures" }
                .forEach { field ->
                    field.isAccessible = true
                    patchRomFeatureKeyList(field.get(null))
                    patchedCount += 1
                }

            AndroidInternals.log("Patched $patchedCount RomFeatures key lists")
        }.onFailure {
            AndroidInternals.log("Failed to patch existing RomFeatures key lists", it)
        }
    }

    fun patchRomFeatureKeyList(romFeatures: Any?) {
        val keyList = readRomFeatureKeyList(romFeatures) ?: return

        val normalized = LinkedHashMap<String, Any>()
        keyList.forEach { featureKey ->
            if (featureKey == null) return@forEach
            val key = runCatching { ReflectCompat.callMethod(featureKey, "getKey") as? String }.getOrNull()
                ?: return@forEach
            if (key == COLORFUL_LIGHT_FEATURE_KEY && !isBaldurBoard()) return@forEach
            normalized[key] = featureKey
        }

        if (!normalized.containsKey(SUPER_RESOLUTION_FEATURE_KEY)) {
            createFeatureKeys(arrayOf(SUPER_RESOLUTION_FEATURE_KEY)).firstOrNull()?.let { featureKey ->
                insertFeatureKey(
                    normalized = normalized,
                    key = SUPER_RESOLUTION_FEATURE_KEY,
                    featureKey = featureKey,
                    beforeKeys = listOf(LIVE_PICTURE_FEATURE_KEY, COLORFUL_LIGHT_FEATURE_KEY),
                )
            }
        }

        if (!normalized.containsKey(FOUR_D_VIBRATE_FEATURE_KEY)) {
            createFeatureKeys(arrayOf(FOUR_D_VIBRATE_FEATURE_KEY)).firstOrNull()?.let { featureKey ->
                insertFeatureKey(
                    normalized = normalized,
                    key = FOUR_D_VIBRATE_FEATURE_KEY,
                    featureKey = featureKey,
                    beforeKeys = listOf(WIDE_VISION_FEATURE_KEY, COLORFUL_LIGHT_FEATURE_KEY),
                    afterKeys = listOf(LIVE_PICTURE_FEATURE_KEY),
                )
            }
        }

        runCatching {
            ReflectCompat.setObjectField(romFeatures, "keyList", ArrayList(normalized.values))
        }.onFailure {
            AndroidInternals.log("Failed to overwrite RomFeatures key list", it)
        }
    }

    fun readRomFeatureKeyList(romFeatures: Any?): List<Any?>? {
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            ReflectCompat.getObjectField(romFeatures, "keyList") as? List<Any?>
        }.getOrNull()
    }

    fun normalizeGameSettingFeatureList(features: List<Any?>): List<Any?> {
        if (isBaldurBoard()) return features
        return features.filterNot {
            resolveGameSettingFeatureKey(it) == GAME_HELPER_COLORFUL_LIGHT_PREFERENCE_KEY
        }
    }

    fun removeColorfulLightPreference(fragment: Any?) {
        if (isBaldurBoard()) return

        runCatching {
            ReflectCompat.callMethod(
                fragment,
                "tryRemovePreference",
                GAME_HELPER_COLORFUL_LIGHT_PREFERENCE_KEY,
            )
            AndroidInternals.log("Removed colorful light preference from Game Helper settings")
        }.onFailure {
            AndroidInternals.log("Failed to remove colorful light preference", it)
        }
    }

    private fun resolveKeyContainerFeatureKey(container: Any?): String? {
        return runCatching { ReflectCompat.callMethod(container, "getKEY") as? String }.getOrNull()
    }

    private fun resolveGameSettingFeatureKey(feature: Any?): String? {
        return runCatching { ReflectCompat.callMethod(feature, "getKey") as? String }.getOrNull()
    }

    private fun insertFeatureKey(
        normalized: LinkedHashMap<String, Any>,
        key: String,
        featureKey: Any,
        beforeKeys: List<String> = emptyList(),
        afterKeys: List<String> = emptyList(),
    ) {
        if (normalized.containsKey(key)) return

        val entries = normalized.entries.map { it.key to it.value }.toMutableList()
        val beforeIndex = beforeKeys
            .map { anchor -> entries.indexOfFirst { it.first == anchor } }
            .filter { it >= 0 }
            .minOrNull()

        val afterIndex = afterKeys
            .map { anchor -> entries.indexOfFirst { it.first == anchor } }
            .filter { it >= 0 }
            .maxOrNull()

        val insertIndex = beforeIndex ?: afterIndex?.plus(1) ?: entries.size
        entries.add(insertIndex, key to featureKey)

        normalized.clear()
        entries.forEach { (entryKey, entryValue) ->
            normalized[entryKey] = entryValue
        }
    }

    private fun createFeatureKeys(keys: Array<String>): List<Any> {
        val classLoader = resolveGameHelperClassLoader() ?: return emptyList()
        return runCatching {
            val featureKeyClass = ReflectCompat.findClass("com.zui.game.service.FeatureKey", classLoader)
            val companion = ReflectCompat.getStaticObjectField(featureKeyClass, "Companion")
            @Suppress("UNCHECKED_CAST")
            ReflectCompat.callMethod(companion, "createByKeys", keys) as? List<Any> ?: emptyList()
        }.getOrElse {
            AndroidInternals.log("Failed to create FeatureKey instances", it)
            emptyList()
        }
    }

    private companion object {
        private const val SUPER_RESOLUTION_FEATURE_KEY = "key_super_resolution"
        private const val FOUR_D_VIBRATE_FEATURE_KEY = "key_4d_vibrate"
        private const val WIDE_VISION_FEATURE_KEY = "key_wide_vision"
        private const val LIVE_PICTURE_FEATURE_KEY = "key_live_picture"
        private const val COLORFUL_LIGHT_FEATURE_KEY = "key_colorful_light"
        private const val GAME_HELPER_COLORFUL_LIGHT_PREFERENCE_KEY = "option_item_colorful_light"
    }
}
