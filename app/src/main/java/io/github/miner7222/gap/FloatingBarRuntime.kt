package io.github.miner7222.gap

import android.content.Context

internal class FloatingBarRuntime(
    private val resolveGameHelperClassLoader: () -> ClassLoader?,
    private val isBaldurBoard: () -> Boolean,
    private val shouldExposeSuperResolution: (String) -> Boolean,
) {
    fun normalizeItems(controller: Any?, source: String) {
        val packageName = resolveControllerPackageName(controller).orEmpty()
        val currentItems = resolveFloatingFeatureItems(controller)
        if (currentItems.isEmpty()) return

        val normalized = ArrayList<Any>(currentItems.size)
        currentItems.forEach { item ->
            val key = resolveItemKey(item)
            when {
                key == COLORFUL_LIGHT_FEATURE_KEY && !isBaldurBoard() -> return@forEach
                key == SUPER_RESOLUTION_FEATURE_KEY && !shouldExposeSuperResolution(packageName) -> return@forEach
                key == FOUR_D_VIBRATE_FEATURE_KEY &&
                    !shouldExposeFourDVibration(controller, packageName) -> return@forEach
            }
            normalized += item
        }

        if (
            shouldExposeFourDVibration(controller, packageName) &&
            normalized.none { resolveItemKey(it) == FOUR_D_VIBRATE_FEATURE_KEY }
        ) {
            resolveFourDVibrationItem(controller)?.let { item ->
                insertFloatingItemByFeatureOrder(normalized, item, resolveCurrentRomFeatureOrder())
                AndroidInternals.log("Reinserted 4D vibration button for $packageName from $source")
            }
        }

        if (!hasSameKeys(currentItems, normalized)) {
            publishFloatingFeatureItems(controller, normalized)
        }
    }

    private fun resolveControllerPackageName(lambdaInstance: Any?): String? {
        // Walk the this$0 chain from nested lambdas up to GameHelperViewController,
        // checking pkgName at each level because callers may already pass the controller.
        return runCatching {
            var current: Any? = lambdaInstance
            for (depth in 0 until 6) {
                val pkgName = runCatching {
                    ReflectCompat.getObjectField(current, "pkgName") as? String
                }.getOrNull()
                if (pkgName != null) return@runCatching pkgName
                current = ReflectCompat.getObjectField(current, "this\$0")
            }
            null
        }.getOrElse {
            AndroidInternals.log("Failed to resolve GameHelperViewController pkgName", it)
            null
        }
    }

    private fun resolveFloatingFeatureItems(controller: Any?): List<Any> {
        val rootView = resolveFloatingRootView(controller) ?: return emptyList()
        val liveData = runCatching {
            ReflectCompat.callMethod(rootView, "getMFeatureListItems")
        }.getOrNull() ?: return emptyList()

        @Suppress("UNCHECKED_CAST")
        return runCatching {
            (ReflectCompat.callMethod(liveData, "getValue") as? List<Any?>)
                ?.filterNotNull()
                ?: emptyList()
        }.getOrElse {
            AndroidInternals.log("Failed to read floating feature list", it)
            emptyList()
        }
    }

    private fun resolveFloatingRootView(controller: Any?): Any? {
        return runCatching {
            ReflectCompat.getObjectField(controller, "mPortraitRootView")
        }.getOrNull() ?: runCatching {
            ReflectCompat.getObjectField(controller, "mLandscapeRootView")
        }.getOrNull()
    }

    private fun publishFloatingFeatureItems(controller: Any?, items: List<Any>) {
        val portraitChunkSize = resolvePortraitChunkSize()
        postFloatingItemsToRoot(
            rootView = runCatching { ReflectCompat.getObjectField(controller, "mPortraitRootView") }.getOrNull(),
            featureItems = items,
            buttonChunkSize = portraitChunkSize,
        )
        postFloatingItemsToRoot(
            rootView = runCatching { ReflectCompat.getObjectField(controller, "mLandscapeRootView") }.getOrNull(),
            featureItems = items,
            buttonChunkSize = LANDSCAPE_BUTTON_CHUNK_SIZE,
        )
    }

    private fun postFloatingItemsToRoot(rootView: Any?, featureItems: List<Any>, buttonChunkSize: Int) {
        if (rootView == null) return

        runCatching {
            val featureLiveData = ReflectCompat.callMethod(rootView, "getMFeatureListItems")
            ReflectCompat.callMethod(featureLiveData, "postValue", featureItems)
        }.onFailure {
            AndroidInternals.log("Failed to publish floating feature items", it)
        }

        runCatching {
            val buttonLiveData = ReflectCompat.callMethod(rootView, "getMButtonItemsPortrait")
            ReflectCompat.callMethod(buttonLiveData, "postValue", featureItems.chunked(buttonChunkSize))
        }.onFailure {
            AndroidInternals.log("Failed to publish floating button items", it)
        }
    }

    private fun resolvePortraitChunkSize(): Int {
        return runCatching {
            val repositoryClass = ReflectCompat.findClass(
                "com.zui.game.service.data.Repository",
                resolveGameHelperClassLoader(),
            )
            val companion = ReflectCompat.getStaticObjectField(repositoryClass, "Companion")
            ReflectCompat.callMethod(companion, "getMAX_DISPLAY_COUNT_PORTRAIT") as? Int
        }.getOrNull() ?: DEFAULT_PORTRAIT_BUTTON_CHUNK_SIZE
    }

    private fun resolveItemKey(item: Any?): String? {
        return runCatching { ReflectCompat.callMethod(item, "getKey") as? String }.getOrNull()
    }

    private fun insertFloatingItemByFeatureOrder(
        items: MutableList<Any>,
        item: Any,
        featureOrder: List<String>,
    ) {
        val itemKey = resolveItemKey(item) ?: run {
            items += item
            return
        }
        val targetOrder = featureOrder.indexOf(itemKey)
        if (targetOrder < 0) {
            items += item
            return
        }

        val insertIndex = items.indexOfFirst { existing ->
            val existingOrder = featureOrder.indexOf(resolveItemKey(existing))
            existingOrder > targetOrder
        }.takeIf { it >= 0 } ?: items.size

        items.add(insertIndex, item)
    }

    private fun shouldExposeFourDVibration(controller: Any?, packageName: String): Boolean {
        if (packageName.isBlank()) return false

        val support = runCatching {
            val classLoader = resolveGameHelperClassLoader() ?: return false
            val context = runCatching {
                ReflectCompat.callMethod(controller, "getContext") as? Context
            }.getOrNull() ?: return false
            val vibrationToolClass = ReflectCompat.findClass(
                "com.zui.game.service.vibrate.VibrationToolKt",
                classLoader,
            )
            @Suppress("UNCHECKED_CAST")
            ReflectCompat.callStaticMethod(
                vibrationToolClass,
                "isGameSupport4dVibration",
                context,
                packageName,
            ) as? List<Any?>
        }.getOrElse {
            AndroidInternals.log("Failed to resolve 4D vibration support for $packageName", it)
            null
        }

        return !support.isNullOrEmpty()
    }

    private fun resolveFourDVibrationItem(controller: Any?): Any? {
        return runCatching {
            ReflectCompat.callMethod(controller, "getMItem4DVibrate")
        }.getOrElse {
            AndroidInternals.log("Failed to resolve Item4DVibrate instance", it)
            null
        }
    }

    private fun resolveCurrentRomFeatureOrder(): List<String> {
        return runCatching {
            val classLoader = resolveGameHelperClassLoader() ?: return emptyList()
            val featuresClass = ReflectCompat.findClass(
                "com.zui.game.service.FeaturesBaseOnRomKt",
                classLoader,
            )
            val romFeatures = ReflectCompat.callStaticMethod(featuresClass, "getRomFeatures")
            @Suppress("UNCHECKED_CAST")
            (ReflectCompat.getObjectField(romFeatures, "keyList") as? List<Any?>)
                ?.mapNotNull { featureKey ->
                    runCatching { ReflectCompat.callMethod(featureKey, "getKey") as? String }.getOrNull()
                }
                ?: emptyList()
        }.getOrElse {
            AndroidInternals.log("Failed to resolve current RomFeatures key order", it)
            emptyList()
        }
    }

    private fun hasSameKeys(before: List<Any>, after: List<Any>): Boolean {
        if (before.size != after.size) return false
        return before.map(::resolveItemKey) == after.map(::resolveItemKey)
    }

    private companion object {
        private const val SUPER_RESOLUTION_FEATURE_KEY = "key_super_resolution"
        private const val FOUR_D_VIBRATE_FEATURE_KEY = "key_4d_vibrate"
        private const val COLORFUL_LIGHT_FEATURE_KEY = "key_colorful_light"
        private const val DEFAULT_PORTRAIT_BUTTON_CHUNK_SIZE = 10
        private const val LANDSCAPE_BUTTON_CHUNK_SIZE = 6
    }
}
