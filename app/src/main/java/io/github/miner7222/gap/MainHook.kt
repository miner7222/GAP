package io.github.miner7222.gap

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Bundle
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
        private const val AI_SOUND_SETTING_KEY = "key_game_aisound"
        private const val AI_SOUND_PARAMETER_ENABLED = "aisound=true"
        private const val AI_SOUND_PARAMETER_DISABLED = "aisound=false"
        private const val COLORFUL_LIGHT_FEATURE_KEY = "key_colorful_light"
        private const val GAME_RESOLUTION_APPS_ARRAY = "game_resolution_apps"
        private const val DEFAULT_SUPER_RESOLUTION_ARRAY_FLAGS = "#1#1"
        private const val GAME_HELPER_SETTINGS_ACTIVITY = "com.zui.ugame.gamesetting.ui.options.OptionActivity"
        private const val GAME_HELPER_LABEL_RES = "app_name"
        private const val GAME_HELPER_ICON_RES = "ic_launcher_game"
        private const val GAME_HELPER_ROUND_ICON_RES = "ic_launcher_round"
        private const val GAME_HELPER_SETTINGS_ICON_RES = "ic_game_assistant_svg"
        private const val GAME_HELPER_COLORFUL_LIGHT_PREFERENCE_KEY = "option_item_colorful_light"
        private const val SETTINGS_ICON_META_KEY = "com.android.settings.icon"
        private val AI_SOUND_SUPPORTED_PACKAGES = linkedSetOf(
            "com.tencent.ig",
            "com.tencent.tmgp.pubgmhd",
            "com.rekoo.pubgm",
            "com.pubg.krmobile",
            "com.vng.pubgmobile",
            "com.pubg.imobile",
        )
    }

    private val systemHooksInstalled = AtomicBoolean(false)
    private val clientHooksInstalled = AtomicBoolean(false)
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

    /**
     * Prevent infinite recursion when our metadata hooks call
     * getResourcesForApplication(), which may re-enter PackageManager.
     */
    private val patchingGameHelper = ThreadLocal<Boolean>()

    override fun onInit() = configs {
        // YukiHook's internal debug logger queries PackageManager metadata,
        // which re-enters our system_server PackageManager hooks and can loop
        // during early boot. Keep it disabled even in debug builds.
        isDebug = false
    }

    override fun onHook() = encase {
        loadSystem {
            applySystemHooks()
        }

        loadApp {
            applyClientMetadataHooks()
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

        // Fix app label/icon lookups when a rebuilt Game Helper APK changes resource IDs.
        installPackageMetadataHooks()

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

        AndroidInternals.log("Installed YukiHook game helper hooks")
    }

    private fun PackageParam.applyClientMetadataHooks() {
        if (!clientHooksInstalled.compareAndSet(false, true)) return

        installClientPackageManagerHooks()

        AndroidInternals.log("Installed YukiHook client metadata hooks")
    }

    private fun PackageParam.installPackageMetadataHooks() {
        hookApplicationInfoMethod(
            "com.android.server.pm.ComputerEngine",
            "getApplicationInfoInternalBody",
            4,
            preferInstanceContext = true,
        )
        hookApplicationInfoMethod(
            "com.android.server.pm.ComputerEngine",
            "generateApplicationInfoFromSettings",
            4,
            preferInstanceContext = true,
        )
        hookApplicationInfoMethod(
            "com.android.server.pm.parsing.PackageInfoUtils",
            "generateApplicationInfo",
            5,
            preferInstanceContext = false,
        )
        hookActivityInfoMethod(
            "com.android.server.pm.ComputerEngine",
            "getActivityInfoInternalBody",
            4,
            preferInstanceContext = true,
        )
        hookActivityInfoMethod(
            "com.android.server.pm.parsing.PackageInfoUtils",
            "generateActivityInfo",
            6,
            preferInstanceContext = false,
        )
        hookActivityInfoMethod(
            "com.android.server.pm.parsing.PackageInfoUtils",
            "generateActivityInfo",
            7,
            preferInstanceContext = false,
        )
    }

    private fun PackageParam.hookApplicationInfoMethod(
        className: String,
        methodName: String,
        paramCount: Int,
        preferInstanceContext: Boolean,
    ) {
        if (!hasMethodWithParamCount(className, methodName, paramCount, appClassLoader)) {
            AndroidInternals.log("Skip missing $className#$methodName/$paramCount in system_server")
            return
        }

        findClass(className).hook {
            injectMember {
                method {
                    name = methodName
                    this.paramCount = paramCount
                }
                replaceAny {
                    if (patchingGameHelper.get() == true) return@replaceAny callOriginal()
                    val original = runCatching { callOriginal() as? ApplicationInfo }.getOrElse {
                        AndroidInternals.log("Failed to call original $className#$methodName", it)
                        null
                    }
                    patchingGameHelper.set(true)
                    try {
                        patchGameHelperApplicationInfo(
                            info = original,
                            preferredContext = if (preferInstanceContext) resolveSystemContext(instanceOrNull) else null,
                            source = "$className#$methodName",
                        )
                    } finally {
                        patchingGameHelper.set(false)
                    }
                }
            }
        }
    }

    private fun PackageParam.hookActivityInfoMethod(
        className: String,
        methodName: String,
        paramCount: Int,
        preferInstanceContext: Boolean,
    ) {
        if (!hasMethodWithParamCount(className, methodName, paramCount, appClassLoader)) {
            AndroidInternals.log("Skip missing $className#$methodName/$paramCount in system_server")
            return
        }

        findClass(className).hook {
            injectMember {
                method {
                    name = methodName
                    this.paramCount = paramCount
                }
                replaceAny {
                    if (patchingGameHelper.get() == true) return@replaceAny callOriginal()
                    val original = runCatching { callOriginal() as? ActivityInfo }.getOrElse {
                        AndroidInternals.log("Failed to call original $className#$methodName", it)
                        null
                    }
                    patchingGameHelper.set(true)
                    try {
                        patchGameHelperActivityInfo(
                            info = original,
                            preferredContext = if (preferInstanceContext) resolveSystemContext(instanceOrNull) else null,
                            source = "$className#$methodName",
                        )
                    } finally {
                        patchingGameHelper.set(false)
                    }
                }
            }
        }
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
            }
            normalized += item
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

    private fun hasSameKeys(before: List<Any>, after: List<Any>): Boolean {
        if (before.size != after.size) return false
        return before.map(::resolveItemKey) == after.map(::resolveItemKey)
    }

    private fun patchGameHelperApplicationInfo(
        info: ApplicationInfo?,
        preferredContext: Context?,
        source: String,
    ): ApplicationInfo? {
        if (info?.packageName != GAME_HELPER_PACKAGE) return info

        val context = preferredContext ?: resolveSystemContext()
        if (context == null) {
            AndroidInternals.log("No context available to patch $GAME_HELPER_PACKAGE from $source")
            return info
        }

        val packageManager = context.packageManager ?: run {
            AndroidInternals.log("PackageManager unavailable while patching $GAME_HELPER_PACKAGE from $source")
            return info
        }

        val resources = runCatching {
            packageManager.getResourcesForApplication(GAME_HELPER_PACKAGE)
        }.getOrElse {
            AndroidInternals.log("Failed to load resources for $GAME_HELPER_PACKAGE from $source", it)
            return info
        }

        resolveStringResource(resources, GAME_HELPER_LABEL_RES)?.let { label ->
            info.nonLocalizedLabel = label
            info.labelRes = 0
        }

        resolveDrawableResource(resources, GAME_HELPER_ICON_RES).takeIf { it != 0 }?.let {
            info.icon = it
        }

        resolveDrawableResource(resources, GAME_HELPER_ROUND_ICON_RES).takeIf { it != 0 }?.let {
            setRoundIconResource(info, it)
        }

        resolveDrawableResource(resources, GAME_HELPER_SETTINGS_ICON_RES).takeIf { it != 0 }?.let { settingsIcon ->
            if (info.metaData == null) {
                info.metaData = Bundle()
            }
            info.metaData?.putInt(SETTINGS_ICON_META_KEY, settingsIcon)
        }

        return info
    }

    private fun patchGameHelperActivityInfo(
        info: ActivityInfo?,
        preferredContext: Context?,
        source: String,
    ): ActivityInfo? {
        if (info?.packageName != GAME_HELPER_PACKAGE) return info

        val context = preferredContext ?: resolveSystemContext()
        if (context == null) {
            AndroidInternals.log("No context available to patch $GAME_HELPER_PACKAGE activity from $source")
            return info
        }

        val packageManager = context.packageManager ?: run {
            AndroidInternals.log("PackageManager unavailable while patching $GAME_HELPER_PACKAGE activity from $source")
            return info
        }

        val resources = runCatching {
            packageManager.getResourcesForApplication(GAME_HELPER_PACKAGE)
        }.getOrElse {
            AndroidInternals.log("Failed to load resources for $GAME_HELPER_PACKAGE activity from $source", it)
            return info
        }

        patchGameHelperApplicationInfo(
            info = info.applicationInfo,
            preferredContext = context,
            source = "$source#applicationInfo",
        )

        resolveStringResource(resources, GAME_HELPER_LABEL_RES)?.let { label ->
            info.nonLocalizedLabel = label
            info.labelRes = 0
        }

        val iconName = if (info.name == GAME_HELPER_SETTINGS_ACTIVITY) {
            GAME_HELPER_SETTINGS_ICON_RES
        } else {
            GAME_HELPER_ICON_RES
        }

        resolveDrawableResource(resources, iconName).takeIf { it != 0 }?.let {
            info.icon = it
        }

        resolveDrawableResource(resources, GAME_HELPER_ROUND_ICON_RES).takeIf { it != 0 }?.let {
            setRoundIconResource(info, it)
        }

        resolveDrawableResource(resources, GAME_HELPER_SETTINGS_ICON_RES).takeIf { it != 0 }?.let { settingsIcon ->
            if (info.metaData == null) {
                info.metaData = Bundle()
            }
            info.metaData?.putInt(SETTINGS_ICON_META_KEY, settingsIcon)
        }

        return info
    }

    private fun PackageParam.installClientPackageManagerHooks() {
        findClass("android.content.pm.PackageItemInfo").hook {
            injectMember {
                method {
                    name = "loadLabel"
                    param(PackageManager::class.java)
                }
                replaceAny {
                    resolveClientLabel(
                        target = instanceOrNull,
                        packageManager = args.firstOrNull() as? PackageManager,
                    ) ?: callOriginal()
                }
            }
            injectMember {
                method {
                    name = "loadIcon"
                    param(PackageManager::class.java)
                }
                replaceAny {
                    resolveClientIcon(
                        target = instanceOrNull,
                        packageManager = args.firstOrNull() as? PackageManager,
                    ) ?: callOriginal()
                }
            }
        }

        findClass("android.app.ApplicationPackageManager").hook {
            injectMember {
                method {
                    name = "getApplicationLabel"
                    param(ApplicationInfo::class.java)
                }
                replaceAny {
                    resolveClientLabel(
                        target = args.firstOrNull(),
                        packageManager = instanceOrNull as? PackageManager,
                    ) ?: callOriginal()
                }
            }
            injectMember {
                method {
                    name = "getApplicationIcon"
                    param(ApplicationInfo::class.java)
                }
                replaceAny {
                    resolveClientIcon(
                        target = args.firstOrNull(),
                        packageManager = instanceOrNull as? PackageManager,
                    ) ?: callOriginal()
                }
            }
            injectMember {
                method {
                    name = "getApplicationIcon"
                    param(String::class.java)
                }
                replaceAny {
                    resolveClientIcon(
                        target = args.firstOrNull(),
                        packageManager = instanceOrNull as? PackageManager,
                    ) ?: callOriginal()
                }
            }
            injectMember {
                method {
                    name = "getActivityIcon"
                    param(ComponentName::class.java)
                }
                replaceAny {
                    resolveClientIcon(
                        target = args.firstOrNull(),
                        packageManager = instanceOrNull as? PackageManager,
                    ) ?: callOriginal()
                }
            }
            injectMember {
                method {
                    name = "getActivityIcon"
                    param(Intent::class.java)
                }
                replaceAny {
                    resolveClientIcon(
                        target = args.firstOrNull(),
                        packageManager = instanceOrNull as? PackageManager,
                    ) ?: callOriginal()
                }
            }
            injectMember {
                method {
                    name = "loadItemIcon"
                    param(android.content.pm.PackageItemInfo::class.java, ApplicationInfo::class.java)
                }
                replaceAny {
                    resolveClientIcon(
                        target = args.firstOrNull(),
                        packageManager = instanceOrNull as? PackageManager,
                    ) ?: callOriginal()
                }
            }
        }

        findClass("android.content.pm.ResolveInfo").hook {
            injectMember {
                method {
                    name = "loadLabel"
                    param(PackageManager::class.java)
                }
                replaceAny {
                    resolveClientLabel(
                        target = instanceOrNull,
                        packageManager = args.firstOrNull() as? PackageManager,
                    ) ?: callOriginal()
                }
            }
            injectMember {
                method {
                    name = "loadIcon"
                    param(PackageManager::class.java)
                }
                replaceAny {
                    resolveClientIcon(
                        target = instanceOrNull,
                        packageManager = args.firstOrNull() as? PackageManager,
                    ) ?: callOriginal()
                }
            }
        }

        findClass("android.content.pm.LauncherActivityInfo").hook {
            injectMember {
                method {
                    name = "getLabel"
                    emptyParam()
                }
                replaceAny {
                    resolveClientLabel(target = instanceOrNull, packageManager = null) ?: callOriginal()
                }
            }
            injectMember {
                method {
                    name = "getIcon"
                    param(Int::class.javaPrimitiveType!!)
                }
                replaceAny {
                    resolveClientIcon(target = instanceOrNull, packageManager = null) ?: callOriginal()
                }
            }
            injectMember {
                method {
                    name = "getBadgedIcon"
                    param(Int::class.javaPrimitiveType!!)
                }
                replaceAny {
                    resolveClientIcon(target = instanceOrNull, packageManager = null) ?: callOriginal()
                }
            }
        }
    }

    private fun resolveClientLabel(target: Any?, packageManager: PackageManager?): CharSequence? {
        if (resolveTargetPackageName(target) != GAME_HELPER_PACKAGE) return null
        return resolveStringResource(loadGameHelperResources(packageManager) ?: return null, GAME_HELPER_LABEL_RES)
    }

    private fun resolveClientIcon(target: Any?, packageManager: PackageManager?): Drawable? {
        if (resolveTargetPackageName(target) != GAME_HELPER_PACKAGE) return null

        val iconName = if (resolveTargetClassName(target) == GAME_HELPER_SETTINGS_ACTIVITY) {
            GAME_HELPER_SETTINGS_ICON_RES
        } else {
            GAME_HELPER_ICON_RES
        }

        val resources = loadGameHelperResources(packageManager) ?: return null
        val resId = resolveDrawableResource(resources, iconName)
        if (resId == 0) return null

        return runCatching {
            (packageManager ?: resolveProcessApplicationContext()?.packageManager)
                ?.getDrawable(GAME_HELPER_PACKAGE, resId, null)
        }.getOrNull()
    }

    private fun loadGameHelperResources(packageManager: PackageManager?): Resources? {
        val pm = packageManager ?: resolveProcessApplicationContext()?.packageManager ?: return null
        return runCatching { pm.getResourcesForApplication(GAME_HELPER_PACKAGE) }.getOrNull()
    }

    private fun resolveTargetPackageName(target: Any?): String? {
        return when (target) {
            is ResolveInfo -> target.activityInfo?.packageName ?: target.resolvePackageName
            is ComponentName -> target.packageName
            is Intent -> target.component?.packageName ?: target.`package`
            is String -> target
            else -> runCatching { XposedHelpers.getObjectField(target, "packageName") as? String }.getOrNull()
                ?: runCatching {
                    val componentName = XposedHelpers.callMethod(target, "getComponentName") as? android.content.ComponentName
                    componentName?.packageName
                }.getOrNull()
        }
    }

    private fun resolveTargetClassName(target: Any?): String? {
        return when (target) {
            is ResolveInfo -> target.activityInfo?.name
            is ComponentName -> target.className
            is Intent -> target.component?.className
            else -> runCatching { XposedHelpers.getObjectField(target, "name") as? String }.getOrNull()
                ?: runCatching {
                    val componentName = XposedHelpers.callMethod(target, "getComponentName") as? android.content.ComponentName
                    componentName?.className
                }.getOrNull()
        }
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
                normalized[SUPER_RESOLUTION_FEATURE_KEY] = featureKey
            }
        }

        runCatching {
            XposedHelpers.setObjectField(romFeatures, "keyList", ArrayList(normalized.values))
        }.onFailure {
            AndroidInternals.log("Failed to overwrite RomFeatures key list", it)
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

    private fun hasField(clazz: Class<*>, name: String): Boolean {
        return runCatching { XposedHelpers.findField(clazz, name) != null }.getOrDefault(false)
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

    private fun resolveStringResource(resources: Resources, name: String): String? {
        val resId = resources.getIdentifier(name, "string", GAME_HELPER_PACKAGE)
        if (resId == 0) return null
        return runCatching { resources.getString(resId) }.getOrNull()
    }

    private fun resolveDrawableResource(resources: Resources, name: String): Int {
        val mipmapRes = resources.getIdentifier(name, "mipmap", GAME_HELPER_PACKAGE)
        if (mipmapRes != 0) return mipmapRes
        return resources.getIdentifier(name, "drawable", GAME_HELPER_PACKAGE)
    }

    private fun setRoundIconResource(info: Any, resId: Int) {
        val fieldName = when {
            hasField(info.javaClass, "roundIconRes") -> "roundIconRes"
            hasField(info.javaClass, "roundIcon") -> "roundIcon"
            else -> null
        } ?: return

        runCatching {
            XposedHelpers.setIntField(info, fieldName, resId)
        }.onFailure {
            AndroidInternals.log("Failed to set round icon resource for $GAME_HELPER_PACKAGE", it)
        }
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
