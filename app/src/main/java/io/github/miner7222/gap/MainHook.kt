package io.github.miner7222.gap

import android.content.Context
import android.content.res.Resources
import android.media.AudioManager
import android.os.IBinder
import android.provider.Settings
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.param.HookParam
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import com.zui.server.lsr.LsrService
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.lang.reflect.Modifier
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean

@InjectYukiHookWithXposed
class MainHook : IYukiHookXposedInit {

    private companion object {
        private const val GAME_HELPER_PACKAGE = "com.zui.game.service"
        private const val SUPER_RESOLUTION_FEATURE_KEY = "key_super_resolution"
        private const val FOUR_D_VIBRATE_FEATURE_KEY = "key_4d_vibrate"
        private const val WIDE_VISION_FEATURE_KEY = "key_wide_vision"
        private const val LIVE_PICTURE_FEATURE_KEY = "key_live_picture"
        private const val AI_SOUND_SETTING_KEY = "key_game_aisound"
        private const val AI_SOUND_PARAMETER_ENABLED = "aisound=true"
        private const val AI_SOUND_PARAMETER_DISABLED = "aisound=false"
        private const val COLORFUL_LIGHT_FEATURE_KEY = "key_colorful_light"
        private const val GAME_RESOLUTION_APPS_ARRAY = "game_resolution_apps"
        private const val DEFAULT_SUPER_RESOLUTION_ARRAY_FLAGS = "#1#1"
        private const val GAME_HELPER_COLORFUL_LIGHT_PREFERENCE_KEY = "option_item_colorful_light"
        private const val PUBG_VIBRATION_SHARED_KEY = "game.pubg.mobile"
        private val PUBG_VARIANT_PACKAGES = linkedSetOf(
            "com.tencent.ig",
            "com.tencent.tmgp.pubgmhd",
            "com.rekoo.pubgm",
            "com.pubg.krmobile",
            "com.vng.pubgmobile",
            "com.pubg.imobile",
        )
        private val AI_SOUND_SUPPORTED_PACKAGES = LinkedHashSet(PUBG_VARIANT_PACKAGES)
    }

    private val systemHooksInstalled = AtomicBoolean(false)
    private val gameHooksInstalled = AtomicBoolean(false)
    @Volatile
    private var systemContext: Context? = null
    @Volatile
    private var fallbackLsrBinder: IBinder? = null
    @Volatile
    private var cachedGameHelperClassLoader: ClassLoader? = null
    @Volatile
    private var cachedSupportedPackages: Set<String>? = null
    @Volatile
    private var cachedSupportedPackagesSignature: String? = null

    override fun onInit() = configs {
        // Keep YukiHook's internal debug logger disabled to reduce early-boot
        // PackageManager churn while system_server hooks are coming online.
        isDebug = false
    }

    override fun onHook() = encase {
        loadSystem {
            applySystemHooks()
        }

        loadApp(GAME_HELPER_PACKAGE) {
            applyGameHelperHooks()
        }
    }

    private fun PackageParam.applySystemHooks() {
        if (!systemHooksInstalled.compareAndSet(false, true)) return

        if (AndroidInternals.useCompatibilityLsr()) {
            // NOTE: Do NOT install ServiceManager.getService fallback in system_server.
            // It would intercept the getService() check inside LsrServiceRegistry.ensureRegistered(),
            // making it think the service is already registered and preventing actual registration.

            // Register the compatibility Binder service early in system_server
            // so Game Helper can bind to lenovosr on non-Baldur devices.
            findClass("com.android.server.SystemServer").hook {
                injectMember {
                    method {
                        name = "startBootstrapServices"
                        paramCount = 1
                    }
                    afterHook {
                        ensureLsrRegistered()
                    }
                }
                injectMember {
                    method {
                        name = "startCoreServices"
                        paramCount = 1
                    }
                    afterHook {
                        ensureLsrRegistered()
                    }
                }
                injectMember {
                    method {
                        name = "startOtherServices"
                        paramCount = 1
                    }
                    afterHook {
                        ensureLsrRegistered()
                    }
                }
            }
        } else {
            AndroidInternals.log("Skipping compatibility lenovosr bootstrap hooks on baldur")
        }

        AndroidInternals.log("Installed YukiHook system_server hooks")
    }

    private fun PackageParam.applyGameHelperHooks() {
        if (!gameHooksInstalled.compareAndSet(false, true)) return

        // Cache the Game Helper classloader for helpers that run after the
        // initial package hook, such as SR state sync and feature list patching.
        cachedGameHelperClassLoader = appClassLoader

        if (AndroidInternals.useCompatibilityLsr()) {
            // If ServiceManager registration is blocked, keep a local binder path so
            // Game Helper can still talk to the reconstructed compatibility service.
            installLsrServiceManagerFallback()
        } else {
            AndroidInternals.log("Using stock lenovosr on baldur; compatibility fallback binder disabled")
        }

        if (!isBaldurBoard()) {
            // Make Game Helper follow the Baldur feature path on non-Baldur devices.
            findClass("com.zui.util.DeviceUtils").hook {
                injectMember {
                    method {
                        name = "isBaldur"
                        emptyParam()
                    }
                    replaceToTrue()
                }
            }
        }

        // Normalize the floating-bar feature inventory itself, then backstop the
        // runtime feature checks that consult it later.
        installRomFeatureHooks()
        // Settings uses a separate feature registry, so patch it independently.
        installGameSettingFeatureHooks()
        // Keep SR device support enabled while swapping the stock
        // whitelist inputs to the active gpp_app_list view.
        installSuperResolutionAvailabilityHooks()
        // Replace the stock SR whitelist lookups with the active gpp_app_list view.
        installSuperResolutionSupportHooks()
        // Normalize AI sound package gating so both PUBG package names work
        // regardless of the device's ROW region flag.
        installAiSoundEnhancementHooks()
        // Wide Vision and 4D vibration still keep separate regional lists in
        // stock Game Helper, so merge them at runtime.
        installWideVisionHooks()
        installVibrationSupportHooks()

        AndroidInternals.log("Installed YukiHook game helper hooks")
    }

    private fun PackageParam.installRomFeatureHooks() {
        findClass("com.zui.game.service.RomFeatures").hook {
            injectMember {
                method {
                    name = "isFeatureOpen"
                    param(String::class.java)
                }
                replaceAny {
                    when (args.firstOrNull() as? String) {
                        SUPER_RESOLUTION_FEATURE_KEY -> true
                        FOUR_D_VIBRATE_FEATURE_KEY -> true
                        COLORFUL_LIGHT_FEATURE_KEY -> isBaldurBoard()
                        else -> callOriginal()
                    }
                }
            }
            injectMember {
                method {
                    name = "getKeyList"
                    emptyParam()
                }
                afterHook {
                    patchRomFeatureKeyList(instanceOrNull)
                    result = runCatching {
                        @Suppress("UNCHECKED_CAST")
                        XposedHelpers.getObjectField(instanceOrNull, "keyList") as? List<Any?>
                    }.getOrNull() ?: result
                }
            }
        }

        findClass("com.zui.game.service.sys.item.KeyContainer").hook {
            injectMember {
                method {
                    name = "isFeatureOpened"
                    paramCount = 1
                }
                replaceAny {
                    when (resolveKeyContainerFeatureKey(instanceOrNull)) {
                        SUPER_RESOLUTION_FEATURE_KEY -> true
                        FOUR_D_VIBRATE_FEATURE_KEY -> true
                        COLORFUL_LIGHT_FEATURE_KEY -> isBaldurBoard()
                        else -> callOriginal()
                    }
                }
            }
        }

        findClass("com.zui.game.service.FeatureKey\$Companion").hook {
            injectMember {
                method {
                    name = "createByKeys"
                    param(Array<String>::class.java)
                }
                beforeHook {
                    val keys = (args.firstOrNull() as? Array<*>)?.mapNotNull { it as? String } ?: return@beforeHook
                    val normalized = normalizeFeatureKeys(keys)
                    if (keys.size != normalized.size || keys.toList() != normalized.toList()) {
                        AndroidInternals.log("Normalized FeatureKey list from ${keys.size} to ${normalized.size}")
                    }
                    args[0] = normalized
                }
            }
        }

        patchExistingRomFeatureSets()
    }

    private fun PackageParam.installGameSettingFeatureHooks() {
        findClass("com.zui.ugame.gamesetting.feature.FeatureList").hook {
            injectMember {
                method {
                    name = "list"
                    emptyParam()
                }
                replaceAny {
                    val original = runCatching {
                        @Suppress("UNCHECKED_CAST")
                        callOriginal() as? List<Any?>
                    }.getOrNull() ?: emptyList()

                    if (isBaldurBoard()) {
                        original
                    } else {
                        original.filterNot {
                            it?.javaClass?.name == "com.zui.ugame.gamesetting.feature.FEATURE_COLORFUL_LIGHT"
                        }
                    }
                }
            }
        }

        // The settings screen also keeps a static XML entry, so remove it after inflation.
        findClass("com.zui.ugame.gamesetting.ui.options.SaverGameSettingsExtension").hook {
            injectMember {
                method {
                    name = "onCreatePreferences"
                    paramCount = 2
                }
                afterHook {
                    removeColorfulLightPreference(instanceOrNull)
                }
            }
            injectMember {
                method {
                    name = "onResume"
                    emptyParam()
                }
                afterHook {
                    removeColorfulLightPreference(instanceOrNull)
                }
            }
        }

        // Backstop the ViewModel cache in case it was built before FeatureList.list() was filtered.
        findClass("com.zui.ugame.gamesetting.ui.options.SaverGameSettingsExtensionViewModel").hook {
            injectMember {
                method {
                    name = "getFeatureList"
                    emptyParam()
                }
                replaceAny {
                    val original = runCatching {
                        @Suppress("UNCHECKED_CAST")
                        callOriginal() as? List<Any?>
                    }.getOrNull() ?: emptyList()
                    normalizeGameSettingFeatureList(original)
                }
            }
        }

        findClass("com.zui.ugame.gamesetting.feature.FEATURE_SUPER_RESOLUTION").hook {
            injectMember {
                method {
                    name = "isEnable"
                    param(Context::class.java)
                }
                replaceToTrue()
            }
        }

        findClass("com.zui.ugame.gamesetting.feature.FEATURE_COLORFUL_LIGHT").hook {
            injectMember {
                method {
                    name = "isEnable"
                    param(Context::class.java)
                }
                replaceAny {
                    isBaldurBoard()
                }
            }
            injectMember {
                method {
                    name = "onPreferenceTreeClick"
                    paramCount = 3
                }
                replaceAny {
                    if (isBaldurBoard()) callOriginal() else false
                }
            }
        }
    }

    private fun PackageParam.installSuperResolutionAvailabilityHooks() {
        findClass("com.zui.game.service.di.Settings").hook {
            injectMember {
                method {
                    name = "getSupportSuperResolution"
                    emptyParam()
                }
                replaceToTrue()
            }
        }

        installSuperResolutionResourceHooks()

        // Backstop the rendered list so stale SR buttons cannot survive after
        // the runtime whitelist changes.
        findClass("com.zui.game.service.ui.GameHelperViewController\$getCurrentView\$1\$1").hook {
            injectMember {
                method {
                    name = "emit"
                    paramCount = 2
                }
                afterHook {
                    val controller = runCatching {
                        XposedHelpers.getObjectField(instanceOrNull, "this\$0")
                    }.getOrNull()
                    normalizeFloatingBarItems(controller, "getCurrentView collector")
                    // If the LiveData was empty (list not populated yet), schedule a
                    // retry after the current message finishes so the original code
                    // has a chance to fill the list first.
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        normalizeFloatingBarItems(controller, "getCurrentView collector (deferred)")
                    }
                }
            }
        }
    }

    private fun PackageParam.installSuperResolutionResourceHooks() {
        findClass("android.content.res.Resources").hook {
            injectMember {
                method {
                    name = "getStringArray"
                    param(Int::class.javaPrimitiveType!!)
                }
                replaceAny {
                    val resources = instanceOrNull as? Resources ?: return@replaceAny callOriginal()
                    val resId = args.firstOrNull() as? Int ?: return@replaceAny callOriginal()
                    val entryName = runCatching { resources.getResourceEntryName(resId) }.getOrNull()
                    val packageName = runCatching { resources.getResourcePackageName(resId) }.getOrNull()

                    if (entryName != GAME_RESOLUTION_APPS_ARRAY || packageName != GAME_HELPER_PACKAGE) {
                        return@replaceAny callOriginal()
                    }

                    val originalEntries = runCatching {
                        callOriginal() as? Array<String>
                    }.getOrElse {
                        AndroidInternals.log("Failed to read stock $GAME_RESOLUTION_APPS_ARRAY entries", it)
                        null
                    } ?: return@replaceAny emptyArray<String>()

                    val overriddenEntries = resolveSuperResolutionArrayEntries(originalEntries)
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
        }
    }

    private fun PackageParam.installSuperResolutionSupportHooks() {
        hookSuperResolutionSupportMethod(
            "com.zui.ugame.gamesetting.data.RepositoryImpl",
            "querySuperResolutionSupportPackage",
        )
        hookSuperResolutionSupportMethod(
            "com.zui.ugame.gamesetting.data.source.PreDownloadSourceImpl",
            "querySuperResolutionSupportPackage",
        )
        hookSuperResolutionSupportMethod(
            "com.zui.game.service.util.ConstValueKt",
            "getSuperResolutionSupportPackages",
        )
        hookSuperResolutionSupportMethod(
            "com.zui.game.service.util.ConstValueKt",
            "getSuperResolutionSupportPackagesForAll",
        )
    }

    private fun PackageParam.installAiSoundEnhancementHooks() {
        hookAiSoundItemInitialization()
        hookAiSoundToggleHandler()
        hookAiSoundFloatingNotice()
    }

    private fun PackageParam.installWideVisionHooks() {
        val className = "com.zui.ugame.gamesetting.ui.options.content.widevision.MoreGameViewModel"
        val methodName = "<init>"
        if (!hasMethodWithParamCount(className, methodName, 2, appClassLoader)) {
            AndroidInternals.log("Skip missing $className#$methodName/2 in Game Helper")
            return
        }

        findClass(className).hook {
            injectMember {
                method {
                    name = methodName
                    paramCount = 2
                }
                afterHook {
                    mergeWideVisionKnownGames(instanceOrNull)
                }
            }
        }
    }

    private fun PackageParam.installVibrationSupportHooks() {
        val className = "com.zui.game.service.vibrate.VibrationToolKt"
        val methodName = "isGameSupport4dVibration"
        if (!hasMethodWithParamCount(className, methodName, 2, appClassLoader)) {
            AndroidInternals.log("Skip missing $className#$methodName/2 in Game Helper")
            return
        }

        findClass(className).hook {
            injectMember {
                method {
                    name = methodName
                    paramCount = 2
                }
                replaceAny {
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
                        return@replaceAny original.mapNotNull { it as? String }
                    }

                    mergeVibrationSupportKeys(context, packageName, original)
                }
            }
        }
    }

    private fun PackageParam.hookAiSoundItemInitialization() {
        val className = "com.zui.game.service.sys.item.ItemAISoundEnhancement"
        val methodName = "initFromSavedState"
        if (!hasMethodWithParamCount(className, methodName, 2, appClassLoader)) {
            AndroidInternals.log("Skip missing $className#$methodName/2 in Game Helper")
            return
        }

        findClass(className).hook {
            injectMember {
                method {
                    name = methodName
                    paramCount = 2
                }
                afterHook {
                    val context = args.firstOrNull() as? Context ?: return@afterHook
                    val packageName = args.getOrNull(1) as? String ?: return@afterHook
                    if (!isAiSoundSupportedPackage(packageName)) return@afterHook

                    val enabled = readAiSoundSetting(context, defaultValue = 1) == 1
                    applyAiSoundState(instanceOrNull, context, enabled)
                    result = if (enabled) 0 else 1
                }
            }
        }
    }

    private fun PackageParam.hookAiSoundToggleHandler() {
        val className = "com.zui.game.service.sys.item.ItemAISoundEnhancement\$initFromSavedState\$1"
        if (!hasMethodWithParamCount(className, "onNoClick", 0, appClassLoader)) {
            AndroidInternals.log("Skip missing $className#onNoClick/0 in Game Helper")
            return
        }

        findClass(className).hook {
            injectMember {
                method {
                    name = "onNoClick"
                    emptyParam()
                }
                replaceAny {
                    val packageName = resolveAiSoundCallbackPackage(instanceOrNull)
                        ?: return@replaceAny callOriginal()
                    if (!isAiSoundSupportedPackage(packageName)) {
                        return@replaceAny callOriginal()
                    }

                    val context = resolveAiSoundCallbackContext(instanceOrNull)
                        ?: return@replaceAny callOriginal()
                    val item = resolveAiSoundCallbackItem(instanceOrNull)
                        ?: return@replaceAny callOriginal()
                    toggleAiSound(item, context)
                    null
                }
            }

            injectMember {
                method {
                    name = "onToast"
                    emptyParam()
                }
                replaceAny {
                    val packageName = resolveAiSoundCallbackPackage(instanceOrNull)
                    if (packageName != null && isAiSoundSupportedPackage(packageName)) {
                        return@replaceAny null
                    }
                    callOriginal()
                }
            }
        }
    }

    private fun PackageParam.hookAiSoundFloatingNotice() {
        val className = "com.zui.game.service.ui.FloatingGameNoticController"
        val methodName = "checkAISoundEnhancementEnable"
        if (!hasMethodWithParamCount(className, methodName, 1, appClassLoader)) {
            AndroidInternals.log("Skip missing $className#$methodName/1 in Game Helper")
            return
        }

        findClass(className).hook {
            injectMember {
                method {
                    name = methodName
                    paramCount = 1
                }
                replaceAny {
                    val packageName = args.firstOrNull() as? String ?: return@replaceAny callOriginal()
                    if (!isAiSoundSupportedPackage(packageName)) {
                        return@replaceAny callOriginal()
                    }

                    val context = runCatching {
                        XposedHelpers.getObjectField(instanceOrNull, "mContext") as? Context
                    }.getOrNull() ?: return@replaceAny callOriginal()

                    readAiSoundSetting(context, defaultValue = 1) == 1 && isAiSoundFeatureOpened()
                }
            }
        }
    }

    private fun PackageParam.hookSuperResolutionSupportMethod(className: String, methodName: String) {
        findClass(className).hook {
            injectMember {
                method {
                    name = methodName
                    emptyParam()
                }
                replaceAny {
                    resolveRegisteredGamePackagesOrOriginal("$className#$methodName")
                }
            }
        }
    }

    private fun isAiSoundSupportedPackage(packageName: String?): Boolean {
        return packageName != null && packageName in AI_SOUND_SUPPORTED_PACKAGES
    }

    private fun resolveAiSoundCallbackPackage(callback: Any?): String? {
        return runCatching {
            XposedHelpers.getObjectField(callback, "\$pkg") as? String
        }.getOrNull()
    }

    private fun resolveAiSoundCallbackContext(callback: Any?): Context? {
        return runCatching {
            XposedHelpers.getObjectField(callback, "\$context") as? Context
        }.getOrNull()
    }

    private fun resolveAiSoundCallbackItem(callback: Any?): Any? {
        return runCatching {
            XposedHelpers.getObjectField(callback, "this\$0")
        }.getOrNull()
    }

    private fun readAiSoundSetting(context: Context, defaultValue: Int): Int {
        val classLoader = resolveGameHelperClassLoader() ?: context.classLoader

        return runCatching {
            val utilClass = XposedHelpers.findClass("com.zui.util.SettingsValueUtilKt", classLoader)
            XposedHelpers.callStaticMethod(
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

    private fun writeAiSoundSetting(context: Context, value: Int) {
        val classLoader = resolveGameHelperClassLoader() ?: context.classLoader
        val stored = runCatching {
            val utilClass = XposedHelpers.findClass("com.zui.util.SettingsValueUtilKt", classLoader)
            XposedHelpers.callStaticMethod(
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

    private fun isAiSoundFeatureOpened(): Boolean {
        val classLoader = resolveGameHelperClassLoader() ?: return false

        return runCatching {
            val itemClass = XposedHelpers.findClass(
                "com.zui.game.service.sys.item.ItemAISoundEnhancement",
                classLoader,
            )
            val featuresClass = XposedHelpers.findClass(
                "com.zui.game.service.FeaturesBaseOnRomKt",
                classLoader,
            )
            val companion = XposedHelpers.getStaticObjectField(itemClass, "Companion")
            val romFeatures = XposedHelpers.callStaticMethod(featuresClass, "getRomFeatures")
            XposedHelpers.callMethod(companion, "isFeatureOpened", romFeatures) as? Boolean ?: false
        }.getOrElse {
            AndroidInternals.log("Failed to evaluate AI sound feature availability", it)
            false
        }
    }

    private fun applyAiSoundState(item: Any?, context: Context, enabled: Boolean) {
        if (item == null) return

        val status = if (enabled) 0 else 1
        runCatching {
            XposedHelpers.callMethod(item, "setMStatus", status)
            XposedHelpers.callMethod(item, "setMNoClick", true)
            val liveData = XposedHelpers.callMethod(item, "getMStatusLive")
            XposedHelpers.callMethod(liveData, "postValue", status)
        }.onFailure {
            AndroidInternals.log("Failed to apply AI sound item state", it)
        }

        runCatching {
            (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.setParameters(
                if (enabled) AI_SOUND_PARAMETER_ENABLED else AI_SOUND_PARAMETER_DISABLED,
            )
        }.onFailure {
            AndroidInternals.log("Failed to update AI sound audio parameters", it)
        }
    }

    private fun toggleAiSound(item: Any, context: Context) {
        val enable = runCatching {
            (XposedHelpers.callMethod(item, "getMStatus") as? Int) != 0
        }.getOrElse {
            AndroidInternals.log("Failed to read current AI sound toggle state", it)
            true
        }

        runCatching {
            XposedHelpers.callMethod(item, "change2Status", if (enable) 0 else 1)
        }.onFailure {
            AndroidInternals.log("Failed to toggle AI sound state through change2Status()", it)
            applyAiSoundState(item, context, enable)
        }

        runCatching {
            (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.setParameters(
                if (enable) AI_SOUND_PARAMETER_ENABLED else AI_SOUND_PARAMETER_DISABLED,
            )
        }.onFailure {
            AndroidInternals.log("Failed to update AI sound audio parameters during toggle", it)
        }

        writeAiSoundSetting(context, if (enable) 1 else 0)
    }

    private fun mergeWideVisionKnownGames(viewModel: Any?) {
        if (viewModel == null) return

        val knownGames = readStringArrayField(viewModel, "knownGames")
        val rowKnownGames = readStringArrayField(viewModel, "rowKnownGames")
        val merged = mergeStringArrays(knownGames, rowKnownGames)
        if (merged.isEmpty()) return

        runCatching {
            XposedHelpers.setObjectField(viewModel, "knownGames", merged)
            XposedHelpers.setObjectField(viewModel, "rowKnownGames", merged)
        }.onFailure {
            AndroidInternals.log("Failed to merge Wide Vision knownGames lists", it)
        }
    }

    private fun mergeVibrationSupportKeys(
        context: Context,
        packageName: String?,
        original: List<Any?>,
    ): List<String> {
        val merged = LinkedHashSet<String>()
        original.mapNotNullTo(merged) { it as? String }

        val normalizedPackage = packageName?.takeIf { it.isNotBlank() } ?: return merged.toList()
        resolveVibrationResourceMatches(context, normalizedPackage).forEach { merged += it }
        if (normalizedPackage in PUBG_VARIANT_PACKAGES) {
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
            val featuresClass = XposedHelpers.findClass(
                "com.zui.game.service.FeaturesBaseOnRomKt",
                classLoader,
            )
            val romFeatures = XposedHelpers.callStaticMethod(featuresClass, "getRomFeatures")
            linkedSetOf(
                XposedHelpers.callMethod(romFeatures, "getSupportVibrationGameListArrayId") as? Int,
                XposedHelpers.callMethod(romFeatures, "getSupportVibrationGameListArrayIdRow") as? Int,
            ).filterNotNull().toSet()
        }.getOrElse {
            AndroidInternals.log("Failed to resolve vibration support array ids", it)
            emptySet()
        }
    }

    private fun readStringArrayField(target: Any, fieldName: String): Array<String> {
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            ((XposedHelpers.getObjectField(target, fieldName) as? Array<*>)?.mapNotNull { it as? String }?.toTypedArray())
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

    private fun HookParam.resolveRegisteredGamePackagesOrOriginal(source: String): Any? {
        val packages = resolveSupportedPackages()
        if (packages != null) {
            AndroidInternals.log("Resolved ${packages.size} SR whitelist packages for $source")
            return packages
        }

        AndroidInternals.log("Falling back to original super resolution package list for $source")
        return runCatching { callOriginal() }.getOrElse {
            AndroidInternals.log("Failed to call original method for $source", it)
            emptyList<String>()
        }
    }

    private fun resolveControllerPackageName(lambdaInstance: Any?): String? {
        // Walk the this$0 chain from nested lambdas up to GameHelperViewController,
        // then read pkgName.  Check pkgName at each level BEFORE navigating deeper,
        // because the caller may already pass the GameHelperViewController itself
        // (e.g. $getCurrentView$1$1.this$0 → GameHelperViewController directly).
        return runCatching {
            var current: Any? = lambdaInstance
            for (depth in 0 until 6) {
                val pkgName = runCatching {
                    XposedHelpers.getObjectField(current, "pkgName") as? String
                }.getOrNull()
                if (pkgName != null) return@runCatching pkgName
                current = XposedHelpers.getObjectField(current, "this\$0")
            }
            null
        }.getOrElse {
            AndroidInternals.log("Failed to resolve GameHelperViewController pkgName", it)
            null
        }
    }

    private fun resolveSuperResolutionArrayEntries(originalEntries: Array<String>): Array<String>? {
        val packages = resolveSupportedPackages() ?: return null
        val originalByPackage = LinkedHashMap<String, String>(originalEntries.size)

        originalEntries.forEach { entry ->
            val packageName = entry.substringBefore('#').trim()
            if (packageName.isNotBlank()) {
                originalByPackage[packageName] = entry
            }
        }

        val normalized = LinkedHashSet<String>(packages.size)
        packages.forEach { packageName ->
            if (packageName.isBlank()) return@forEach
            normalized += originalByPackage[packageName]
                ?: "$packageName$DEFAULT_SUPER_RESOLUTION_ARRAY_FLAGS"
        }

        return normalized.toTypedArray()
    }

    private fun resolveSupportedPackages(): List<String>? {
        return runCatching {
            readSupportedPackagesFromSettings()?.let { packages ->
                val signature = "settings:${packages.joinToString(",")}"
                val cached = cachedSupportedPackages
                if (cached != null && cachedSupportedPackagesSignature == signature) {
                    return@runCatching ArrayList(cached)
                }

                cachedSupportedPackages = LinkedHashSet(packages)
                cachedSupportedPackagesSignature = signature
                return@runCatching packages
            }

            val whitelistFile = File(SupportedPackageList.ACTIVE_LIST_PATH)
            val signature = buildString {
                append("file:")
                append(whitelistFile.exists())
                append(':')
                append(whitelistFile.length())
                append(':')
                append(whitelistFile.lastModified())
            }
            val cached = cachedSupportedPackages
            if (cached != null && cachedSupportedPackagesSignature == signature) {
                return@runCatching ArrayList(cached)
            }

            val packages = SupportedPackageList.readPackages(whitelistFile)
                .filterTo(ArrayList()) { packageName ->
                    packageName.isNotBlank() && packageName != GAME_HELPER_PACKAGE
                }
            if (packages.isEmpty()) {
                cachedSupportedPackages = null
                cachedSupportedPackagesSignature = null
                return@runCatching null
            }

            cachedSupportedPackages = LinkedHashSet(packages)
            cachedSupportedPackagesSignature = signature
            packages
        }.getOrElse {
            AndroidInternals.log("Failed to resolve SR whitelist packages", it)
            null
        }
    }

    private fun readSupportedPackagesFromSettings(): List<String>? {
        val context = resolveSystemContext() ?: resolveProcessApplicationContext() ?: return null
        val rawValue = runCatching {
            Settings.Global.getString(context.contentResolver, SupportedPackageList.RUNTIME_SETTINGS_KEY)
        }.getOrElse {
            AndroidInternals.log("Failed to read runtime SR whitelist from Settings.Global", it)
            null
        } ?: return null

        return SupportedPackageList.parsePackages(rawValue)
            .filterTo(ArrayList()) { packageName ->
                packageName.isNotBlank() && packageName != GAME_HELPER_PACKAGE
            }
    }

    private fun shouldExposeSuperResolution(packageName: String): Boolean {
        if (packageName.isBlank() || packageName == GAME_HELPER_PACKAGE) {
            return false
        }

        val packages = resolveSupportedPackages() ?: return true
        return packageName in packages
    }

    private fun normalizeGameSettingFeatureList(features: List<Any?>): List<Any?> {
        if (isBaldurBoard()) return features
        return features.filterNot {
            resolveGameSettingFeatureKey(it) == GAME_HELPER_COLORFUL_LIGHT_PREFERENCE_KEY
        }
    }

    private fun resolveGameSettingFeatureKey(feature: Any?): String? {
        return runCatching { XposedHelpers.callMethod(feature, "getKey") as? String }.getOrNull()
    }

    private fun removeColorfulLightPreference(fragment: Any?) {
        if (isBaldurBoard()) return

        runCatching {
            XposedHelpers.callMethod(
                fragment,
                "tryRemovePreference",
                GAME_HELPER_COLORFUL_LIGHT_PREFERENCE_KEY,
            )
            AndroidInternals.log("Removed colorful light preference from Game Helper settings")
        }.onFailure {
            AndroidInternals.log("Failed to remove colorful light preference", it)
        }
    }

    private fun isBaldurBoard(): Boolean {
        return AndroidInternals.isBaldurBoard()
    }

    private fun normalizeFloatingBarItems(controller: Any?, source: String) {
        val packageName = resolveControllerPackageName(controller).orEmpty()
        val currentItems = resolveFloatingFeatureItems(controller) ?: return
        if (currentItems.isEmpty()) return   // List not populated yet — let the original code fill it first
        val normalized = ArrayList<Any>(currentItems.size)

        currentItems.forEach { item ->
            val key = resolveItemKey(item)
            when {
                key == COLORFUL_LIGHT_FEATURE_KEY && !isBaldurBoard() -> return@forEach
                key == SUPER_RESOLUTION_FEATURE_KEY && !shouldExposeSuperResolution(packageName) -> return@forEach
                key == FOUR_D_VIBRATE_FEATURE_KEY && !shouldExposeFourDVibration(controller, packageName) -> return@forEach
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

    private fun resolveFloatingFeatureItems(controller: Any?): List<Any> {
        val rootView = resolveFloatingRootView(controller) ?: return emptyList()
        val liveData = runCatching {
            XposedHelpers.callMethod(rootView, "getMFeatureListItems")
        }.getOrNull() ?: return emptyList()

        @Suppress("UNCHECKED_CAST")
        return runCatching {
            (XposedHelpers.callMethod(liveData, "getValue") as? List<Any?>)
                ?.filterNotNull()
                ?: emptyList()
        }.getOrElse {
            AndroidInternals.log("Failed to read floating feature list", it)
            emptyList()
        }
    }

    private fun resolveFloatingRootView(controller: Any?): Any? {
        return runCatching {
            XposedHelpers.getObjectField(controller, "mPortraitRootView")
        }.getOrNull() ?: runCatching {
            XposedHelpers.getObjectField(controller, "mLandscapeRootView")
        }.getOrNull()
    }

    private fun publishFloatingFeatureItems(controller: Any?, items: List<Any>) {
        val portraitChunkSize = resolvePortraitChunkSize()
        postFloatingItemsToRoot(
            rootView = runCatching { XposedHelpers.getObjectField(controller, "mPortraitRootView") }.getOrNull(),
            featureItems = items,
            buttonChunkSize = portraitChunkSize,
        )
        postFloatingItemsToRoot(
            rootView = runCatching { XposedHelpers.getObjectField(controller, "mLandscapeRootView") }.getOrNull(),
            featureItems = items,
            buttonChunkSize = 6,
        )
    }

    private fun postFloatingItemsToRoot(rootView: Any?, featureItems: List<Any>, buttonChunkSize: Int) {
        if (rootView == null) return

        runCatching {
            val featureLiveData = XposedHelpers.callMethod(rootView, "getMFeatureListItems")
            XposedHelpers.callMethod(featureLiveData, "postValue", featureItems)
        }.onFailure {
            AndroidInternals.log("Failed to publish floating feature items", it)
        }

        runCatching {
            val buttonLiveData = XposedHelpers.callMethod(rootView, "getMButtonItemsPortrait")
            XposedHelpers.callMethod(buttonLiveData, "postValue", featureItems.chunked(buttonChunkSize))
        }.onFailure {
            AndroidInternals.log("Failed to publish floating button items", it)
        }
    }

    private fun resolvePortraitChunkSize(): Int {
        return runCatching {
            val repositoryClass = XposedHelpers.findClass(
                "com.zui.game.service.data.Repository",
                resolveGameHelperClassLoader(),
            )
            val companion = XposedHelpers.getStaticObjectField(repositoryClass, "Companion")
            XposedHelpers.callMethod(companion, "getMAX_DISPLAY_COUNT_PORTRAIT") as? Int
        }.getOrNull() ?: 10
    }

    private fun resolveItemKey(item: Any?): String? {
        return runCatching { XposedHelpers.callMethod(item, "getKey") as? String }.getOrNull()
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
                XposedHelpers.callMethod(controller, "getContext") as? Context
            }.getOrNull() ?: return false
            val vibrationToolClass = XposedHelpers.findClass(
                "com.zui.game.service.vibrate.VibrationToolKt",
                classLoader,
            )
            @Suppress("UNCHECKED_CAST")
            XposedHelpers.callStaticMethod(
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
            XposedHelpers.callMethod(controller, "getMItem4DVibrate")
        }.getOrElse {
            AndroidInternals.log("Failed to resolve Item4DVibrate instance", it)
            null
        }
    }

    private fun resolveCurrentRomFeatureOrder(): List<String> {
        return runCatching {
            val classLoader = resolveGameHelperClassLoader() ?: return emptyList()
            val featuresClass = XposedHelpers.findClass(
                "com.zui.game.service.FeaturesBaseOnRomKt",
                classLoader,
            )
            val romFeatures = XposedHelpers.callStaticMethod(featuresClass, "getRomFeatures")
            @Suppress("UNCHECKED_CAST")
            (XposedHelpers.getObjectField(romFeatures, "keyList") as? List<Any?>)
                ?.mapNotNull { featureKey ->
                    runCatching { XposedHelpers.callMethod(featureKey, "getKey") as? String }.getOrNull()
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

    private fun resolveKeyContainerFeatureKey(container: Any?): String? {
        return runCatching { XposedHelpers.callMethod(container, "getKEY") as? String }.getOrNull()
    }

    private fun normalizeFeatureKeys(keys: Collection<String>): Array<String> {
        val normalized = LinkedHashSet<String>()

        keys.forEach { key ->
            if (key == COLORFUL_LIGHT_FEATURE_KEY && !isBaldurBoard()) return@forEach
            normalized += key
        }

        return normalized.toTypedArray()
    }

    private fun patchExistingRomFeatureSets() {
        runCatching {
            val classLoader = resolveGameHelperClassLoader() ?: return
            val featuresClass = XposedHelpers.findClass("com.zui.game.service.FeaturesBaseOnRomKt", classLoader)
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

    private fun patchRomFeatureKeyList(romFeatures: Any?) {
        val keyList = runCatching {
            XposedHelpers.getObjectField(romFeatures, "keyList") as? List<Any?>
        }.getOrNull() ?: return

        val normalized = LinkedHashMap<String, Any>()
        keyList.forEach { featureKey ->
            if (featureKey == null) return@forEach
            val key = runCatching { XposedHelpers.callMethod(featureKey, "getKey") as? String }.getOrNull() ?: return@forEach
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
            XposedHelpers.setObjectField(romFeatures, "keyList", ArrayList(normalized.values))
        }.onFailure {
            AndroidInternals.log("Failed to overwrite RomFeatures key list", it)
        }
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
        return runCatching {
            val classLoader = resolveGameHelperClassLoader() ?: return emptyList()
            val featureKeyClass = XposedHelpers.findClass("com.zui.game.service.FeatureKey", classLoader)
            val companion = XposedHelpers.getStaticObjectField(featureKeyClass, "Companion")
            @Suppress("UNCHECKED_CAST")
            XposedHelpers.callMethod(companion, "createByKeys", keys) as? List<Any> ?: emptyList()
        }.getOrElse {
            AndroidInternals.log("Failed to create FeatureKey instances", it)
            emptyList()
        }
    }

    private fun resolveGameHelperClassLoader(): ClassLoader? {
        return cachedGameHelperClassLoader
            ?: resolveProcessApplicationContext()?.javaClass?.classLoader
    }

    private fun hasMethodWithParamCount(
        className: String,
        methodName: String,
        paramCount: Int,
        classLoader: ClassLoader?,
    ): Boolean {
        return runCatching {
            val resolvedClassLoader = classLoader
                ?: resolveProcessApplicationContext()?.javaClass?.classLoader
                ?: Thread.currentThread().contextClassLoader

            XposedHelpers.findClass(className, resolvedClassLoader).declaredMethods.any {
                it.name == methodName && it.parameterTypes.size == paramCount
            }
        }.getOrDefault(false)
    }

    private fun resolveProcessApplicationContext(): Context? {
        return runCatching {
            XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentApplication",
            ) as? Context
        }.getOrElse {
            AndroidInternals.log("Failed to resolve process application context", it)
            null
        }
    }

    private fun resolveSystemContext(instance: Any? = null): Context? {
        (instance?.let {
            runCatching { XposedHelpers.getObjectField(it, "mContext") as? Context }.getOrNull()
        } ?: systemContext)?.let { context ->
            rememberSystemContext(context)
            return context
        }
        return resolveProcessApplicationContext()?.also(::rememberSystemContext)
    }

    private fun rememberSystemContext(context: Context) {
        if (systemContext == null) {
            systemContext = context.applicationContext ?: context
        }
    }

    private fun HookParam.ensureLsrRegistered() {
        if (!AndroidInternals.useCompatibilityLsr()) {
            return
        }
        runCatching {
            val systemServer = instance<Any>()
            val context = XposedHelpers.getObjectField(systemServer, "mSystemContext") as? Context ?: return
            rememberSystemContext(context)
            LsrServiceRegistry.ensureRegistered(context)
        }.onFailure {
            AndroidInternals.log("Failed to register lenovosr from Yuki hook", it)
        }
    }

    // -------------------------------------------------------------------------
    // ServiceManager fallback: intercept getService / checkService so callers
    // can still obtain lenovosr when SELinux blocks addService().
    // -------------------------------------------------------------------------

    private fun PackageParam.installLsrServiceManagerFallback() {
        findClass("android.os.ServiceManager").hook {
            injectMember {
                method {
                    name = "getService"
                    param(String::class.java)
                }
                afterHook {
                    val svcName = args.firstOrNull()
                    if (svcName == LsrService.LSR_SERVICE) {
                        if (result == null) {
                            val binder = getOrCreateFallbackLsrBinder()
                            result = binder
                            AndroidInternals.log("ServiceManager.getService(lenovosr) → fallback binder=$binder")
                        } else {
                            AndroidInternals.log("ServiceManager.getService(lenovosr) → original result=$result")
                        }
                    }
                }
            }
            injectMember {
                method {
                    name = "checkService"
                    param(String::class.java)
                }
                afterHook {
                    val svcName = args.firstOrNull()
                    if (svcName == LsrService.LSR_SERVICE) {
                        if (result == null) {
                            val binder = getOrCreateFallbackLsrBinder()
                            result = binder
                            AndroidInternals.log("ServiceManager.checkService(lenovosr) → fallback binder=$binder")
                        } else {
                            AndroidInternals.log("ServiceManager.checkService(lenovosr) → original result=$result")
                        }
                    }
                }
            }
        }
    }

    private fun getOrCreateFallbackLsrBinder(): IBinder? {
        if (!AndroidInternals.useCompatibilityLsr()) {
            return null
        }
        // In system_server the binder that failed to register is retained by
        // LsrServiceRegistry.
        LsrServiceRegistry.getFallbackBinder()?.let { return it }

        // In client processes, construct a local in-process service instead.
        fallbackLsrBinder?.let { return it }
        synchronized(this) {
            fallbackLsrBinder?.let { return it }
            val context = resolveProcessApplicationContext() ?: return null
            val service = LsrService(context)
            service.onStartLocal()
            val binder: IBinder = LsrService.BinderService(service)
            fallbackLsrBinder = binder
            AndroidInternals.log("Created fallback local LsrService binder")
            return binder
        }
    }
}
