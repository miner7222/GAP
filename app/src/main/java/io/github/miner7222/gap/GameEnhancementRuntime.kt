package io.github.miner7222.gap

import android.content.Context
import java.util.LinkedHashSet

internal class GameEnhancementRuntime(
    private val resolveGameHelperClassLoader: () -> ClassLoader?,
) {
    fun mergeWideVisionKnownGames(viewModel: Any?) {
        if (viewModel == null) return

        val knownGames = readStringArrayField(viewModel, "knownGames")
        val rowKnownGames = readStringArrayField(viewModel, "rowKnownGames")
        val merged = mergeStringArrays(knownGames, rowKnownGames)
        if (merged.isEmpty()) return

        runCatching {
            ReflectCompat.setObjectField(viewModel, "knownGames", merged)
            ReflectCompat.setObjectField(viewModel, "rowKnownGames", merged)
        }.onFailure {
            AndroidInternals.log("Failed to merge Wide Vision knownGames lists", it)
        }
    }

    fun mergeVibrationSupportKeys(
        context: Context,
        packageName: String?,
        original: List<Any?>,
    ): List<String> {
        val merged = LinkedHashSet<String>()
        original.mapNotNullTo(merged) { it as? String }

        val normalizedPackage = packageName?.takeIf { it.isNotBlank() } ?: return merged.toList()
        resolveVibrationResourceMatches(context, normalizedPackage).forEach { merged += it }
        if (normalizedPackage in GamePackageGroups.PUBG_VARIANT_PACKAGES) {
            merged += PUBG_VIBRATION_SHARED_KEY
        }

        return merged.toList()
    }

    private fun resolveVibrationResourceMatches(context: Context, packageName: String): Set<String> {
        val merged = LinkedHashSet<String>()
        val resources = context.resources

        resolveVibrationSupportArrayIds(context).forEach { resId ->
            runCatching { resources.getStringArray(resId) }
                .onFailure { AndroidInternals.log("Failed to read vibration support array $resId", it) }
                .getOrNull()
                ?.forEach { candidate ->
                    if (packageName.contains(candidate)) {
                        merged += candidate
                    }
                }
        }

        return merged
    }

    private fun resolveVibrationSupportArrayIds(context: Context): Set<Int> {
        val classLoader = resolveGameHelperClassLoader() ?: context.classLoader

        return runCatching {
            val featuresClass = ReflectCompat.findClass(
                "com.zui.game.service.FeaturesBaseOnRomKt",
                classLoader,
            )
            val romFeatures = ReflectCompat.callStaticMethod(featuresClass, "getRomFeatures")
            linkedSetOf(
                ReflectCompat.callMethod(romFeatures, "getSupportVibrationGameListArrayId") as? Int,
                ReflectCompat.callMethod(romFeatures, "getSupportVibrationGameListArrayIdRow") as? Int,
            ).filterNotNull().toSet()
        }.getOrElse {
            AndroidInternals.log("Failed to resolve vibration support array ids", it)
            emptySet()
        }
    }

    private fun readStringArrayField(target: Any, fieldName: String): Array<String> {
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            (ReflectCompat.getObjectField(target, fieldName) as? Array<*>)
                ?.mapNotNull { it as? String }
                ?.toTypedArray()
                ?: emptyArray()
        }.getOrElse {
            AndroidInternals.log("Failed to read $fieldName from ${target.javaClass.name}", it)
            emptyArray()
        }
    }

    private fun mergeStringArrays(vararg arrays: Array<String>): Array<String> {
        val merged = LinkedHashSet<String>()
        arrays.forEach { array ->
            array.forEach { value ->
                if (value.isNotBlank()) {
                    merged += value
                }
            }
        }
        return merged.toTypedArray()
    }

    private companion object {
        private const val PUBG_VIBRATION_SHARED_KEY = "game.pubg.mobile"
    }
}
